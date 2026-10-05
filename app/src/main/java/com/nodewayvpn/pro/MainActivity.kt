package com.nodewayvpn.pro

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Single screen host for the WebView UI (`assets/index.html`).
 *
 * The activity is a thin shell: it renders the HTML, keeps the list of imported
 * profiles in [ProfileStore], forwards user actions to [NodewayVpnService] and
 * pushes state changes back into the page.
 */
class MainActivity : AppCompatActivity(), VpnWebBridge.Host {

    private lateinit var webView: WebView
    private lateinit var store: ProfileStore

    private val network = Executors.newSingleThreadExecutor()

    /**
     * Отдельный поток для списка приложений и иконок.
     *
     * Он не связан с сетью: тяжёлая отрисовка иконок не должна задерживать загрузку подписки.
     */
    private val appsExecutor = Executors.newSingleThreadExecutor()

    /** Пользовательские настройки: последняя ссылка и раздельное туннелирование. */
    private lateinit var prefs: Prefs

    /** Список установленных приложений, пересчитывается только после смены настроек. */
    @Volatile
    private var installedAppsCache: List<InstalledApp>? = null

    /** Высота статус-бара и нижней панели навигации в CSS-пикселях. */
    private var insetTop = 0
    private var insetBottom = 0

    /** Подписки, которые уже грузятся — чтобы не запускать загрузку дважды. */
    private val subscriptionsInFlight = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Планировщик автообновления подписок: раз в минуту смотрим, какие из них
     * д��яли интервал из директивы `#refresh`, и обновляем их.
     */
    private val refreshHandler = Handler(Looper.getMainLooper())

    private val refreshTick = object : Runnable {
        override fun run() {
            refreshDueSubscriptions()
            refreshHandler.postDelayed(this, REFRESH_CHECK_INTERVAL_MS)
        }
    }

    /** Источник журнала: строки от обоих ядер и от сервиса. */
    private val journalListener = Journal.Listener { entries -> pushJournal(entries) }

    /**
     * Записи журнала, ждущие отправки в страницу.
     *
     * Ядра пишут построчно, а каждый вызов `evaluateJavascript` — это переход в JS,
     * поэтому записи копятся и уходят пачкой.
     */
    private val journalQueue = ArrayDeque<Journal.Entry>()

    private var journalFlushScheduled = false

    private val journalFlush = Runnable {
        journalFlushScheduled = false
        val batch = synchronized(journalQueue) {
            val out = journalQueue.toList()
            journalQueue.clear()
            out
        }
        if (batch.isNotEmpty()) pushJournalEntries("onJournalEntries", batch)
    }

    /** Состояние туннеля — нужно для горячей смены профиля. */
    private var vpnState: String = VpnState.DISCONNECTED.code

    /** Ход проверки профилей: очередь, текущий и уже полученные отклики. */
    private var pingProgress: PingProgress = PingProgress()

