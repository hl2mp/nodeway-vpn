package com.nodetun.vpn

import java.net.URLDecoder
import java.util.Locale

data class SocksProfile(
    val username: String = "",
    val password: String = "",
    val address: String,
    val port: Int,
    val remark: String,
    val version: Int = 5, // 4 or 5
) {
    fun describe(): String = buildString {
        append(remark.ifBlank { "$address:$port" })
        append("  ·  SOCKS")
        append(version)
    }

    fun toLink(): String {
        val scheme = if (version == 4) "socks4" else "socks5"
        val userInfo = if (username.isNotBlank() || password.isNotBlank()) {
            java.net.URLEncoder.encode(username, "UTF-8") + ":" + java.net.URLEncoder.encode(password, "UTF-8") + "@"
        } else ""
        return buildString {
            append(scheme)
            append("://")
            append(userInfo)
            append(address)
            append(':')
            append(port)
            if (remark.isNotBlank()) {
                append('#')
                append(java.net.URLEncoder.encode(remark, "UTF-8").replace("+", "%20"))
            }
        }
    }

    companion object {
        fun isSocksLink(link: String): Boolean {
            val s = link.trim().lowercase(Locale.ROOT)
            return s.startsWith("socks://") || s.startsWith("socks4://") || s.startsWith("socks5://")
        }

        fun parse(link: String): SocksProfile {
            val raw = link.trim()
            require(isSocksLink(raw)) { "Ссылка должна начинаться с socks://" }
            val lower = raw.lowercase(Locale.ROOT)
            val version = when {
                lower.startsWith("socks4://") -> 4
                else -> 5
            }
            val body = raw.substringAfter("://")

            val remarkPart = body.substringAfter('#', "")
            val remark = runCatching { URLDecoder.decode(remarkPart, "UTF-8") }.getOrDefault(remarkPart)
            val withoutFragment = body.substringBefore('#')

            val authority = withoutFragment
            val (userInfo, hostPort) = if (authority.contains('@')) {
                val idx = authority.lastIndexOf('@')
                authority.substring(0, idx) to authority.substring(idx + 1)
            } else {
                "" to authority
            }

            val (username, password) = if (userInfo.isNotBlank()) {
                val parts = userInfo.split(':', limit = 2)
                (runCatching { URLDecoder.decode(parts.getOrElse(0) { "" }, "UTF-8") }.getOrDefault(parts.getOrElse(0) { "" })) to
                    (runCatching { URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8") }.getOrDefault(parts.getOrElse(1) { "" }))
            } else "" to ""

            val (address, port) = splitHostPort(hostPort)

            return SocksProfile(
                username = username,
                password = password,
                address = address,
                port = port,
                remark = remark,
                version = version,
            )
        }

        private fun splitHostPort(value: String): Pair<String, Int> {
            val host: String
            val portText: String
            if (value.startsWith("[")) {
                val end = value.indexOf(']')
                require(end > 1 && value.getOrNull(end + 1) == ':') { "Не указан порт сервера" }
                host = value.substring(1, end)
                portText = value.substring(end + 2)
            } else {
                val lastColon = value.lastIndexOf(':')
                require(lastColon > 0) { "Не указан порт сервера" }
                host = value.substring(0, lastColon)
                portText = value.substring(lastColon + 1)
            }
            val port = portText.toIntOrNull() ?: throw IllegalArgumentException("Неверный порт сервера")
            require(port in 1..65535) { "Порт вне диапазона 1..65535" }
            return host to port
        }
    }
}