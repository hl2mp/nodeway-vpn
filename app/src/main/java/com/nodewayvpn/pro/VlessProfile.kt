package com.nodewayvpn.pro

import android.net.Uri
import java.net.URLDecoder
import java.util.Locale

/**
 * Parsed representation of a `vless://` share link.
 *
 * Supported query parameters (Xray / XrayNG convention):
 * `encryption`, `security`, `sni`, `alpn`, `fp`, `pbk`, `sid`, `spx`, `type`,
 * `path`, `host`, `serviceName`, `mode`, `headerType`, `flow`, `allowInsecure`, `alpn`.
 */
data class VlessProfile(
    val id: String,
    val address: String,
    val port: Int,
    val remark: String,
    val security: String = "none",
    val sni: String = "",
    val alpn: List<String> = emptyList(),
    val fingerprint: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "/",
    val allowInsecure: Boolean = false,
    val network: String = "tcp",
    val path: String = "",
    val hostHeader: String = "",
    val serviceName: String = "",
    val mode: String = "",
    val headerType: String = "none",
    val flow: String = "",
) {
    val isReality: Boolean get() = security.equals("reality", ignoreCase = true)
    val isTls: Boolean get() = security.equals("tls", ignoreCase = true)

    /** Short human readable description shown in the UI. */
    fun describe(): String = buildString {
        append(remark.ifBlank { "$address:$port" })
        append("  ·  ")
        append(security.uppercase(Locale.ROOT))
        append('/')
        append(network)
    }

    companion object {

        private val UUID_REGEX =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun isVlessLink(link: String): Boolean = link.trim().startsWith("vless://", ignoreCase = true)

        fun parse(link: String): VlessProfile {
            val raw = link.trim()
            require(isVlessLink(raw)) { "Ссылка должна начинаться с vless://" }

            val body = raw.substring("vless://".length)

            val remarkPart = body.substringAfter('#', "")
            val remark = runCatching { URLDecoder.decode(remarkPart, "UTF-8") }.getOrDefault(remarkPart)

            val withoutFragment = body.substringBefore('#')
            val query = withoutFragment.substringAfter('?', "")
            val authority = withoutFragment.substringBefore('?')

            val id = authority.substringBefore('@')
            require(UUID_REGEX.matches(id)) { "Некорректный UUID пользователя" }

            val hostAndPort = authority.substringAfter('@')
            val (address, port) = splitHostPort(hostAndPort)

            val params = parseQuery(query)

            val network = params["type"]?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "tcp"
            val security = params["security"]?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "none"
            val hostHeader = params["host"].orEmpty()
            val path = params["path"].orEmpty().ifBlank { "/" }
            val serviceName = params["serviceName"].orEmpty()

            return VlessProfile(
                id = id,
                address = address,
                port = port,
                remark = remark,
                security = security,
                sni = params["sni"].orEmpty().ifBlank { hostHeader.ifBlank { address } },
                alpn = params["alpn"]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty(),
                fingerprint = params["fp"].orEmpty(),
                publicKey = params["pbk"].orEmpty(),
                shortId = params["sid"].orEmpty(),
                spiderX = params["spx"].orEmpty().ifBlank { "/" },
                // Клиенты пишут флаг по-разному: и allowInsecure=1, и insecure=true.
// Приём на nullable нужен, чтобы params[key] звал эту функцию, а не stdlib:
// у stdlib toBoolean() ресивер String?, и наш хелпер молча остался бы неиспользуемым.
                allowInsecure = params["allowInsecure"].toFlag() || params["insecure"].toFlag(),
                network = network,
                path = path,
                hostHeader = hostHeader,
                serviceName = serviceName.ifBlank { path.trimStart('/') },
                mode = params["mode"].orEmpty(),
                headerType = params["headerType"].orEmpty().ifBlank { "none" },
                flow = params["flow"].orEmpty(),
            )
        }

        private fun splitHostPort(value: String): Pair<String, Int> {
            val unbracketed = value.removePrefix("[").removeSuffix("]")
            // Из ссылки: пример «example.com:443» и голый IPv6-литерал вида «::1».
            val lastColon = unbracketed.lastIndexOf(':')
            val looksLikeIpv6 = unbracketed.count { it == ':' } > 1 && value.startsWith("[")
            require(lastColon > 0 && !looksLikeIpv6) { "Не указан порт сервера" }
            val host = unbracketed.substring(0, lastColon)
            val port = unbracketed.substring(lastColon + 1).toIntOrNull()
                ?: throw IllegalArgumentException("Неверный порт сервера")
            require(port in 1..65535) { "Порт вне диапазона 1..65535" }
            return host to port
        }

        private fun parseQuery(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            // Uri сам разбирает query и раскодирует percent-encoding.
            val uri = Uri.parse("http://localhost/?$query")
            val result = mutableMapOf<String, String>()
            uri.queryParameterNames.forEach { key ->
                uri.getQueryParameter(key)?.let { result[key] = it }
            }
            return result
        }

        private fun String?.toFlag(): Boolean =
            this != null && (equals("1", ignoreCase = true) || equals("true", ignoreCase = true))
    }
}