package com.nodewayvpn.pro

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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

    /** Пользовательские настройки: последняя ссылка и раздельное туннелирование. */
    private lateinit var prefs: Prefs

    /** Список установленных приложений, пересчитывается только после смены настроек. */
    @Volatile
    private var installedAppsCache: List<InstalledApp>? = null

    /** Иконки приложений в base64, чтобы не перерисовывать их при каждом показе. */
    private val iconCache = object : LruCache<String, String>(ICON_CACHE_SIZE) {
        override fun sizeOf(key: String, value: String): Int = value.length
    }

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

    /** Состояние туннеля — нужно для горячей смены профиля. */
    private var vpnState: String = VpnState.DISCONNECTED.code

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
            val profileId = intent.getStringExtra(NodewayVpnService.EXTRA_PROFILE_ID).orEmpty()
            // Активный профиль приходит из сервиса: при перезапуске он иначе терялся.
            activeProfileId = profileId
            vpnState = state
            store.connectedAt = intent.getLongExtra(NodewayVpnService.EXTRA_CONNECTED_AT, 0L)
            pushProfiles()
            pushState(state, message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        store = ProfileStore(this)
        prefs = Prefs(this)
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
            }
            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    // Первая отрисовка может обогнать применение инсетов.
                    pushInsets()
                }
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

        webView.loadUrl("file:///android_asset/index.html")
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
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
        webView.removeJavascriptInterface(BRIDGE_NAME)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        network.shutdownNow()
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
        val vless = parsed.vlessLinks
        if (vless.isEmpty()) {
            val hint = if (parsed.unsupported.isNotEmpty()) {
                "В буфере только неподдерживаемые ссылки: " +
                    parsed.unsupported.map { it.scheme }.distinct().joinToString()
            } else {
                "В буфере не найдено ни одной vless:// ссылки"
            }
            return result(ok = false, message = hint)
        }

        val added = store.addProfiles(
            links = vless.map { it.raw },
            names = vless.associate { it.raw to remarkOf(it.raw) },
            source = ServerProfile.Source.CLIPBOARD,
            subscriptionId = null,
        )
        pushProfiles()

        val message = when {
            added == 0 -> "Такие ссылки уже были добавлены"
            added < vless.size -> "Добавлено $added из ${vless.size} (остальные уже есть)"
            else -> "Добавлено профилей: $added"
        }
        return result(
            ok = true,
            added = added,
            total = vless.size,
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

    override fun appVersion(): String = BuildConfig.VERSION_NAME

    override fun uiState(): String = store.toUiModel(activeProfileId).toString()

    // ai-generated
    override fun onGetSplitTunnelSettings(): String = JSONObject().apply {
        put("mode", prefs.tunnelMode)
        put("packages", JSONArray(prefs.splitPackages.toList()))
        put("selfPackage", packageName)
    }.toString()

    // ai-generated
    override fun onSetSplitTunnelModeRequested(mode: String): String {
        val next = TunnelMode.fromCode(mode.trim())
        // Пустой список в режиме allow отрезал бы трафик вообще, поэтому не пускаем.
        if (next == TunnelMode.ALLOW && prefs.splitPackages.isEmpty()) {
            return splitResult(ok = false, error = "Выберите хотя бы одно приложение")
        }
        prefs.tunnelMode = next.code
        Log.i(TAG, "Split tunneling mode: ${next.code}")
        restartTunnelWithSplitSettings()
        return splitResult(ok = true)
    }

    // ai-generated
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

    // ai-generated
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

    // ai-generated
    override fun onListInstalledAppsRequested(includeSystem: Boolean, query: String): String {
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

    // ai-generated
    override fun onGetAppIconRequested(packageName: String): String {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return ""
        iconCache.get(pkg)?.let { return it }
        val dataUrl = runCatching { encodeIcon(pkg) }.getOrDefault("")
        if (dataUrl.isNotEmpty()) iconCache.put(pkg, dataUrl)
        return dataUrl
    }

    // endregion

    // region раздельное туннелирование

    /** Приложение в том виде, в котором оно показано в настройках туннелирования. */
    private data class InstalledApp(val pkg: String, val label: String, val system: Boolean)

    /** Установленные приложения. Список кэшируется: он дорогой и меняется редко. */
    // ai-generated
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

    /** Иконка приложения как data-URL: WebView умеет показывать такие картинки напрямую. */
    // ai-generated
    private fun encodeIcon(packageName: String): String {
        val drawable = packageManager.getApplicationIcon(packageName)
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        if (width <= 0 || height <= 0) return ""

        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Масштабируем с сохранением пропорций и центрируем, иначе иконки будут растянуты.
        val scale = maxOf(ICON_SIZE_PX.toFloat() / width, ICON_SIZE_PX.toFloat() / height)
        canvas.translate((ICON_SIZE_PX - width * scale) / 2f, (ICON_SIZE_PX - height * scale) / 2f)
        canvas.scale(scale, scale)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return "data:image/png;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /** Пересоздаёт туннель с теми же настройками, если VPN уже поднят. */
    // ai-generated
    private fun restartTunnelWithSplitSettings() = runOnUiThread {
        if (vpnState != VpnState.CONNECTED.code && vpnState != VpnState.CONNECTING.code) {
            return@runOnUiThread
        }
        val profile = store.profile(activeProfileId) ?: return@runOnUiThread
        Log.i(TAG, "Recreating tunnel with split tunneling settings")
        NodewayVpnService.switchTo(this, profile.link, profile.id)
    }

    /** Ответ на изменение настроек раздельного туннелирования. */
    // ai-generated
    private fun splitResult(ok: Boolean, error: String = ""): String = JSONObject().apply {
        put("ok", ok)
        put("error", error)
    }.toString()

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
        val vless = parsed.vlessLinks
        if (vless.isEmpty()) {
            return result(
                ok = false,
                message = "В подписке нет vless:// ссылок",
                title = parsed.title,
            )
        }

        val profiles = vless.map { link ->
            ServerProfile.create(
                link = link.raw,
                name = remarkOf(link.raw),
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

    /** Имя из фрагмента `#…` ссылки, иначе — адрес сервера. */
    private fun remarkOf(link: String): String = runCatching {
        val parsed = VlessProfile.parse(link)
        parsed.remark.ifBlank { parsed.address }
    }.getOrDefault("")

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
        if (!VlessProfile.isVlessLink(profile.link)) {
            pushState(VpnState.ERROR.code, "Ссылка должна начинаться с vless://")
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
    private fun pushState(state: String, message: String?) {
        val payload = JSONObject().apply {
            put("state", state)
            put("message", message ?: "")
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

    /** Результат импорта или загрузки подписки. */
    private fun pushImportResult(payload: String) {
        val escaped = escape(payload)
        runOnUiThread {
            webView.evaluateJavascript("window.onImportResult && window.onImportResult('$escaped');", null)
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

        /** Сторона иконки приложения, отдаваемой в WebView. */
        const val ICON_SIZE_PX = 96

        /** Предельный объём кэша иконок в символах base64. */
        const val ICON_CACHE_SIZE = 512 * 1024

        /** Как часто проверяем, не пора ли обновить подписку. */
        const val REFRESH_CHECK_INTERVAL_MS = 60_000L

        /** Ниже этого интервала не обновляем, даже если сервер просит `#refresh: 1m`. */
        const val MIN_REFRESH_INTERVAL_MS = 15 * 60_000L
    }
}