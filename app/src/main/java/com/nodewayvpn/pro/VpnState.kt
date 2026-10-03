package com.nodewayvpn.pro

/**
 * Lifecycle of the tunnel as seen by the UI layer.
 */
enum class VpnState(val code: String) {
    DISCONNECTED("disconnected"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    DISCONNECTING("disconnecting"),
    ERROR("error");

    companion object {
        fun fromCode(code: String?): VpnState = entries.firstOrNull { it.code == code } ?: DISCONNECTED
    }
}