package com.nodewayvpn.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import libXray.DialerController
import java.io.File

/**
 * Foreground [VpnService] that owns the embedded Xray-core instance.
 *
 * Flow: the WebView UI asks the activity to start the service with a `vless://` or
 * `olcrtc://` link, the service establishes the TUN interface, then hands the
 * descriptor to Xray-core through the `env.xray.tun.fd` entry of the generated
 * configuration.
 *
 * Two upstreams are supported:
 *  - `vless://` — Xray talks to the server itself;
 *  - `olcrtc://` — a separate [OlcrtcProcess] provides a local SOCKS5, and Xray
 *    dials it. That process needs [protect], which is unreachable from
 *    another process, so our own package is excluded from the tunnel instead.
 */
class NodewayVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    private var tunDescriptor: ParcelFileDescriptor? = null

    /** Профиль текущего подключения — попадает в широковещание состояния. */
    private var activeProfileId: String = ""

    /** Момент поднятия туннеля (мс) — по нему UI считает время подключения. */
    private var connectedAt: Long = 0L

    /** Отклик через туннель в мс; 0 — замера ещё не было. */
    private var latencyMs: Int = 0

    /** Порт локального SOCKS5 от olcrtc: замер для него идёт через прокси. */
    private var probeSocksPort: Int? = null

    /** Цикл замеров отклика, крутится только при живом туннеле. */
    private var probeJob: Job? = null

    /** Ссылка активного профиля — по ней состояние возвращается после проверки. */
    private var activeLink: String = ""

    /** Идёт проверка профилей: пока это так, UI не показывает состояние туннеля. */
    @Volatile
    private var pinging: Boolean = false

    /** Прогресс проверки: номер текущего профиля (с нуля) и всего в очереди. */
    private var pingIndex: Int = 0
    private var pingTotal: Int = 0
    private var pingProfileId: String = ""

    /** Результаты проверки в памяти процесса; на диск не пишутся. */
    private var pingResults: MutableMap<String, Int> = mutableMapOf()

    /** Настройки раздельного туннелирования читаются на каждый establish. */
    private val prefs by lazy { Prefs(this) }

    /** Туннель olcrtc живёт в отдельном процессе и управляется отсюда. */
    private val olcrtc by lazy { OlcrtcProcess(this) }

    /** Логи Xray-core ядро пишет само в файл, а этот класс переливает их в журнал. */
    private val xrayLog by lazy { XrayLogTailer(File(cacheDir, XRAY_LOG_NAME)) }

    @Volatile
    var state: VpnState = VpnState.DISCONNECTED
        private set

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createNotificationChannel()
        state = VpnState.DISCONNECTED
        publishState(VpnState.DISCONNECTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                startForeground(NOTIFICATION_ID, buildNotification(VpnState.DISCONNECTING))
                work?.cancel()
                work = serviceScope.launch { shutdown() }
            }

            ACTION_CONNECT -> {
                val link = intent.getStringExtra(EXTRA_LINK)
                if (link.isNullOrBlank()) {
                    Log.w(TAG, "Connect requested without a link")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification(VpnState.CONNECTING))
                activeProfileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
                publishState(VpnState.CONNECTING)
                work?.cancel()
                work = serviceScope.launch { connect(link) }
            }

            ACTION_SWITCH -> {
                val link = intent.getStringExtra(EXTRA_LINK)
                if (link.isNullOrBlank()) {
                    Log.w(TAG, "Switch requested without a link")
                    stopSelf()
                    return START_NOT_STICKY
                }
                activeProfileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
                publishState(VpnState.CONNECTING, "Переключаем профиль")
                work?.cancel()
                work = serviceScope.launch {
                    // Мягкая замена: гасим старое ядро и tun, не роняя foreground.
                    runCatching { XrayCore.stop() }
                    XrayCore.releaseDns()
                    releaseTun()
                    olcrtc.stop()
                    connect(link)
                }
            }

            ACTION_PING -> {
                val links = intent.getStringArrayExtra(EXTRA_PING_LINKS)?.toList().orEmpty()
                val ids = intent.getStringArrayExtra(EXTRA_PING_IDS)?.toList().orEmpty()
                if (links.isEmpty() || links.size != ids.size) {
                    Log.w(TAG, "Ping requested with empty or mismatched profile lists")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification(VpnState.PINGING))
                work?.cancel()
                work = serviceScope.launch { runPing(links, ids) }
            }

            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** VPN revoked from system settings or another VPN app took over. */
    override fun onRevoke() {
        serviceScope.launch { shutdown() }
        super.onRevoke()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopProbe()
        // Проверка, пережившая сервис, оставила бы UI в вечном «проверяем»:
        // цикла уже нет, а в снимке осталось PINGING.
        if (pinging) {
            pinging = false
            snapshot = VpnSnapshot.DISCONNECTED
        }
        runCatching { XrayCore.stop() }
        xrayLog.stop()
        olcrtc.stop()
        deleteXrayLogFile()
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        super.onDestroy()
    }

    private suspend fun connect(link: String) {
        // Запоминаем всегда: после проверки профилей вернём то, на чём стояли.
        activeLink = link
        if (OlcrtcProfile.isOlcrtcLink(link)) {
            connectOlcrtc(link)
            return
        }
        connectVless(link)
    }

    private fun connectVless(link: String) {
        // Наш пакет в туннеле при любом режиме, замер пойдёт прямо в TUN.
        probeSocksPort = null
        val profile = try {
            VlessProfile.parse(link)
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "Некорректная ссылка")
            return
        }

        val descriptor = try {
            establishTun()
        } catch (e: Exception) {
            fail("Не удалось создать интерфейс VPN: ${e.message}")
            return
        }

        if (descriptor == null) {
            fail("Не удалось создать интерфейс VPN")
            return
        }
        tunDescriptor = descriptor

        startCore(
            config = XrayConfigBuilder.build(profile, descriptor.fd, xrayLogLevel(), xrayLogFile()),
            description = profile.describe(),
        )
    }

    /** Запускает Xray с готовым конфигом и публикует итоговое состояние. */
    private fun startCore(config: String, description: String) {
        // Хвост прошлой сессии не должен попасть в журнал, а при выключенном
        // журнале файл вообще не нужен — иначе он лежит в кесте мёртвым грузом.
        deleteXrayLogFile()
        // Читатель логов поднимаем до старта ядра: первые строки тоже полезны.
        if (Journal.enabled) xrayLog.start()

        // Keep every socket the core opens outside of the tunnel it feeds.
        val delegate = ProtectDelegate()
        XrayCore.registerDialer(delegate)
        XrayCore.setupDns("1.1.1.1:53", delegate)

        val error = try {
            XrayCore.start(config)
        } catch (e: Throwable) {
            Log.e(TAG, "Core failed to start", e)
            e.message ?: "Ядро не запустилось"
        }

        if (error.isNotEmpty()) {
            Log.w(TAG, "Xray error: $error")
            xrayLog.stop()
            releaseTun()
            olcrtc.stop()
            fail(shorten(error))
            return
        }

        Journal.append(Journal.SOURCE_APP, "Подключено: $description")
        state = VpnState.CONNECTED
        connectedAt = System.currentTimeMillis()
        updateNotification(state)
        publishState(state, description)
        // Во время проверки профилей свой замер не нужен: он всё равно делается
        // один раз на профиль, а фоновый цикл только жёг бы трафик впустую и
        // мешал визуально — у него свой таймер, а тут и так всё подряд.
        if (!pinging) startProbe()
    }

    /**
     * Цикл замеров отклика через живой туннель.
     *
     * Первый замер идёт сразу после подключения, дальше раз в минуту: чаще
     * незачем, а запросы через туннель всё-таки видны оператору.
     *
     * Результат нигде не хранится — только в [VpnSnapshot], который и так живёт
     * в памяти процесса. В журнал и Prefs он не пишется намеренно: хронология
     * «когда подключались и с каким откликом» на устройстве ни к чему.
     */
    private fun startProbe() {
        probeJob?.cancel()
        latencyMs = 0
        val socksPort = probeSocksPort
        probeJob = serviceScope.launch {
            while (isActive) {
                latencyMs = TunnelProbe.measure(socksPort) ?: 0
                publishState(state)
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    private fun stopProbe() {
        probeJob?.cancel()
        probeJob = null
        latencyMs = 0
    }

    /**
     * Поочерёдная проверка профилей: на каждом туннель поднимается по-настоящему,
     * с настоящим ядром и настоящим выходом, поэтому проверяется не только
     * достижимость адреса, но и живость профиля — UUID, SNI, поднятие ядра.
     * Одиночный TCP-connect этого не умеет.
     *
     * Строго последовательно: так за один раз стучится ровно в один адрес, и на
     * узком канале результат не искажается собственными очередями.
     *
     * Состояние ВПН возвращается ровно к тому, чем было до старта: если были
     * подключены — встаём обратно на тот же профиль, если нет — остаёмся
     * отключёнными.
     */
    private suspend fun runPing(links: List<String>, ids: List<String>) {
        val wasConnected = state == VpnState.CONNECTED || state == VpnState.CONNECTING
        val restoreLink = activeLink
        val restoreId = activeProfileId

        pinging = true
        pingResults = mutableMapOf()
        pingIndex = 0
        pingTotal = links.size
        pingProfileId = ""

        // Гасим то, что уже поднято, до первой итерации. Иначе попытка создать
        // новый TUN поверх старого провалится, и первый профиль в списке
        // стабильно отмечался бы отказом — притом только при запуске проверки
        // с включённым ВПН. Сервис при этом остаётся жив: pinging уже выставлен.
        if (state != VpnState.DISCONNECTED) {
            shutdown()
        }

        try {
            links.forEachIndexed { index, link ->
                pingIndex = index
                pingProfileId = ids[index]
                publishState(VpnState.PINGING)

                val result = withTimeoutOrNull(PING_PROFILE_TIMEOUT_MS) {
                    connect(link)
                    if (state == VpnState.CONNECTED) TunnelProbe.measure(probeSocksPort) else null
                }
                // -1 — «не отвечает», рисуется иначе, чем хороший отклик.
                pingResults[ids[index]] = result ?: -1
                publishState(VpnState.PINGING)

                shutdown()
            }
        } finally {
            pinging = false
            pingIndex = 0
            pingTotal = 0
            pingProfileId = ""
            if (wasConnected && restoreLink.isNotBlank()) {
                activeProfileId = restoreId
                activeLink = restoreLink
                startForeground(NOTIFICATION_ID, buildNotification(VpnState.CONNECTING))
                connect(restoreLink)
            } else {
                shutdown()
                // shutdown() не публикует ничего, если туннель уже погашен, а
                // последним ушло «Проверка: 5 из 5». Без этого явного события
                // интерфейс остался бы с надписью «Проверка» навсегда.
                publishState(VpnState.DISCONNECTED)
            }
        }
    }

    /**
     * Путь к файлу логов ядра или null, если журнал выключен либо кеш недоступен.
     */
    private fun xrayLogFile(): String? {
        if (!Journal.enabled) return null
        return runCatching { File(cacheDir, XRAY_LOG_NAME).absolutePath }.getOrNull()
    }

    /** Уровень логов ядра: выключенный журнал означает полное молчание. */
    private fun xrayLogLevel(): String =
        if (Journal.enabled) XrayConfigBuilder.DEFAULT_LOG_LEVEL else XrayConfigBuilder.LOG_LEVEL_NONE

    /**
     * Убирает файл логов.
     *
     * Случай не только очевидный: выключенный журнал файл не читает, а значит и не
     * создаёт, и старый от прошлой сессии остался бы лежать в кеше навсегда.
     */
    private fun deleteXrayLogFile() {
        runCatching { File(cacheDir, XRAY_LOG_NAME).delete() }
    }

    /**
     * Подключение через olcrtc: сначала поднимается отдельный процесс с SOCKS5,
     * потом — туннель, и только затем Xray.
     */
    private suspend fun connectOlcrtc(link: String) {
        val profile = try {
            OlcrtcProfile.parse(link)
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "Некорректная ссылка")
            return
        }

        publishState(VpnState.CONNECTING, "Запускаем olcrtc…")
        val socksPort = try {
            olcrtc.start(profile)
        } catch (e: Exception) {
            Log.e(TAG, "olcrtc failed to start", e)
            fail(e.message ?: "olcrtc не запустился")
            return
        }
        Log.i(TAG, "olcrtc ready on 127.0.0.1:$socksPort")
        // Замер отклика для olcrtc пойдёт через этот SOCKS: наш пакет вне туннеля.
        probeSocksPort = socksPort

        val descriptor = try {
            establishTun(excludeSelf = true)
        } catch (e: Exception) {
            olcrtc.stop()
            fail("Не удалось создать интерфейс VPN: ${e.message}")
            return
        }

        if (descriptor == null) {
            olcrtc.stop()
            fail("Не удалось создать интерфейс VPN")
            return
        }
        tunDescriptor = descriptor

        publishState(VpnState.CONNECTING, "Поднимаем туннель…")
        startCore(
            config = XrayConfigBuilder.buildForOlcrtc(
                socksPort = socksPort,
                tunFd = descriptor.fd,
                logLevel = xrayLogLevel(),
                logFile = xrayLogFile(),
            ),
            description = profile.describe(),
        )
    }

    private fun shutdown() {
        stopProbe()
        if (state != VpnState.DISCONNECTED) {
            state = VpnState.DISCONNECTING
            updateNotification(state)
            publishState(state)

            runCatching { XrayCore.stop() }
                .onFailure { Log.w(TAG, "stop failed", it) }
            xrayLog.stop()
            XrayCore.releaseDns()
            releaseTun()
            olcrtc.stop()
            deleteXrayLogFile()

            state = VpnState.DISCONNECTED
            publishState(state)
        }
        // Между профилями в очереди сервис не гасим — это часть проверки.
        if (pinging) return
        stopForegroundCompat()
        stopSelf()
    }

    private fun fail(message: String) {
        state = VpnState.ERROR
        publishState(state, message)
        // Во время проверки профилей сервис остаётся жив: неудачный профиль не
        // должен уносить с собой всю очередь, иначе один битый сервер остановит
        // проверку всех остальных.
        if (pinging) return
        stopForegroundCompat()
        stopSelf()
    }

    /**
     * @param excludeSelf исключает пакет приложения из туннеля. Нужен для olcrtc:
     * его процесс не может вызвать `protect()`, поэтому весь наш UID выводится из
     * туннеля целиком — иначе WebRTC-сокеты уходят в подменённый маршрут. Работает
     * во всех режимах, включая ALLOW: там исключение достигается отсутствием пакета
     * в разрешённом списке, потому что Android не принимает оба списка сразу.
     */
    private fun establishTun(excludeSelf: Boolean = false): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Nodeway VPN")
            .setMtu(TUN_MTU)
            .setBlocking(false)
            .addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)
        XrayConfigBuilder.DNS_SERVERS.forEach { builder.addDnsServer(it) }

        // Раздельное туннелирование: список приложений применяется к Builder до establish.
        val tunnelMode = TunnelMode.fromCode(prefs.tunnelMode)
        builder.applySplitTunnel(
            this,
            tunnelMode,
            splitPackages(this),
            packageName,
            // olcrtc не умеет protect(), поэтому его собственный UID обязан остаться
            // вне туннеля — иначе его сокеты уйдут в подменённый маршрут, и трафик
            // встанет целиком, хотя подключение формально прошло.
            includeSelf = !excludeSelf,
        )

        // В ALLOW наш пакет и так вне туннеля — там список запрещённых не собирается.
        if (excludeSelf && (tunnelMode != TunnelMode.ALLOW)) {
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure { Log.w(TAG, "System rejected self exclusion", it) }
        }

        val configureIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.setConfigureIntent(configureIntent)

        Log.i(TAG, "Establishing tunnel")
        return builder.establish()
    }

    private fun releaseTun() {
        runCatching { tunDescriptor?.close() }
            .onFailure { Log.w(TAG, "closing tun failed", it) }
        tunDescriptor = null
    }

    private fun publishState(newState: VpnState, message: String? = null) {
        // Во время проверки профилей состояние туннеля наружу не отдаётся: внутри
        // цикла оно мельтешит «подключено/отключено» на каждом шаге, и пользователь
        // видел бы мельтешение вместо прогресса. Снаружи одно состояние.
        val shown = if (pinging) VpnState.PINGING else newState
        // Без туннеля профиль не значим — и в снимке, и в широковещании одинаково.
        val profileId =
            if (shown == VpnState.DISCONNECTED || shown == VpnState.ERROR) "" else activeProfileId
        snapshot = VpnSnapshot(
            state = shown,
            profileId = profileId,
            connectedAt = connectedAt,
            latencyMs = latencyMs,
            pingIndex = pingIndex,
            pingTotal = pingTotal,
            pingProfileId = pingProfileId,
            pingResults = HashMap(pingResults),
        )

        val intent = Intent(ACTION_STATE)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, shown.code)
            .putExtra(EXTRA_MESSAGE, message)
            .putExtra(EXTRA_PROFILE_ID, profileId)
            .putExtra(EXTRA_CONNECTED_AT, connectedAt)
            .putExtra(EXTRA_LATENCY_MS, latencyMs)
            .putExtra(EXTRA_PING_INDEX, pingIndex)
            .putExtra(EXTRA_PING_TOTAL, pingTotal)
            .putExtra(EXTRA_PING_PROFILE_ID, pingProfileId)
            .putExtra(EXTRA_PING_RESULTS, HashMap(pingResults))
        sendBroadcast(intent)
    }

    private inner class ProtectDelegate : DialerController {
        override fun protectFd(fd: Long): Boolean = protect(fd.toInt())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN-соединение",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Состояние VPN-подключения"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(currentState: VpnState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NodewayVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (currentState) {
            VpnState.CONNECTING -> getString(R.string.notification_connecting)
            VpnState.CONNECTED -> getString(R.string.notification_connected)
            VpnState.DISCONNECTING -> getString(R.string.notification_disconnecting)
            VpnState.PINGING -> getString(R.string.notification_ping)
            else -> getString(R.string.app_name)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(getString(R.string.notification_subtitle))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.action_disconnect), stopIntent)
            .build()
    }

    private fun updateNotification(currentState: VpnState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(currentState)) }
    }

    private fun stopForegroundCompat() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun shorten(message: String): String =
        if (message.length <= MAX_ERROR_LENGTH) message else message.take(MAX_ERROR_LENGTH) + "…"

    companion object {
        private const val TAG = "NodewayVpnService"

    /** Файл, куда ядро пишет логи для журнала в приложении. */
    private const val XRAY_LOG_NAME = "xray.log"

        const val ACTION_CONNECT = "com.nodewayvpn.pro.action.CONNECT"
        const val ACTION_SWITCH = "com.nodewayvpn.pro.action.SWITCH"
        const val ACTION_DISCONNECT = "com.nodewayvpn.pro.action.DISCONNECT"
        const val ACTION_STATE = "com.nodewayvpn.pro.action.STATE"
        const val ACTION_PING = "com.nodewayvpn.pro.action.PING"

        const val EXTRA_LINK = "extra_link"
        const val EXTRA_STATE = "extra_state"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_CONNECTED_AT = "extra_connected_at"

    /** Отклик через туннель в мс; 0 — замера не было или туннель не ответил. */
    const val EXTRA_LATENCY_MS = "extra_latency_ms"

    /** Поочерёдная проверка профилей: ссылки и их id в том же порядке. */
    const val EXTRA_PING_LINKS = "extra_ping_links"
    const val EXTRA_PING_IDS = "extra_ping_ids"

    /** Прогресс проверки и её результаты, чтобы Activity не отставала. */
    const val EXTRA_PING_INDEX = "extra_ping_index"
    const val EXTRA_PING_TOTAL = "extra_ping_total"
    const val EXTRA_PING_PROFILE_ID = "extra_ping_profile_id"
    const val EXTRA_PING_RESULTS = "extra_ping_results"

        private const val CHANNEL_ID = "nodeway_vpn_service"
        private const val NOTIFICATION_ID = 0x4E57
        private const val MAX_ERROR_LENGTH = 220

        private const val TUN_MTU = 1500
        private const val TUN_ADDRESS = "10.23.0.2"
        private const val TUN_PREFIX_LENGTH = 32

        /** Пауза между замерами отклика; минуты хватает с большим запасом. */
        private const val PROBE_INTERVAL_MS = 60_000L

        /**
         * Предел на один профиль при проверке.
         *
         * С запасом на DNS, TLS-рукопожатие и старт ядра, но всё же ограничение:
         * мёртвый сервер обязан отпустить очередь, иначе прогон двадцати профилей
         * растянется на четверть часа.
         */
        private const val PING_PROFILE_TIMEOUT_MS = 15_000L

        /**
         * Последнее опубликованное состояние туннеля.
         *
         * Нужно Activity, созданной заново после смахивания приложения из недавних:
         * сервис в этом процессе жив и событий больше не шлёт, поэтому без снимка
         * интерфейс показывал бы «Отключено» при работающем туннеле. Значение
         * обновляется в [publishState] вместе с широковещанием, поэтому совпадает
         * с тем, что получила бы Activity по событию.
         */
        @Volatile
        var snapshot: VpnSnapshot = VpnSnapshot.DISCONNECTED

        fun start(context: Context, link: String, profileId: String) {
            val intent = Intent(context, NodewayVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_LINK, link)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Горячая смена сервера: туннель и foreground остаются, меняется ядро. */
        fun switchTo(context: Context, link: String, profileId: String) {
            val intent = Intent(context, NodewayVpnService::class.java)
                .setAction(ACTION_SWITCH)
                .putExtra(EXTRA_LINK, link)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Поочерёдная проверка профилей.
         *
         * [ids] и [links] идут строго параллельными списками: индекс в одном
         * соответствует индексу в другом, иначе плашки разъедутся с профилями.
         */
        fun startPing(context: Context, ids: Array<String>, links: Array<String>) {
            val intent = Intent(context, NodewayVpnService::class.java)
                .setAction(ACTION_PING)
                .putExtra(EXTRA_PING_IDS, ids)
                .putExtra(EXTRA_PING_LINKS, links)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, NodewayVpnService::class.java)
                .setAction(ACTION_DISCONNECT)
            runCatching { context.startService(intent) }
        }
    }
}