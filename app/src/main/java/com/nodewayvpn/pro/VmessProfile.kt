package com.nodetun.vpn

import org.json.JSONObject
import java.net.URLDecoder
import android.util.Base64
import java.util.Locale

data class VmessProfile(
    val id: String,
    val address: String,
    val port: Int,
    val remark: String,
    val alterId: Int = 0,
    val vmessSecurity: String = "auto",
    val security: String = "none",
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
        append("  ·  VMess/")
        append(network)
    }

    fun toLink(): String {
        // Canonical URI form (v2rayN new format) so round-trip stays simple.
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
        params += "encryption" to vmessSecurity
        if (alterId != 0) params += "aid" to alterId.toString()

        return buildString {
            append("vmess://")
            append(id)
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
        private val UUID_REGEX =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun isVmessLink(link: String): Boolean = link.trim().startsWith("vmess://", ignoreCase = true)

        fun parse(link: String): VmessProfile {
            val raw = link.trim()
            require(isVmessLink(raw)) { "Ссылка должна начинаться с vmess://" }
            val body = raw.substring("vmess://".length)

            // Base64 JSON form (v2rayN legacy).
            if (!body.contains('@')) {
                return parseBase64(body)
            }

            val remarkPart = body.substringAfter('#', "")
            val remark = runCatching { URLDecoder.decode(remarkPart, "UTF-8") }.getOrDefault(remarkPart)
            val withoutFragment = body.substringBefore('#')
            val query = withoutFragment.substringAfter('?', "")
            val authority = withoutFragment.substringBefore('?')

            val id = authority.substringBefore('@')
            // vmess uuid may be missing in some links; allow any non-empty.
            require(id.isNotBlank()) { "Некорректный UUID пользователя" }

            val hostAndPort = authority.substringAfter('@')
            val (address, port) = splitHostPort(hostAndPort)

            val params = parseQuery(query)

            val network = params["type"]?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
                ?.let { when (it) { "splithttp" -> "xhttp"; "h2" -> "http"; else -> it } }
                ?: "tcp"
            val security = params["security"]?.lowercase(Locale.ROOT)
                ?.let { if (it == "xtls") "tls" else it }
                ?.takeIf { it.isNotBlank() } ?: "none"
            val hostHeader = params["host"].orEmpty()
            val path = params["path"].orEmpty().ifBlank { "/" }
            val serviceName = params["serviceName"].orEmpty()

            return VmessProfile(
                id = id,
                address = address,
                port = port,
                remark = remark,
                alterId = params["aid"]?.toIntOrNull() ?: params["alterId"]?.toIntOrNull() ?: 0,
                vmessSecurity = params["encryption"].orEmpty().ifBlank { params["scy"].orEmpty().ifBlank { "auto" } },
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

        private fun parseBase64(body: String): VmessProfile {
            val decoded = runCatching {
                val normalized = body.replace("-", "+").replace("_", "/")
                val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
                String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: throw IllegalArgumentException("Некорректная ссылка vmess")
            val json = runCatching { JSONObject(decoded) }.getOrElse {
                throw IllegalArgumentException("Некорректный JSON vmess")
            }
            val address = json.optString("add").ifBlank { json.optString("address") }
            val port = json.optInt("port", 0)
            require(address.isNotBlank()) { "Не указан адрес сервера" }
            require(port in 1..65535) { "Порт вне диапазона 1..65535" }
            val id = json.optString("id").ifBlank { json.optString("uuid") }
            require(id.isNotBlank()) { "Некорректный UUID пользователя" }
            val tls = json.optString("tls", "none")
            val security = when (tls.lowercase(Locale.ROOT)) {
                "tls" -> "tls"
                else -> "none"
            }
            val net = json.optString("net", "tcp").lowercase(Locale.ROOT)
                .let { when (it) { "splithttp" -> "xhttp"; "h2" -> "http"; else -> it } }
            val host = json.optString("host", "")
            val path = json.optString("path", "/").ifBlank { "/" }
            return VmessProfile(
                id = id,
                address = address,
                port = port,
                remark = json.optString("ps").ifBlank { json.optString("remark") },
                alterId = json.optInt("aid", 0),
                vmessSecurity = json.optString("scy", "auto").ifBlank { "auto" },
                security = security,
                sni = json.optString("sni").ifBlank { host.ifBlank { address } },
                alpn = json.optString("alpn").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                fingerprint = json.optString("fp"),
                allowInsecure = json.optString("allowInsecure").toFlag(),
                network = net,
                path = path,
                hostHeader = host,
                serviceName = json.optString("serviceName").ifBlank { path.trimStart('/') },
                mode = json.optString("mode"),
                headerType = json.optString("type", "none").ifBlank { "none" },
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