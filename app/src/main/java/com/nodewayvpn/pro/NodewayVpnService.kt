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
import kotlinx.coroutines.launch
import libXray.DialerController

/**
 * Foreground [VpnService] that owns the embedded Xray-core instance.
 *
 * Flow: the WebView UI asks the activity to start the service with a `vless://` link,
 * the service establishes the TUN interface, then hands the descriptor to Xray-core
 * through the `env.xray.tun.fd` entry of the generated configuration.
 */
class NodewayVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    private var tunDescriptor: ParcelFileDescriptor? = null

    /** Профиль текущего подключения — попадает в широковещание состояния. */
    private var activeProfileId: String = ""

    /** Момент поднятия туннеля (мс) — по нему UI считает время подключения. */
    private var connectedAt: Long = 0L

    /** Настройки раздельного туннелирования читаются на каждый establish. */
    private val prefs by lazy { Prefs(this) }

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
                    connect(link)
                }
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
        runCatching { XrayCore.stop() }
        runCatching { tunDescriptor?.close() }
        tunDescriptor = null
        super.onDestroy()
    }

    private suspend fun connect(link: String) {
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

        val config = XrayConfigBuilder.build(profile, descriptor.fd)

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
            releaseTun()
            fail(shorten(error))
            return
        }

        state = VpnState.CONNECTED
        connectedAt = System.currentTimeMillis()
        updateNotification(state)
        publishState(state, profile.describe())
    }

    private fun shutdown() {
        if (state != VpnState.DISCONNECTED) {
            state = VpnState.DISCONNECTING
            updateNotification(state)
            publishState(state)

            runCatching { XrayCore.stop() }
                .onFailure { Log.w(TAG, "stop failed", it) }
            XrayCore.releaseDns()
            releaseTun()

            state = VpnState.DISCONNECTED
            publishState(state)
        }
        stopForegroundCompat()
        stopSelf()
    }

    private fun fail(message: String) {
        state = VpnState.ERROR
        publishState(state, message)
        stopForegroundCompat()
        stopSelf()
    }

    private fun establishTun(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Nodeway VPN")
            .setMtu(TUN_MTU)
            .setBlocking(false)
            .addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)
        XrayConfigBuilder.DNS_SERVERS.forEach { builder.addDnsServer(it) }

        // Раздельное туннелирование: список приложений применяется к Builder до establish.
        val tunnelMode = TunnelMode.fromCode(prefs.tunnelMode)
        builder.applySplitTunnel(this, tunnelMode, splitPackages(this), packageName)

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
        val intent = Intent(ACTION_STATE)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, newState.code)
            .putExtra(EXTRA_MESSAGE, message)
            .putExtra(
                EXTRA_PROFILE_ID,
                if (newState == VpnState.DISCONNECTED || newState == VpnState.ERROR) "" else activeProfileId,
            )
            .putExtra(EXTRA_CONNECTED_AT, connectedAt)
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

        const val ACTION_CONNECT = "com.nodewayvpn.pro.action.CONNECT"
        const val ACTION_SWITCH = "com.nodewayvpn.pro.action.SWITCH"
        const val ACTION_DISCONNECT = "com.nodewayvpn.pro.action.DISCONNECT"
        const val ACTION_STATE = "com.nodewayvpn.pro.action.STATE"

        const val EXTRA_LINK = "extra_link"
        const val EXTRA_STATE = "extra_state"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_CONNECTED_AT = "extra_connected_at"

        private const val CHANNEL_ID = "nodeway_vpn_service"
        private const val NOTIFICATION_ID = 0x4E57
        private const val MAX_ERROR_LENGTH = 220

        private const val TUN_MTU = 1500
        private const val TUN_ADDRESS = "10.23.0.2"
        private const val TUN_PREFIX_LENGTH = 32

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

        fun stop(context: Context) {
            val intent = Intent(context, NodewayVpnService::class.java)
                .setAction(ACTION_DISCONNECT)
            runCatching { context.startService(intent) }
        }
    }
}