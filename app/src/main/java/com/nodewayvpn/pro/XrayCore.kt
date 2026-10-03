package com.nodewayvpn.pro

import android.util.Log
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject

/**
 * Thin Kotlin facade over the embedded Xray-core (gomobile binding `libXray.LibXray`).
 *
 * All calls are blocking and must be performed off the main thread.
 */
object XrayCore {

    private const val TAG = "XrayCore"

    /** `LibXrayAPIVersion` in the Go binding. */
    private const val API_VERSION = 3

    private const val METHOD_RUN = "runXray"
    private const val METHOD_STOP = "stopXray"
    private const val METHOD_TEST = "testXray"
    private const val METHOD_STATE = "getXrayState"
    private const val METHOD_VERSION = "xrayVersion"

    /**
     * Starts the core with the given configuration.
     * @return empty string on success, otherwise the error reported by the core.
     */
    fun start(xrayJson: String): String = invoke(METHOD_RUN, JSONObject().put("xrayJson", xrayJson))

    /** Validates a configuration without starting anything. */
    fun test(xrayJson: String): String = invoke(METHOD_TEST, JSONObject().put("xrayJson", xrayJson))

    fun stop() {
        val error = invoke(METHOD_STOP, JSONObject())
        if (error.isNotEmpty()) Log.w(TAG, "stopXray: $error")
    }

    fun isRunning(): Boolean = try {
        val response = rawInvoke(METHOD_STATE, JSONObject())
        val data = response.optJSONObject("data")
        data != null && data.optBoolean("running", false)
    } catch (e: Exception) {
        Log.w(TAG, "getXrayState failed", e)
        false
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

    /** Makes the Go DNS resolver bypass the VPN instead of looping into the tunnel. */
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

    /** @return error message, empty string when the call succeeded. */
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