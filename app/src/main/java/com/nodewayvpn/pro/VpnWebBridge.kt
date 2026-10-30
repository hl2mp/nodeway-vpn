package com.nodetun.vpn

import android.webkit.JavascriptInterface
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Объект, доступный UI в WebView как `window.NodewayVpn`.
 *
 * Методы вызываются на приватном JS-потоке WebView, поэтому реализация в
 * [MainActivity] перепрыгивает на главный поток и возвращает JSON-строками.
 *
 * Долгие операции (загрузка подписки) асинхронные: метод возвращает сразу,
 * а результат приходит в `window.onImportResult(json)`.
 */
class VpnWebBridge(private val host: Host) {

    interface Host {
        fun onConnectRequested(profileId: String)

        fun onDisconnectRequested()

    /**
     * Запуск поочерёдной проверки переданных профилей.
     *
     * На вход — список id: Activity сама достаёт из хранилища ссылки и
     * передаёт сервису готовые пары, а страница ничего о ссылках не знает.
     */
    fun onPingRequested(profileIds: List<String>)

    /**
     * Отмена текущей проверки профилей.
     *
     * Вызывается когда пользователь нажал "Остановить" в UI во время пинга.
     */
    fun onCancelPingRequested()

        /**
         * Синхронно: читает буфер обмена и импортирует его содержимое.
         *
         * Одна ссылка http(s) — подписка, остальное — список конфигураций.
         *
         * @return JSON с результатом импорта.
         */
        fun onImportClipboardRequested(): String

        /** Запуск сканера QR-кодов. Результат приходит в window.onScanQrResult(json). */
        fun onScanQrRequested()

        fun onRefreshSubscriptionRequested(id: String)

        fun onRemoveSubscriptionRequested(id: String)

        fun onRemoveProfileRequested(id: String)

        /**
         * Сохраняет новый или обновляет существующий профиль.
         *
         * @return JSON с результатом сохранения.
         */
        fun onSaveProfileRequested(id: String?, link: String, name: String): String

        /** Читает сырой текст из буфера обмена для подстановки в форму. */
        fun onReadClipboardText(): String

        fun onSelectProfileRequested(id: String)

        fun coreVersion(): String

        /** Версия ядра olcrtc: пустая строка, если версию узнать не удалось. */
        fun olcrtcVersion(): String

        fun appVersion(): String

        /** Полный снимок профилей, подписок и активного подключения. */
        fun uiState(): String

        /** Текущий режим и набор пакетов раздельного туннелирования. */
        fun onGetSplitTunnelSettings(): String

        /** Сохраняет режим раздельного туннелирования. */
        fun onSetSplitTunnelModeRequested(mode: String): String

        /** Добавляет или убирает пакет из набора раздельного туннелирования. */
        fun onSetPackageSelectedRequested(packageName: String, selected: Boolean): String

        /**
         * Сохраняет весь список приложений раздельного туннелирования разом.
         *
         * Список приходит JSON-массивом. Отмечать по одному нельзя: туннель
         * пересоздаётся на каждое изменение и рвёт активное соединение.
         */
        fun onSetSelectedPackagesRequested(packagesJson: String): String

        /** Список установленных приложений для выбора в настройках туннелирования. */
        fun onListInstalledAppsRequested(includeSystem: Boolean, query: String): String

        /**
         * Тот же список, но считается в отдельном потоке.
         *
         * Синхронный вариант замирал на слабых устройствах: пока считается список,
         * браузер не может отрисовать индикатор загрузки. Ответ приходит вызовом
         * `window.onSplitApps(token, json)`.
         */
        fun onListInstalledAppsAsyncRequested(
            token: String,
            includeSystem: Boolean,
            query: String,
        )

        

        /** Ведётся ли журнал: выключатель в настройках. */
        fun journalEnabled(): Boolean

        /**
         * Включает или выключает журнал.
         *
         * @return JSON с новым состоянием.
         */
        fun onSetJournalEnabledRequested(enabled: Boolean): String

        /**
         * Выбор темы оформления: "system", "light" или "dark".
         *
         * Применяется сразу, без перезагрузки страницы, и возвращается обратно
         * тем же вызовом: настройка видна в том же экране, где её выбрали.
         */
        fun onSetThemeModeRequested(mode: String)

        /** Проверить наличие обновлений вручную. */
        fun onCheckUpdateRequested()

