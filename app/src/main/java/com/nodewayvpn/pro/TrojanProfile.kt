package com.nodetun.vpn

import java.net.URLDecoder
import java.util.Locale

data class TrojanProfile(
    val password: String,
    val address: String,
    val port: Int,
    val remark: String,
    val security: String = "tls",
    val sni: String = "",
    val alpn: List<String> = emptyList(),
    val fingerprint: String = "",
    val allowInsecure: Boolean = false,
    val network: String = "tcp",
    val path: String = "",
    val hostHeader: String = "",
    val serviceName: String = "",
    val mode: String = "",
    val headerType: String = "none",
) {
    fun describe(): String = buildString {
        append(remark.ifBlank { "$address:$port" })
        append("  ·  Trojan/")
        append(network)
    }

    fun toLink(): String {
        val params = mutableListOf<Pair<String, String>>()
        if (network.isNotBlank() && !network.equals("tcp", ignoreCase = true)) params += "type" to network
        if (security.isNotBlank() && !security.equals("none", ignoreCase = true)) params += "security" to security
        if (hostHeader.isNotBlank()) params += "host" to hostHeader
        if (sni.isNotBlank() && sni != address && sni != hostHeader) params += "sni" to sni
        if (path.isNotBlank() && path != "/") params += "path" to path
        if (headerType.isNotBlank() && !headerType.equals("none", ignoreCase = true)) params += "headerType" to headerType
        if (serviceName.isNotBlank()) params += "serviceName" to serviceName
        if (mode.isNotBlank()) params += "mode" to mode
        if (alpn.isNotEmpty()) params += "alpn" to alpn.joinToString(",")
        if (fingerprint.isNotBlank()) params += "fp" to fingerprint
        if (allowInsecure) params += "allowInsecure" to "1"

        return buildString {
            append("trojan://")
            append(enc(password))
            append('@')
            append(address)
            append(':')
            append(port)
            if (params.isNotEmpty()) {
                append('?')
                append(params.joinToString("&") { (k, v) -> "$k=${enc(v)}" })
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
        fun isTrojanLink(link: String): Boolean = link.trim().startsWith("trojan://", ignoreCase = true)

        fun parse(link: String): TrojanProfile {
            val raw = link.trim()
            require(isTrojanLink(raw)) { "Ссылка должна начинаться с trojan://" }
            val body = raw.substring("trojan://".length)

            val remarkPart = body.substringAfter('#', "")
            val remark = runCatching { URLDecoder.decode(remarkPart, "UTF-8") }.getOrDefault(remarkPart)
            val withoutFragment = body.substringBefore('#')
            val query = withoutFragment.substringAfter('?', "")
            val authority = withoutFragment.substringBefore('?')

            val password = authority.substringBefore('@')
            require(password.isNotBlank()) { "Некорректный пароль" }

            val hostAndPort = authority.substringAfter('@')
            val (address, port) = splitHostPort(hostAndPort)

            val params = parseQuery(query)

            val network = params["type"]?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
                ?.let { when (it) { "splithttp" -> "xhttp"; "h2" -> "http"; else -> it } }
                ?: "tcp"
            val security = params["security"]?.lowercase(Locale.ROOT)
                ?.let { if (it == "xtls") "tls" else it }
                ?.takeIf { it.isNotBlank() } ?: "tls"
            val hostHeader = params["host"].orEmpty()
            val path = params["path"].orEmpty().ifBlank { "/" }
            val serviceName = params["serviceName"].orEmpty()

            return TrojanProfile(
                password = password,
                address = address,
                port = port,
                remark = remark,
                security = security,
                sni = params["sni"].orEmpty().ifBlank { hostHeader.ifBlank { address } },
                alpn = params["alpn"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                fingerprint = params["fp"].orEmpty(),
                allowInsecure = params["allowInsecure"].toFlag() || params["insecure"].toFlag(),
                network = network,
                path = path,
                hostHeader = hostHeader,
                serviceName = serviceName.ifBlank { path.trimStart('/') },
                mode = params["mode"].orEmpty(),
                headerType = params["headerType"].orEmpty().ifBlank { "none" },
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