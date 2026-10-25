package com.nodetun.vpn

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

    /** Reconstructs the `vless://` share link from this profile. */
    fun toLink(): String = buildString {
        append("vless://")
        append(id)
        append('@')
        if (address.contains(":") && !address.startsWith("[")) {
            append('[').append(address).append(']')
        } else {
            append(address)
        }
        append(':')
        append(port)

        val params = mutableListOf<Pair<String, String>>()
        if (network.isNotBlank() && !network.equals("tcp", ignoreCase = true)) {
            params += "type" to network
        }
        if (security.isNotBlank() && !security.equals("none", ignoreCase = true)) {
            params += "security" to security
        }
        if (flow.isNotBlank()) {
            params += "flow" to flow
        }
        if (hostHeader.isNotBlank()) {
            params += "host" to hostHeader
        }
        if (sni.isNotBlank() && sni != address && sni != hostHeader) {
            params += "sni" to sni
        }
        if (path.isNotBlank() && path != "/") {
            params += "path" to path
        }
        if (headerType.isNotBlank() && !headerType.equals("none", ignoreCase = true)) {
            params += "headerType" to headerType
        }
        if (serviceName.isNotBlank() && serviceName != path.trimStart('/')) {
            params += "serviceName" to serviceName
        }
        if (mode.isNotBlank()) {
            params += "mode" to mode
        }
        if (alpn.isNotEmpty()) {
            params += "alpn" to alpn.joinToString(",")
        }
        if (fingerprint.isNotBlank()) {
            params += "fp" to fingerprint
        }
        if (publicKey.isNotBlank()) {
            params += "pbk" to publicKey
        }
        if (shortId.isNotBlank()) {
            params += "sid" to shortId
        }
        if (spiderX.isNotBlank() && spiderX != "/") {
            params += "spx" to spiderX
        }
        if (allowInsecure) {
            params += "allowInsecure" to "1"
        }

        if (params.isNotEmpty()) {
            append('?')
            append(params.joinToString("&") { (k, v) ->
                "$k=${encodeParam(v)}"
            })
        }

        if (remark.isNotBlank()) {
            append('#')
            append(encodeParam(remark))
        }
    }

    private fun encodeParam(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

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

            // Имена транспортов приводим к тем, что ждёт ядро: `splithttp` — прежнее
            // имя XHTTP, `h2` в конфиге называется `http`. Без этого блок настроек
            // транспорта не попал бы в конфиг и профиль молча не поднялся бы.
            val network = params["type"]?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
                ?.let { when (it) { "splithttp" -> "xhttp"; "h2" -> "http"; else -> it } }
                ?: "tcp"
            // `xtls` — старое имя шифрования, ядро его больше не принимает, а ссылки с
            // ним ещё встречаются. Без нормализации в конфиг ушёл бы `security: "xtls"`
            // вовсе без блока настроек: ни TLS, ни REALITY, и профиль не поднялся бы.
            val security = params["security"]?.lowercase(Locale.ROOT)
                ?.let { if (it == "xtls") "tls" else it }
                ?.takeIf { it.isNotBlank() } ?: "none"
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
            val result = mutableMapOf<String, String>()
            query.split('&').forEach { pair ->
                if (pair.isNotBlank()) {
                    val key = pair.substringBefore('=').trim()
                    val rawVal = pair.substringAfter('=', "").trim()
                    if (key.isNotEmpty()) {
                        val decodedVal = runCatching {
                            URLDecoder.decode(rawVal, "UTF-8")
                        }.getOrDefault(rawVal)
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