        /** Начать скачивание обновления. */
        fun onStartUpdate()

        /** Отменить скачивание обновления. */
        fun onCancelUpdate()

        /** Установить скачанное обновление. */
        fun onInstallUpdate()
    }

    @JavascriptInterface
    fun getInitialState(): String = uiState()

    @JavascriptInterface
    fun importFromClipboard(): String = host.onImportClipboardRequested()

    /** Запуск сканера QR-кодов. Результат приходит в window.onScanQrResult(json). */
    @JavascriptInterface
    fun scanQr() = host.onScanQrRequested()

    @JavascriptInterface
    fun refreshSubscription(id: String) = host.onRefreshSubscriptionRequested(id)

    @JavascriptInterface
    fun removeSubscription(id: String) = host.onRemoveSubscriptionRequested(id)

    @JavascriptInterface
    fun removeProfile(id: String) = host.onRemoveProfileRequested(id)

    @JavascriptInterface
    fun saveProfile(id: String?, link: String, name: String): String =
        host.onSaveProfileRequested(id?.ifBlank { null }, link, name)

    @JavascriptInterface
    fun readClipboardText(): String = host.onReadClipboardText()

    @JavascriptInterface
    fun selectProfile(id: String) = host.onSelectProfileRequested(id)

    @JavascriptInterface
    fun requestConnect(profileId: String) = host.onConnectRequested(profileId)

    @JavascriptInterface
    fun requestDisconnect() = host.onDisconnectRequested()

    /**
     * Проверка списка профилей: [jsonIds] — массив id вида `["a","b"]`.
     *
     * Синхронно не читаем ничего тяжёлого: разбор и запуск уходят в Activity.
     */
    @JavascriptInterface
    fun pingProfiles(jsonIds: String) {
        val ids = try {
            val array = JSONArray(jsonIds)
            List(array.length()) { index -> array.optString(index) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w("NodewayBridge", "pingProfiles got a malformed id list", e)
            emptyList()
        }
        if (ids.isNotEmpty()) host.onPingRequested(ids)
    }

    /**
     * Отмена текущей проверки профилей.
     *
     * Вызывается из JS когда пользователь нажал "Остановить".
     */
    @JavascriptInterface
    fun cancelPing() = host.onCancelPingRequested()

    @JavascriptInterface
    fun getSplitTunnelSettings(): String = host.onGetSplitTunnelSettings()

    @JavascriptInterface
    fun setSplitTunnelMode(mode: String): String = host.onSetSplitTunnelModeRequested(mode)

    @JavascriptInterface
    fun setPackageSelected(packageName: String, selected: Boolean): String =
        host.onSetPackageSelectedRequested(packageName, selected)

    @JavascriptInterface
    fun setSelectedPackages(packagesJson: String): String =
        host.onSetSelectedPackagesRequested(packagesJson)

    @JavascriptInterface
    fun listInstalledApps(includeSystem: Boolean, query: String): String =
        host.onListInstalledAppsRequested(includeSystem, query)

    @JavascriptInterface
    fun listInstalledAppsAsync(token: String, includeSystem: Boolean, query: String) =
        host.onListInstalledAppsAsyncRequested(token, includeSystem, query)

    

    @JavascriptInterface
    fun setJournalEnabled(enabled: Boolean): String = host.onSetJournalEnabledRequested(enabled)

    /** Тема оформления. Режим сразу применяется, ответ приходит в onThemeMode. */
    @JavascriptInterface
    fun setThemeMode(mode: String) = host.onSetThemeModeRequested(mode)

    /** Проверить обновления вручную (из настроек). */
    @JavascriptInterface
    fun checkUpdate() = host.onCheckUpdateRequested()

    /** Начать скачивание обновления. */
    @JavascriptInterface
    fun startUpdate() = host.onStartUpdate()

    /** Отменить скачивание обновления. */
    @JavascriptInterface
    fun cancelUpdate() = host.onCancelUpdate()

    /** Установить скачанное обновление. */
    @JavascriptInterface
    fun installUpdate() = host.onInstallUpdate()

    private fun uiState(): String = JSONObject().apply {
        put("state", JSONObject(host.uiState()))
        put("coreVersion", host.coreVersion())
        put("olcrtcVersion", host.olcrtcVersion())
        put("appVersion", host.appVersion())
        put("journalEnabled", host.journalEnabled())
    }.toString()
}