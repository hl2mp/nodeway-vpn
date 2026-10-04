package com.nodewayvpn.pro

/** Состояние туннеля в терминах, которые понимает UI. */
enum class VpnState(val code: String) {
    DISCONNECTED("disconnected"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    DISCONNECTING("disconnecting"),
    ERROR("error"),
}