    /** Профиль, к которому относится текущее подключение. */
    private var activeProfileId: String = ""
    private var pendingProfileId: String = ""

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val profileId = pendingProfileId
            pendingProfileId = ""
            val profile = store.profile(profileId)
            if (result.resultCode == RESULT_OK && profile != null) {
                store.selectedProfileId = profile.id
                activeProfileId = profile.id
                NodewayVpnService.start(this, profile.link, profile.id)
                pushProfiles()
            } else {
                pushState(VpnState.ERROR.code, "Не выдано разрешение на VPN")
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(NodewayVpnService.EXTRA_STATE) ?: return
            val message = intent.getStringExtra(NodewayVpnService.EXTRA_MESSAGE)
            applyVpnState(
                state = state,
                message = message,
                profileId = intent.getStringExtra(NodewayVpnService.EXTRA_PROFILE_ID).orEmpty(),
                connectedAt = intent.getLongExtra(NodewayVpnService.EXTRA_CONNECTED_AT, 0L),
                latencyMs = intent.getIntExtra(NodewayVpnService.EXTRA_LATENCY_MS, 0),
                ping = pingFrom(intent),
            )
        }
    }

    /**
     * Применяет состояние туннеля ко всем полям и отдаёт его странице и журналу.
     *
     * Путь общий для события от сервиса и для снимка при пересоздании Activity,
     * иначе после смахивания из недавних восстановилось бы не всё.
     */
    private fun applyVpnState(
        state: String,
        message: String?,
        profileId: String,
        connectedAt: Long,
        latencyMs: Int = 0,
        ping: PingProgress = PingProgress(),
    ) {
        // Активный профиль приходит из сервиса: при перезапуске он иначе терялся.
        activeProfileId = profileId
        vpnState = state
        store.connectedAt = connectedAt
        pingProgress = ping
        // Во время проверки профилей список не перерисовываем. Событие приходит
        // дважды на профиль, и каждая перерисовка создаёт строки заново вместе с
        // «галочкой» выбранного — тот мигал бы на глазах. Данные профилей при
        // проверке не меняются, меняются только плашки, а они обновляются точечно.
        if (state != VpnState.PINGING.code) {
            pushProfiles()
        }
        pushState(state, message, latencyMs)
        // Состояние пишем в журнал здесь, а не в странице: записи переживают
        // перезагрузку WebView и попадают в общий буфер с логами ядер.
        if (message != null) {
            Journal.append(
                Journal.SOURCE_APP,
                message,
                if (state == VpnState.ERROR.code) Journal.LEVEL_ERROR else null,
            )
        }
    }

    /**
     * Забирает состояние у сервиса, если он пережил эту Activity.
     *
     * Смахивание из недавних убивает Activity, но не процесс с foreground-сервисом,
     * так что туннель продолжает работать и молчит — без этого вызова новая
     * Activity до первого события показывала бы «Отключено».
     */
    private fun restoreVpnStateFromService() {
        val snapshot = NodewayVpnService.snapshot
        // Сервис не поднимался: менять нечего, иначе затёрли бы время подключения.
        if (snapshot.state == VpnState.DISCONNECTED && store.connectedAt <= 0L) return
        applyVpnState(
            state = snapshot.state.code,
            // Сообщение не восстанавливаем: оно уже было записано в журнал.
            message = null,
            profileId = snapshot.profileId,
            connectedAt = snapshot.connectedAt,
            latencyMs = snapshot.latencyMs,
            ping = PingProgress(snapshot.pingIndex, snapshot.pingTotal, snapshot.pingProfileId, snapshot.pingResults),
        )
    }

    /** Разбирает прогресс проверки из широковещания сервиса. */
    private fun pingFrom(intent: Intent) = PingProgress(
        index = intent.getIntExtra(NodewayVpnService.EXTRA_PING_INDEX, 0),
        total = intent.getIntExtra(NodewayVpnService.EXTRA_PING_TOTAL, 0),
        profileId = intent.getStringExtra(NodewayVpnService.EXTRA_PING_PROFILE_ID).orEmpty(),
        results = @Suppress("UNCHECKED_CAST")
        (intent.getSerializableExtra(NodewayVpnService.EXTRA_PING_RESULTS) as? HashMap<String, Int>)
            ?.toMap() ?: emptyMap(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        store = ProfileStore(this)
        prefs = Prefs(this)
        // Вид панелей — сразу по теме приложения: enableEdgeToEdge() смотрел на
        // системную, а при выбранной теме они расходятся.
        applySystemBars(pageTheme() == "dark")
        // Настройка журнала применяется раньше любой записи в него.
        Journal.enabled = prefs.journalEnabled
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            // WebView растянут под системные панели, а отступы сообщаются странице
            // через `setInsets`: в WebView `env(safe-area-inset-*)` пустой.
            fitsSystemWindows = false

            setBackgroundColor(BACKGROUND_COLOR)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                disableForceDark(this)
            }
            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    // Первая отрисовка может обогнать применение инсетов.
                    pushInsets()
                    // Режим оформления уже задан адресом страницы, а сделанный
                    // выбор подписан в строке настроек — его страница узнаёт отсюда.
                    pushTheme(pageTheme(), prefs.themeMode)
                    // Страница перерисована — отдаём ей журнал целиком, без дублей.
                    startJournal()
                }

                /**
                 * Иконки приложений отдаём сами, из PackageManager.
                 *
                 * Перехват вместо моста с base64: браузер сам решает, что грузить и
                 * когда, поэтому перерисовка списка не ждёт ответа из JS, а иконка
                 * не едет туда-обратно строкой.
                 */
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? =
                    AppIcons.packageOf(request.url.toString())
                        ?.let { AppIcons.respond(this@MainActivity, it) }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d(TAG, "web: ${message.message()}")
                    return true
                }
            }
            addJavascriptInterface(VpnWebBridge(this@MainActivity), BRIDGE_NAME)
        }
        setContentView(webView)

        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            insetTop = bars.top
            insetBottom = bars.bottom
            pushInsets()
            insets
        }

        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(NodewayVpnService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Сверяемся с сервисом до загрузки страницы: он переживает смахивание
        // из недавних, а событий больше не присылает.
        restoreVpnStateFromService()

        // Deeplink разбираем до загрузки страницы: при первом открытии она читает
        // снимок состояния, и подписка попадёт в него уже добавленной.
        // Саму ссылку стираем: иначе следующий запуск процеса добавил бы её снова.
        handleImportLink(intent)
        intent.data = null

        webView.loadUrl("file:///android_asset/index.html?theme=${pageTheme()}")
        // Список приложений считается сразу, чтобы лист выбора открывался мгновенно.
        warmInstalledAppsCache()
        requestNotificationPermissionIfNeeded()
    }

    /**
     * Режим оформления страницы: светлый или тёмный.
     *
     * Приходит адресом, а не вызовом после загрузки: иначе на тёмной теме
     * система успела бы показать светлый фон. Позже, при смене темы системы,
     * режим догоняется через setUiMode — активность не пересоздаётся, потому
     * что uiMode помечен в манифесте как configChanges.
     */
    private fun pageTheme(): String {
        // Явный выбор пользователя перекрывает системный, иначе берём системный.
        when (prefs.themeMode) {
            "light" -> return "light"
            "dark" -> return "dark"
        }
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    }

    /**
     * Вид системных панелей — по теме приложения, а не по системной.
     *
     * `enableEdgeToEdge()` выбирает его один раз, по теме системы, и активность при
     * её смене не пересоздаётся. Поэтому при выборе темы в приложении вид панелей
     * надо обновлять самому: иначе на светлой странице в тёмной системе остаются
     * белые иконки статус-бара, а на тёмной — тёмные, и полосы не читаются.
     */
    private fun applySystemBars(dark: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    /**
     * Выключает принудительную тёмную тему WebView: оформление задаёт приложение.
     *
     * Иначе на Android 10 — и при включённой в настройках разработчика
     * «Принудительной тёмной теме» — WebView перекрашивает страницу сам,
     * разворачивая цвета. Страница не описывает `prefers-color-scheme`: режим
     * приходит атрибутом `data-theme`, — и для WebView она выглядит страницей
     * без поддержки тёмной темы, а значит кандидатом на перекрашивание.
     */
    // setForceDark помечен устаревшим, но на WebView от Android 10 это единственный
    // доступный способ; на новых WebView его заменяет algorithmic darkening.
    @Suppress("DEPRECATION")
    private fun disableForceDark(settings: WebSettings) {
        /*
         * Платформенный вызов идёт первым и без проверки версии WebView.
         *
         * На Android 10 со старым WebView проверки WebViewFeature не проходят:
         * algorithmic darkening появился только в WebView 105, а FORCE_DARK на
         * приложениях с targetSdk от 33 WebView игнорирует. Остаётся платформенное
         * свойство — единственное, что ещё действует там, где всё остальное нет.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.forceDark = WebSettings.FORCE_DARK_OFF
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        }
        /*
         * Стратегия запрещает перекрашивание целиком: WebView берёт тему у самой
         * страницы и рисует ровно то, что она выдала, даже если попросили тёмную,
         * а страница ответила светлой. Именно этот случай и возникает при выборе
         * светлой темы в приложении при тёмной системной.
         */
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(
                settings,
                WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY,
            )
        }
    }

    /**
     * Применяет выбор темы из настроек.
     *
     * Странице уходят два значения: какой режим действует сейчас (setUiMode)
     * и какой выбран (onThemeMode — им подписан пункт настроек). При SYSTEM
     * действующий меняется сам вслед за системой, поэтому его и спрашиваем заново.
     */
    override fun onSetThemeModeRequested(mode: String) {
        val applied = when (mode) {
            "light", "dark" -> {
                prefs.themeMode = mode
                mode
            }
            else -> {
                prefs.themeMode = "system"
                pageTheme()
            }
        }
        pushTheme(applied, prefs.themeMode)
    }

    /** Сообщает странице и режим оформления, и сделанный выбор. */
    private fun pushTheme(applied: String, chosen: String) {
        val script = "window.setUiMode && window.setUiMode('$applied');" +
                "window.onThemeMode && window.onThemeMode('$chosen');"
        // Выбор приходит из JS-моста, а тот зовётся в отдельном потоке WebView:
        // evaluateJavascript оттуда не работает, и без переноса вызов просто теряется.
        runOnUiThread {
            applySystemBars(applied == "dark")
            runCatching { webView.evaluateJavascript(script, null) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Активность выживает при смене темы, значит сообщить о ней должна страница.
        // При явном выборе темы это пустая перерисовка: так система не может
        // переопределить то, что выбрал пользователь.
        pushTheme(pageTheme(), prefs.themeMode)
    }

    /**
     * Ссылка приходит в уже открытое приложение: Activity в singleTop, а без
     * этого переопределения вторая ссылка просто потерялась бы.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportLink(intent)
        intent.data = null
    }

    override fun onStart() {
        super.onStart()
        // Страница к этому моменту могла ещё не загрузиться, но на повторном
        // показе состояние сверяется снова — на случай смены между onStart и onResume.
        restoreVpnStateFromService()
        refreshHandler.postDelayed(refreshTick, REFRESH_CHECK_INTERVAL_MS)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onStop() {
        refreshHandler.removeCallbacks(refreshTick)
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        Journal.unsubscribe(journalListener)
        refreshHandler.removeCallbacks(journalFlush)
        webView.removeJavascriptInterface(BRIDGE_NAME)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        network.shutdownNow()
        appsExecutor.shutdownNow()
        super.onDestroy()
    }

    // region VpnWebBridge.Host — invoked from the WebView JavaScript thread

    override fun onConnectRequested(profileId: String) {
        runOnUiThread { handleConnectRequest(profileId) }
    }

    override fun onDisconnectRequested() {
        runOnUiThread {
            NodewayVpnService.stop(this)
            pushState(VpnState.DISCONNECTING.code, null)
        }
    }

    override fun onPingRequested(profileIds: List<String>) {
        runOnUiThread { handlePingRequest(profileIds) }
    }

    /**
     * Стартует проверку перечисленных профилей.
     *
     * Ссылки и id собираются здесь, в Activity: страница о них ничего не знает,
     * а сервис получает готовые параллельные списки. Состояние туннеля на момент
     * нажатия запоминает уже сам сервис и возвращает как было.
     */
    private fun handlePingRequest(profileIds: List<String>) {
        if (vpnState == VpnState.PINGING.code) return
        val pairs = profileIds.mapNotNull { id -> store.profile(id)?.let { id to it.link } }
        if (pairs.isEmpty()) {
            pushState(VpnState.ERROR.code, "Некого проверять")
            return
        }
        Log.i(TAG, "Ping requested for ${pairs.size} profiles")
        NodewayVpnService.startPing(
            this,
            ids = pairs.map { it.first }.toTypedArray(),
            links = pairs.map { it.second }.toTypedArray(),
        )
    }

    override fun onSelectProfileRequested(id: String) {
        runOnUiThread {
            store.selectedProfileId = id
            pushProfiles()
            // Горячая смена: если туннель уже поднят, сразу переключаемся на новый сервер.
            if (vpnState == VpnState.CONNECTED.code || vpnState == VpnState.CONNECTING.code) {
                store.profile(id)?.let { NodewayVpnService.switchTo(this, it.link, it.id) }
            }
        }
    }

    override fun onImportClipboardRequested(): String {
        val text = readClipboard()
            ?: return result(ok = false, message = "В буфере обмена нет текста")

        // Одна строка http(s) — это подписка, всё остальное — список конфигураций.
        val subscriptionUrl = text.trim().lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        if (subscriptionUrl != null) {
            val source = store.addSubscription(SubscriptionSource.create(subscriptionUrl))
            pushProfiles()
            loadSubscription(source, isNew = true)
            return result(ok = true, message = "Загружаем подписку: ${source.url}")
        }

        val parsed = LinkListParser.parse(text)
        val supported = parsed.supported
        if (supported.isEmpty()) {
            val hint = if (parsed.unsupported.isNotEmpty()) {
                "В буфере только неподдерживаемые ссылки: " +
                    parsed.unsupported.map { it.scheme }.distinct().joinToString()
            } else {
                "В буфере не найдено ни одной vless:// или olcrtc:// ссылки"
            }
            return result(ok = false, message = hint)
        }

        val added = store.addProfiles(
            links = supported.map { it.raw },
            names = supported.associate { it.raw to remarkOf(it.raw, parsed) },
            source = ServerProfile.Source.CLIPBOARD,
            subscriptionId = null,
        )
        pushProfiles()

        val message = when {
            added == 0 -> "Такие ссылки уже были добавлены"
            added < supported.size -> "Добавлено $added из ${supported.size} (остальные уже есть)"
            else -> "Добавлено профилей: $added"
        }
        return result(
            ok = true,
            added = added,
            total = supported.size,
            unsupported = parsed.unsupported.size,
            message = message,
        )
    }


    override fun onRefreshSubscriptionRequested(id: String) {
        val source = store.subscription(id) ?: return
        loadSubscription(source, isNew = false)
    }

    override fun onRemoveSubscriptionRequested(id: String) {
        runOnUiThread {
            store.removeSubscription(id)
            pushProfiles()
        }
    }

    override fun onRemoveProfileRequested(id: String) {
        runOnUiThread {
            store.removeProfile(id)
            pushProfiles()
        }
    }

    override fun coreVersion(): String = runCatching { XrayCore.version() }.getOrDefault("")

    override fun journalEnabled(): Boolean = Journal.enabled

    override fun onSetJournalEnabledRequested(enabled: Boolean): String {
        // Настройка нужна сервису при запуске ядра, а он может быть уже запущен:
        // переключатель применяется к новым подключениям, текущий журнал — сразу.
        prefs.journalEnabled = enabled
        Journal.applyEnabled(enabled)
        pushJournalEnabled()
        return JSONObject().apply { put("enabled", Journal.enabled) }.toString()
    }

    override fun appVersion(): String = BuildConfig.VERSION_NAME

    override fun uiState(): String = store.toUiModel(activeProfileId)
        // Состояние туннеля обязано быть в стартовом снимке: события от сервиса
        // приходят только при сменах, а страница переживает перезапуск WebView.
        // Отклик берём из того же снимка — замер может прийти уже после загрузки.
        .put("vpnState", vpnState)
        .put("latency", NodewayVpnService.snapshot.latencyMs)
        .put("ping", pingProgress.toJson())
        .toString()

    /** Готовит прогресс проверки для страницы. */
    private fun PingProgress.toJson(): JSONObject = JSONObject().apply {
        put("running", running)
        put("index", index)
        put("total", total)
        put("profileId", profileId)
        put("results", JSONObject(results as Map<*, *>))
    }

    override fun onGetSplitTunnelSettings(): String {
        // Режим без единого приложения ничего не делает, поэтому приводим к общему.
        if (TunnelMode.fromCode(prefs.tunnelMode) != TunnelMode.ALL && prefs.splitPackages.isEmpty()) {
            prefs.tunnelMode = TunnelMode.ALL.code
        }
        return JSONObject().apply {
            put("mode", prefs.tunnelMode)
            put("packages", JSONArray(prefs.splitPackages.toList()))
            put("selfPackage", packageName)
        }.toString()
    }

    override fun onSetSplitTunnelModeRequested(mode: String): String {
        val next = TunnelMode.fromCode(mode.trim())
        prefs.tunnelMode = next.code
        Log.i(TAG, "Split tunneling mode: ${next.code}")
        restartTunnelWithSplitSettings()
        return splitResult(ok = true)
    }

    override fun onSetPackageSelectedRequested(packageName: String, selected: Boolean): String {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return splitResult(ok = false, error = "Пустое имя пакета")

        val current = LinkedHashSet(prefs.splitPackages)
        val changed = if (selected) current.add(pkg) else current.remove(pkg)
        if (!changed) return splitResult(ok = true)

        prefs.splitPackages = current
        installedAppsCache = null
        Log.i(TAG, "Split tunneling package ${if (selected) "added" else "removed"}: $pkg")
        restartTunnelWithSplitSettings()
        return splitResult(ok = true)
    }

    override fun onSetSelectedPackagesRequested(packagesJson: String): String {
        val array = runCatching { JSONArray(packagesJson) }.getOrNull()
            ?: return splitResult(ok = false, error = "Не удалось разобрать список")

        val packages = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val pkg = array.optString(index).trim()
            if (pkg.isNotEmpty()) packages.add(pkg)
        }
        if (packages == prefs.splitPackages) return splitResult(ok = true)

        prefs.splitPackages = packages
        installedAppsCache = null
        Log.i(TAG, "Split tunneling packages saved: ${packages.size}")
        restartTunnelWithSplitSettings()
        return splitResult(ok = true)
    }

    override fun onListInstalledAppsRequested(includeSystem: Boolean, query: String): String =
        installedAppsJson(includeSystem, query)

    /**
     * Список приложений, отобранный по фильтру и поиску.
     *
     * Отдельная функция нужна и синхронному, и асинхронному пути.
     */
    private fun installedAppsJson(includeSystem: Boolean, query: String): String {
        val selected = prefs.splitPackages
        val needle = query.trim().lowercase()
        val array = JSONArray()
        installedApps().asSequence()
            .filter { includeSystem || !it.system }
            .filter {
                needle.isEmpty() || it.label.lowercase().contains(needle) ||
                    it.pkg.lowercase().contains(needle)
            }
            // Выбранные приложения показываем первыми, остальные - по алфавиту.
            .sortedWith(
                compareByDescending<InstalledApp> { it.pkg in selected }
                    .thenBy { it.label.lowercase() },
            )
            .forEach { app ->
                array.put(
                    JSONObject().apply {
                        put("pkg", app.pkg)
                        put("label", app.label)
                        put("system", app.system)
                        put("selected", app.pkg in selected)
                    },
                )
            }
        return array.toString()
    }

    

    /**
     * Асинхронные варианты для страницы списка приложений.
     *
     * Синхронный мост выполняется на JS-потоке WebView и на слабых устройствах
     * считает сотни пакетов секунду-две. Всё это время браузер не может
     * отрисовать индикатор загрузки, поэтому тяжёлое уходит в отдельный поток,
     * а ответ приходит вызовом из Kotlin.
     */
    override fun onListInstalledAppsAsyncRequested(
        token: String,
        includeSystem: Boolean,
        query: String,
    ) {
        appsExecutor.execute {
            val payload = installedAppsJson(includeSystem, query)
            runOnUiThread { pushSplitApps(token, payload) }
        }
    }

    /** Отдаёт список приложений странице: `window.onSplitApps(token, json)`. */
    private fun pushSplitApps(token: String, payload: String) {
        val script = "window.onSplitApps && window.onSplitApps('${escape(token)}', '${escape(payload)}');"
        runOnUiThread { runCatching { webView.evaluateJavascript(script, null) } }
    }

    

    /**
     * Прогревает кэш списка приложений заранее.
     *
     * Первое обращение к packageManager занимает на медленных устройствах секунды,
     * а открыть лист выбора можно сразу после запуска приложения.
     */
    private fun warmInstalledAppsCache() {
        appsExecutor.execute { installedApps() }
    }

    // endregion

    // region раздельное туннелирование

    /** Приложение в том виде, в котором оно показано в настройках туннелирования. */
    private data class InstalledApp(val pkg: String, val label: String, val system: Boolean)

    /** Установленные приложения. Список кэшируется: он дорогой и меняется редко. */
    private fun installedApps(): List<InstalledApp> {
        installedAppsCache?.let { return it }
        val pm = packageManager
        val apps = runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA).map { info ->
                InstalledApp(
                    pkg = info.packageName,
                    label = runCatching { info.loadLabel(pm).toString() }
                        .getOrDefault(info.packageName),
                    system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
        }.getOrDefault(emptyList())
        installedAppsCache = apps
        return apps
    }

    

    /** Пересоздаёт туннель с теми же настройками, если VPN уже поднят. */
    private fun restartTunnelWithSplitSettings() = runOnUiThread {
        if (vpnState != VpnState.CONNECTED.code && vpnState != VpnState.CONNECTING.code) {
            return@runOnUiThread
        }
        val profile = store.profile(activeProfileId) ?: return@runOnUiThread
        Log.i(TAG, "Recreating tunnel with split tunneling settings")
        NodewayVpnService.switchTo(this, profile.link, profile.id)
    }

    /** Ответ на изменение настроек раздельного туннелирования. */
    private fun splitResult(ok: Boolean, error: String = ""): String = JSONObject().apply {
        put("ok", ok)
        put("error", error)
    }.toString()

    // endregion

    // region deeplink

    /**
     * Ссылка импорта `nodeway://import#<адрес подписки>`: base64url или как есть.
     *
     * Ссылка приходит извне — из страницы, QR-кода или мессенджера, — поэтому
     * добавление здесь молчаливое и максимально скупое: подписка не подключается,
     * не трогает маршрут и видна в списке своим адресом. Одиночные ссылки из веба
     * не берём: такая строка выглядит в списке неотличимо от настоящей.
     *
     * Вызывается и из [onCreate] (до загрузки страницы, чтобы подписка попала в
     * снимок состояния), и из [onNewIntent], когда приложение уже открыто.
     */
    private fun handleImportLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.scheme.equals(DEEPLINK_SCHEME, ignoreCase = true)) return
        if (!data.host.equals(DEEPLINK_HOST, ignoreCase = true)) return

        // Всё после первого '#' — это фрагмент, поэтому «?» и «#» внутри самой
        // ссылки остаются целыми: подписочный URL с токеном в query переживает
        // ссылку без единого percent-энкодинга.
        val payload = data.fragment?.trim().orEmpty()
        if (payload.isEmpty()) {
            Journal.append("app", "deeplink: ссылка без адреса подписки", level = "warn")
            return
        }
        if (payload.length > MAX_DEEPLINK_PAYLOAD) {
            // Intent ходит через Binder: слишком длинный фрагмент уронил бы не нас,
            // а того, кто ссылку открыл. Обрезаем молча, журналом.
            Journal.append("app", "deeplink: адрес длиннее $MAX_DEEPLINK_PAYLOAD символов", level = "warn")
            return
        }

        val url = LinkListParser.unwrapBase64(payload)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // Одиночные ссылки из веба не берём. Подписка тоже добавляет строку в
            // список, но её URL виден и удаляется одним тапом, а профиль из чужой
            // страницы выглядит так же, как настоящий, пока не проверишь отклик.
            Journal.append("app", "deeplink: это не адрес подписки — «${url.take(60)}»", level = "warn")
            return
        }

        if (store.subscriptions.any { it.url == url }) {
            Journal.append("app", "deeplink: подписка уже в списке — $url")
            return
        }

        val source = store.addSubscription(SubscriptionSource.create(url))
        Journal.append("app", "deeplink: подписка добавлена — $url")
        pushProfiles()
        loadSubscription(source, isNew = true)
    }

    // endregion

    // region import

    private fun readClipboard(): String? {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    }

    /** Загружает подписку в фоне и отдаёт результат в `window.onImportResult`. */
    private fun loadSubscription(source: SubscriptionSource, isNew: Boolean) {
        subscriptionsInFlight.add(source.id)
        // Пока грузим — кнопка «Обновить» у этой подписки показывает спиннер.
        store.loadingSubscriptions.add(source.id)
        pushProfiles()
        network.execute {
            val payload: String = runCatching { SubscriptionLoader.fetch(source.url) }
                .fold(
                    onSuccess = { loaded -> applySubscription(source, loaded, isNew) },
                    onFailure = { error ->
                        result(
                            ok = false,
                            message = "Не удалось загрузить подписку: ${error.message ?: "ошибка сети"}",
                        )
                    },
                )
            subscriptionsInFlight.remove(source.id)
            store.loadingSubscriptions.remove(source.id)
            runOnUiThread {
                pushProfiles()
                pushImportResult(payload)
            }
        }
    }

    private fun applySubscription(
        source: SubscriptionSource,
        loaded: SubscriptionLoader.Loaded,
        isNew: Boolean,
    ): String {
        val parsed = loaded.parsed
        val supported = parsed.supported
        if (supported.isEmpty()) {
            return result(
                ok = false,
                message = "В подписке нет vless:// или olcrtc:// ссылок",
                title = parsed.title,
            )
        }

        val profiles = supported.map { link ->
            ServerProfile.create(
                link = link.raw,
                name = remarkOf(link.raw, parsed),
                source = ServerProfile.Source.SUBSCRIPTION,
                subscriptionId = source.id,
            )
        }
        // Профили подписки пересоздаются с новыми id, поэтому выделение восстанавливаем
        // по той же ссылке или названию — иначе автообновление сбрасывало бы выбор.
        val selectedBefore = store.profile(store.selectedProfileId)
        store.replaceSubscriptionProfiles(source.id, profiles)
        if (selectedBefore != null && store.profile(selectedBefore.id) == null) {
            profiles.firstOrNull { it.link == selectedBefore.link || it.name == selectedBefore.name }
                ?.let { store.selectedProfileId = it.id }
        }

        val title = parsed.title.ifBlank { source.name.ifBlank { "Подписка" } }
        store.updateSubscription(
            source.copy(
                name = title,
                refreshMinutes = parsed.refreshMinutes,
                supportUrl = parsed.supportUrl,
                updatedAt = System.currentTimeMillis(),
            )
        )
        pushProfiles()

        val skipped = parsed.unsupported.size
        val message = buildString {
            append(if (isNew) "Подписка «$title» загружена" else "Подписка обновлена")
            append(": профилей ${profiles.size}")
            if (skipped > 0) append(", пропущено неподдерживаемых: $skipped")
        }
        return result(
            ok = true,
            added = profiles.size,
            total = profiles.size,
            unsupported = skipped,
            title = title,
            message = message,
        )
    }

    /**
     * Имя сервера для карточки.
     *
     * Для olcrtc это MIMO-комментарий из ссылки, но если его нет, берётся
     * локальное поле `##name:` из подписки. Для VLESS — remark или адрес.
     */
    private fun remarkOf(link: String, parsed: LinkListParser.Result? = null): String {
        if (OlcrtcProfile.isOlcrtcLink(link)) {
            val fromLink = runCatching { OlcrtcProfile.parse(link).remark }.getOrDefault("")
            if (fromLink.isNotBlank()) return fromLink
            val fromSubscription = parsed?.serverField(link, "name").orEmpty()
            if (fromSubscription.isNotBlank()) return fromSubscription
            return runCatching { OlcrtcProfile.parse(link).roomLabel }.getOrDefault("olcrtc")
        }
        return runCatching {
            val parsedLink = VlessProfile.parse(link)
            parsedLink.remark.ifBlank { parsedLink.address }
        }.getOrDefault("")
    }

    // region автообновление подписок

    /** Обновляет подписки, у которых истёк интервал из `#refresh`. */
    private fun refreshDueSubscriptions() {
        val now = System.currentTimeMillis()
        store.subscriptions.forEach { source ->
            if (source.refreshMinutes <= 0) return@forEach

            val dueAt = if (source.updatedAt > 0L) {
                source.updatedAt + maxOf(source.refreshMillis, MIN_REFRESH_INTERVAL_MS)
            } else {
                // Ещё ни разу не обновлялись — обновляем сразу, чтобы список был полным.
                now
            }
            if (now >= dueAt && subscriptionsInFlight.add(source.id)) {
                loadSubscription(source, isNew = false)
            }
        }
    }

    // endregion

    private fun result(
        ok: Boolean,
        message: String,
        added: Int = 0,
        total: Int = 0,
        unsupported: Int = 0,
        title: String = "",
    ): String = JSONObject().apply {
        put("ok", ok)
        put("added", added)
        put("total", total)
        put("unsupported", unsupported)
        put("title", title)
        put("message", message)
    }.toString()

    // endregion

    private fun handleConnectRequest(profileId: String) {
        val profile = store.profile(profileId)
        if (profile == null) {
            pushState(VpnState.ERROR.code, "Профиль не найден")
            return
        }
        if (!VlessProfile.isVlessLink(profile.link) && !OlcrtcProfile.isOlcrtcLink(profile.link)) {
            pushState(VpnState.ERROR.code, "Ссылка должна начинаться с vless:// или olcrtc://")
            return
        }

        pushState(VpnState.CONNECTING.code, "Проверяем разрешения…")

        val consentIntent = runCatching { VpnService.prepare(this) }.getOrNull()
        if (consentIntent != null) {
            pendingProfileId = profileId
            vpnPermissionLauncher.launch(consentIntent)
        } else {
            store.selectedProfileId = profile.id
            activeProfileId = profile.id
            NodewayVpnService.start(this, profile.link, profile.id)
            pushProfiles()
        }
    }

    /** Передаёт странице высоту системных панелей, чтобы контент не уходил под них. */
    private fun pushInsets() {
        // WindowInsets приходят в физических пикселях, а CSS ждёт CSS-пиксели.
        val density = resources.displayMetrics.density
        val top = (insetTop / density).roundToInt()
        val bottom = (insetBottom / density).roundToInt()
        webView.evaluateJavascript("window.setInsets && window.setInsets($top, $bottom);", null)
    }

    /** Serialises a state update into a single JS call. */
    private fun pushState(state: String, message: String?, latencyMs: Int = 0) {
        val payload = JSONObject().apply {
            put("state", state)
            put("message", message ?: "")
            put("latency", latencyMs)
            put("ping", pingProgress.toJson())
        }.toString().replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")

        runOnUiThread {
            webView.evaluateJavascript("window.onVpnState && window.onVpnState('$payload');", null)
        }
    }

    /** Актуализирует список профилей в UI. */
    private fun pushProfiles() {
        val payload = escape(store.toUiModel(activeProfileId).toString())
        runOnUiThread {
            webView.evaluateJavascript("window.onProfilesChanged && window.onProfilesChanged('$payload');", null)
        }
    }

    /** Сообщает странице, видна ли карточка журнала. */
    private fun pushJournalEnabled() {
        val enabled = if (Journal.enabled) "true" else "false"
        runOnUiThread {
            runCatching {
                webView.evaluateJavascript("window.onJournalEnabled && window.onJournalEnabled($enabled);", null)
            }
        }
    }

    /** Результат импорта или загрузки подписки. */
    private fun pushImportResult(payload: String) {
        val escaped = escape(payload)
        runOnUiThread {
            webView.evaluateJavascript("window.onImportResult && window.onImportResult('$escaped');", null)
        }
    }

    /**
     * Подписывает страницу на журнал и отдаёт накопленное.
     *
     * Вызывается на каждой загрузке страницы: [Journal.subscribe] отдаёт копию
     * буфера, а страница рисует его с нуля, поэтому перезагрузка не даёт дублей.
     */
    private fun startJournal() {
        Journal.unsubscribe(journalListener)
        // Всё накопленное уходит снимком — очередь до этого сбросится вместе со страницей.
        synchronized(journalQueue) { journalQueue.clear() }
        pushJournalEntries("onJournalReset", Journal.subscribe(journalListener))
    }

    /** Копит записи и просит отправить их пачкой. */
    private fun pushJournal(entries: List<Journal.Entry>) {
        synchronized(journalQueue) {
            journalQueue.addAll(entries)
            // Под виной пакетов буфер всё равно конечен: отдаём свежее.
            while (journalQueue.size > MAX_PENDING_JOURNAL) journalQueue.removeFirst()
        }
        if (journalFlushScheduled) return
        journalFlushScheduled = true
        refreshHandler.postDelayed(journalFlush, JOURNAL_FLUSH_MS)
    }

    /** Отправляет записи в страницу одним вызовом. */
    private fun pushJournalEntries(handler: String, entries: List<Journal.Entry>) {
        if (entries.isEmpty()) return
        val payload = escape(
            JSONArray().apply {
                entries.forEach { entry ->
                    put(JSONObject().apply {
                        put("t", entry.at)
                        put("s", entry.source)
                        put("l", entry.level)
                        put("m", entry.text)
                    })
                }
            }.toString(),
        )
        runOnUiThread {
            runCatching {
                webView.evaluateJavascript("window.$handler && window.$handler('$payload');", null)
            }
        }
    }

    private fun escape(json: String): String =
        json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ")

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val TAG = "MainActivity"
        const val BRIDGE_NAME = "NodewayVpn"
        const val BACKGROUND_COLOR = 0xFF0B0F14.toInt()

        

        /** Как часто проверяем, не пора ли обновить подписку. */
        const val REFRESH_CHECK_INTERVAL_MS = 60_000L

        /** Пауза перед отправкой накопленных записей журнала в страницу. */
        const val JOURNAL_FLUSH_MS = 150L

        /** Сколько записей ждут отправки, прежде чем самые старые будут отброшены. */
        const val MAX_PENDING_JOURNAL = 200

        /** Ниже этого интервала не обновляем, даже если сервер просит `#refresh: 1m`. */
        const val MIN_REFRESH_INTERVAL_MS = 15 * 60_000L

        /** Ссылка импорта: `nodeway://import#<адрес подписки>`. */
        const val DEEPLINK_SCHEME = "nodeway"
        const val DEEPLINK_HOST = "import"

        /** Предел полезной нагрузки deeplink в символах, с запасом на URL. */
        const val MAX_DEEPLINK_PAYLOAD = 4096
    }
}