package com.nodewayvpn.pro

import android.util.Log
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject

/**
 * Тонкая обёртка над встроенным Xray-core (gomobile-биндинг `libXray.LibXray`).
 *
 * Все вызовы блокирующие, их нужно делать вне главного потока.
 */
object XrayCore {

    private const val TAG = "XrayCore"

    /** `LibXrayAPIVersion` в Go-биндинге. */
    private const val API_VERSION = 3

    private const val METHOD_RUN = "runXray"
    private const val METHOD_STOP = "stopXray"
    private const val METHOD_VERSION = "xrayVersion"

    /**
     * Запускает ядро с указанной конфигурацией.
     * @return пустая строка при успехе, иначе текст ошибки от ядра.
     */
    fun start(xrayJson: String): String = invoke(METHOD_RUN, JSONObject().put("xrayJson", xrayJson))

    fun stop() {
        val error = invoke(METHOD_STOP, JSONObject())
        if (error.isNotEmpty()) Log.w(TAG, "stopXray: $error")
    }

    fun version(): String = try {
        rawInvoke(METHOD_VERSION, JSONObject())
            .optJSONObject("data")
            ?.optString("version")
            .orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "xrayVersion failed", e)
        ""
    }

    /** Пускает резолвер Go мимо туннеля, иначе DNS уходит в него же и зацикливается. */
    fun setupDns(server: String, controller: DialerController) {
        runCatching { LibXray.setDNS(controller, server) }
            .onFailure { Log.w(TAG, "setDNS failed", it) }
    }

    fun releaseDns() {
        runCatching { LibXray.resetDNS() }
            .onFailure { Log.w(TAG, "resetDNS failed", it) }
    }

    fun registerDialer(controller: DialerController) {
        LibXray.registerDialerController(controller)
        LibXray.registerListenerController(controller)
    }

    /** @return текст ошибки, пустая строка при успешном вызове. */
    private fun invoke(method: String, payload: JSONObject): String {
        val response = rawInvoke(method, payload)
        if (response.optBoolean("success", false)) return ""
        return response.optString("error").ifEmpty { "Неизвестная ошибка ядра" }
    }

    private fun rawInvoke(method: String, payload: JSONObject): JSONObject {
        val request = JSONObject()
            .put("apiVersion", API_VERSION)
            .put("method", method)
            .put("payload", payload)
        val raw = LibXray.invoke(request.toString())
        return JSONObject(raw)
    }
}