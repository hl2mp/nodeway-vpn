package com.nodetun.vpn

import java.net.URLDecoder

data class ShadowsocksProfile(
    val method: String,
    val password: String,
    val address: String,
    val port: Int,
    val remark: String,
    val plugin: String = "",
    val pluginOpts: String = "",
    val network: String = "tcp",
    val path: String = "",
    val hostHeader: String = "",
    val sni: String = "",
) {
    fun describe(): String = buildString {
        append(remark.ifBlank { "$address:$port" })
        append("  ·  SS/")
        append(method)
    }

    fun toLink(): String {
        // SIP002 format: ss://base64(method:password)@host:port#remark
        val userInfo = Base64Url.encode("$method:$password")
        val params = mutableListOf<String>()
        if (plugin.isNotBlank()) params += "plugin=${enc(plugin + (if (pluginOpts.isNotBlank()) ";$pluginOpts" else ""))}"
        return buildString {
            append("ss://")
            append(userInfo)
            append('@')
            append(address)
            append(':')
            append(port)
            if (params.isNotEmpty()) {
                append('?')
                append(params.joinToString("&"))
            }
            if (remark.isNotBlank()) {
                append('#')
                append(enc(remark))
            }
        }
    }

    private fun enc(v: String): String =
        java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    companion object {
        private val METHODS = setOf(
            "aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
            "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
            "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm",
            "2022-blake3-chacha20-poly1305",
            "none", "plain",
        )

        fun isShadowsocksLink(link: String): Boolean = link.trim().startsWith("ss://", ignoreCase = true)

        fun parse(link: String): ShadowsocksProfile {
            val raw = link.trim()
            require(isShadowsocksLink(raw)) { "Ссылка должна начинаться с ss://" }
            val body = raw.substring("ss://".length)

            val remarkPart = body.substringAfter('#', "")
            val remark = runCatching { URLDecoder.decode(remarkPart, "UTF-8") }.getOrDefault(remarkPart)
            val withoutFragment = body.substringBefore('#')
            val query = withoutFragment.substringAfter('?', "")
            val authority = withoutFragment.substringBefore('?')

            // SIP002: userInfo may be base64(method:password) or method:password
            val (method, password, hostPort) = run {
                val atIdx = authority.lastIndexOf('@')
                if (atIdx >= 0) {
                    val userInfo = authority.substring(0, atIdx)
                    val hp = authority.substring(atIdx + 1)
                    val decoded = decodeUserInfo(userInfo)
                    val (m, p) = decoded.split(':', limit = 2).let {
                        require(it.size == 2) { "Некорректные данные пользователя" }
                        it[0] to it[1]
                    }
                    Triple(m, p, hp)
                } else {
                    // Legacy ss://base64 entire link
                    val decoded = decodeUserInfo(authority)
                    // decoded: method:password@host:port
                    val at = decoded.lastIndexOf('@')
                    require(at >= 0) { "Некорректная ссылка ss" }
                    val userInfo = decoded.substring(0, at)
                    val hp = decoded.substring(at + 1)
                    val (m, p) = userInfo.split(':', limit = 2).let {
                        require(it.size == 2) { "Некорректные данные пользователя" }
                        it[0] to it[1]
                    }
                    Triple(m, p, hp)
                }
            }

            require(method in METHODS) { "Неизвестный метод: $method" }
            val (address, port) = splitHostPort(hostPort)

            val params = parseQuery(query)
            val plugin = params["plugin"]?.substringBefore(';').orEmpty()
            val pluginOpts = params["plugin"]?.substringAfter(';', "").orEmpty()

            return ShadowsocksProfile(
                method = method,
                password = password,
                address = address,
                port = port,
                remark = remark,
                plugin = plugin,
                pluginOpts = pluginOpts,
            )
        }

        private fun decodeUserInfo(value: String): String {
            // Try base64url first.
            return runCatching {
                val normalized = value.replace("-", "+").replace("_", "/")
                val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
                String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrElse { value }
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

        private fun parseQuery(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            val result = mutableMapOf<String, String>()
            query.split('&').forEach { pair ->
                if (pair.isNotBlank()) {
                    val key = pair.substringBefore('=').trim()
                    val rawVal = pair.substringAfter('=', "").trim()
                    if (key.isNotEmpty()) {
                        val decodedVal = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
                        result[key] = decodedVal
                    }
                }
            }
            return result
        }

        private fun String?.toFlag(): Boolean =
            this != null && (equals("1", ignoreCase = true) || equals("true", ignoreCase = true))
    }
}

/** Small helper to base64url-encode user info without '+'/'/'. */
private object Base64Url {
    fun encode(value: String): String =
        android.util.Base64.encodeToString(value.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
            .trimEnd('=')
}