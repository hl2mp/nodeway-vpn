package com.nodewayvpn.pro

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Builds a complete Xray-core configuration for the embedded core:
 * a `tun` inbound bound to the file descriptor obtained from [android.net.VpnService]
 * and a single VLESS outbound taken from the pasted share link.
 */
object XrayConfigBuilder {

    private const val MTU = 1500
    private const val TUN_NAME = "nodeway0"

    /** Подробные логи нужны для диагностики соединения с сервером. */
    private val DEFAULT_LOG_LEVEL = if (BuildConfig.DEBUG) "info" else "warning"

    /** DNS servers advertised to the TUN interface; shared with [NodewayVpnService]. */
    val DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")

    /** Networks used by the TUN inbound to resolve names. */
    private val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "224.0.0.0/4",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
    )

    fun build(profile: VlessProfile, tunFd: Int, logLevel: String = DEFAULT_LOG_LEVEL): String {
        val config = JSONObject()

        // Xray-core applies root level "env" entries with os.Setenv() before building
        // the config; proxy/tun/tun_android.go reads the descriptor from "xray.tun.fd".
        config.put("env", JSONObject().put("xray.tun.fd", tunFd.toString()))

        config.put("log", JSONObject().put("loglevel", logLevel))

        config.put("inbounds", JSONArray().put(tunInbound()))
        config.put("outbounds", JSONArray().put(vlessOutbound(profile)).put(freedomOutbound()))
        config.put("routing", routing())
        config.put("dns", dns())

        return config.toString()
    }

    private fun tunInbound(): JSONObject = JSONObject().apply {
        put("tag", "tun")
        put("protocol", "tun")
        put("settings", JSONObject().apply {
            put("name", TUN_NAME)
            put("mtu", MTU)
            put("dns", JSONArray(DNS_SERVERS))
        })
    }

    private fun vlessOutbound(profile: VlessProfile): JSONObject = JSONObject().apply {
        put("tag", "proxy")
        put("protocol", "vless")
        put("settings", JSONObject().apply {
            put("vnext", JSONArray().put(JSONObject().apply {
                put("address", profile.address)
                put("port", profile.port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", profile.id)
                    put("encryption", "none")
                    // XTLS Vision работает и с обычным TLS, не только с REALITY.
                    if (profile.flow.isNotBlank()) put("flow", profile.flow)
                }))
            }))
        })
        put("streamSettings", streamSettings(profile))
        put("mux", JSONObject().put("enabled", false).put("concurrency", -1))
    }

    private fun freedomOutbound(): JSONObject = JSONObject().apply {
        put("tag", "direct")
        put("protocol", "freedom")
        put("settings", JSONObject().put("domainStrategy", "UseIP"))
    }

    private fun streamSettings(profile: VlessProfile): JSONObject = JSONObject().apply {
        put("network", profile.network)
        put("security", profile.security)
        if (profile.fingerprint.isNotBlank() && !profile.isReality) {
            put("fingerprint", profile.fingerprint)
        }

        when (profile.network) {
            "ws" -> put("wsSettings", wsLikeSettings(profile))
            "httpupgrade" -> put("httpupgradeSettings", wsLikeSettings(profile))
            "xhttp" -> put("xhttpSettings", xhttpSettings(profile))
            "grpc" -> put("grpcSettings", JSONObject().apply {
                put("serviceName", profile.serviceName)
                put("multiMode", profile.mode.equals("multi", ignoreCase = true))
                if (profile.fingerprint.isNotBlank()) put("fingerprint", profile.fingerprint)
            })
            "http" -> put("httpSettings", JSONObject().apply {
                put("path", profile.path)
                hostHeader(profile)?.let { put("host", JSONArray().put(it)) }
            })
            "tcp" -> put("tcpSettings", JSONObject().apply {
                put("header", JSONObject().put("type", profile.headerType))
            })
        }

        when {
            profile.isReality -> put("realitySettings", JSONObject().apply {
                put("serverName", profile.sni)
                put("fingerprint", profile.fingerprint.ifBlank { "chrome" })
                put("publicKey", profile.publicKey)
                put("shortId", profile.shortId)
                put("spiderX", profile.spiderX)
            })

            profile.isTls -> put("tlsSettings", JSONObject().apply {
                put("serverName", profile.sni)
                put("allowInsecure", profile.allowInsecure)
                if (profile.alpn.isNotEmpty()) put("alpn", JSONArray(profile.alpn))
            })
        }
    }

    private fun wsLikeSettings(profile: VlessProfile): JSONObject = JSONObject().apply {
        put("path", profile.path)
        hostHeader(profile)?.let { put("headers", JSONObject().put("Host", it)) }
    }

    /**
     * XHTTP требует явный `mode` (иначе ядро не сможет откатиться на другой
     * транспорт) и Host-заголовок; для REALITY он должен совпадать с SNI.
     */
    private fun xhttpSettings(profile: VlessProfile): JSONObject = JSONObject().apply {
        put("path", profile.path)
        val host = profile.hostHeader.ifBlank { profile.sni }
        if (host.isNotBlank()) put("host", host)
        put("mode", profile.mode.lowercase(Locale.ROOT).ifBlank { "auto" })
    }

    private fun hostHeader(profile: VlessProfile): String? =
        profile.hostHeader.ifBlank { null }

    /**
     * Порядок правил важен: первое совпадение выигрывает.
     *
     * 1. Приватные сети — напрямую.
     * 2. DNS клиента (порт 53) — напрямую, потому что VLESS не перевозит UDP,
     *    и запросы к 1.1.1.1/8.8.8.8 из TUN иначе отбрасываются.
     * 3. Весь остальной UDP (QUIC, WebRTC) — напрямую по той же причине.
     * 4. Всё остальное (TCP) — через VLESS-прокси.
     */
    private fun routing(): JSONObject = JSONObject().apply {
        put("domainStrategy", "IPIfNonMatch")
        put("rules", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("ip", JSONArray(PRIVATE_CIDRS))
            })
            put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("port", "53")
            })
            put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("network", "udp")
            })
        })
    }

    private fun dns(): JSONObject = JSONObject().apply {
        put("servers", JSONArray(DNS_SERVERS))
        put("queryStrategy", "UseIP")
    }
}