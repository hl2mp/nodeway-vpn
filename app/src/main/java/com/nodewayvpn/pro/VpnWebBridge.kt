package com.nodewayvpn.pro

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Объект, доступный UI в WebView как `window.NodewayVpn`.
 *
 * Методы вызываются на приватном JS-потоке WebView, поэтому реализация в
 * [MainActivity] перепрыгивает на главный поток и возвращает JSON-строкуми.
 *
 * Долгие операции (загрузка подписки) асинхронные: метод возвращает сразу,
 * а результат приходит в `window.onImportResult(json)`.
 */
class VpnWebBridge(private val host: Host) {

    interface Host {
        fun onConnectRequested(profileId: String)

        fun onDisconnectRequested()

        /**
         * Синхронно: читает буфер обмена и импортирует его содержимое.
         *
         * Одна ссылка http(s) — подписка, остальное — список конфигураций.
         *
         * @return JSON с результатом импорта.
         */
        fun onImportClipboardRequested(): String

        

        fun onRefreshSubscriptionRequested(id: String)

        fun onRemoveSubscriptionRequested(id: String)

        fun onRemoveProfileRequested(id: String)

        fun onSelectProfileRequested(id: String)

        fun coreVersion(): String

        fun appVersion(): String

        /** Полный снимок профилей, подписок и активного подключения. */
        fun uiState(): String
    }

    @JavascriptInterface
    fun getInitialState(): String = uiState()

    @JavascriptInterface
    fun importFromClipboard(): String = host.onImportClipboardRequested()

    @JavascriptInterface
    fun refreshSubscription(id: String) = host.onRefreshSubscriptionRequested(id)

    @JavascriptInterface
    fun removeSubscription(id: String) = host.onRemoveSubscriptionRequested(id)

    @JavascriptInterface
    fun removeProfile(id: String) = host.onRemoveProfileRequested(id)

    @JavascriptInterface
    fun selectProfile(id: String) = host.onSelectProfileRequested(id)

    @JavascriptInterface
    fun requestConnect(profileId: String) = host.onConnectRequested(profileId)

    @JavascriptInterface
    fun requestDisconnect() = host.onDisconnectRequested()

    private fun uiState(): String = JSONObject().apply {
        put("state", JSONObject(host.uiState()))
        put("coreVersion", host.coreVersion())
        put("appVersion", host.appVersion())
    }.toString()
}