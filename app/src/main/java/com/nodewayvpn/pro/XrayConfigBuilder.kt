package com.nodetun.vpn

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Builds a complete Xray-core configuration for the embedded core.
 */
object XrayConfigBuilder {

    private const val MTU = 1500
    private const val TUN_NAME = "nodeway0"

    val DEFAULT_LOG_LEVEL = if (BuildConfig.DEBUG) "info" else "warning"
    const val LOG_LEVEL_NONE = "none"

    val DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")
    private val DNS_SERVERS_INTERNAL = listOf("tcp://1.1.1.1", "tcp://8.8.8.8")

    private val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
        "169.254.0.0/16", "224.0.0.0/4", "::1/128", "fc00::/7", "fe80::/10",
    )

    // ---- build overloads ----

    fun build(profile: VlessProfile, tunFd: Int, logLevel: String = DEFAULT_LOG_LEVEL, logFile: String? = null): String =
        assemble(tunFd, logLevel, logFile, vlessOutbound(profile), routing(), dns())

    fun build(profile: VmessProfile, tunFd: Int, logLevel: String = DEFAULT_LOG_LEVEL, logFile: String? = null): String =
        assemble(tunFd, logLevel, logFile, vmessOutbound(profile), routing(), dns())

    fun build(profile: TrojanProfile, tunFd: Int, logLevel: String = DEFAULT_LOG_LEVEL, logFile: String? = null): String =
        assemble(tunFd, logLevel, logFile, trojanOutbound(profile), routing(), dns())

    fun build(profile: ShadowsocksProfile, tunFd: Int, logLevel: String = DEFAULT_LOG_LEVEL, logFile: String? = null): String =
        assemble(tunFd, logLevel, logFile, shadowsocksOutbound(profile), routing(), dns())

    fun build(profile: SocksProfile, tunFd: Int, logLevel: String = DEFAULT_LOG_LEVEL, logFile: String? = null): String =
        assemble(tunFd, logLevel, logFile, socksOutbound(profile), routing(), dns())

    fun buildForOlcrtc(socksPort: Int, tunFd: Int, logLevel: String = DEFAULT_LOG_LEVEL, logFile: String? = null): String =
        assemble(tunFd, logLevel, logFile, socksOutbound(socksPort), olcrtcRouting(), olcrtcDns())

    fun buildForSocks5Vless(profile: VlessProfile, socksPort: Int, logLevel: String = DEFAULT_LOG_LEVEL, logFile: String? = null): String =
        assembleSocks5Inbound(socksPort, logLevel, logFile, vlessOutbound(profile), routing(domainStrategy = "AsIs"), dns())

    fun buildForSocks5(profile: VmessProfile, socksPort: Int, logLevel: String = DEFAULT_LOG_LEVEL, logFile: String? = null): String =
        assembleSocks5Inbound(socksPort, logLevel, logFile, vmessOutbound(profile), routing(domainStrategy = "AsIs"), dns())

    fun buildForSocks5(profile: TrojanProfile, socksPort: Int, logLevel: String = DEFAULT_LOG_LEVEL, logFile: String? = null): String =
        assembleSocks5Inbound(socksPort, logLevel, logFile, trojanOutbound(profile), routing(domainStrategy = "AsIs"), dns())

    fun buildForSocks5(profile: ShadowsocksProfile, socksPort: Int, logLevel: String = DEFAULT_LOG_LEVEL, logFile: String? = null): String =
        assembleSocks5Inbound(socksPort, logLevel, logFile, shadowsocksOutbound(profile), routing(domainStrategy = "AsIs"), dns())

    fun buildForSocks5(profile: SocksProfile, socksPort: Int, logLevel: String = DEFAULT_LOG_LEVEL, logFile: String? = null): String =
        assembleSocks5Inbound(socksPort, logLevel, logFile, socksOutbound(profile), routing(domainStrategy = "AsIs"), dns())

    // ---- internals ----

    private fun assemble(tunFd: Int, logLevel: String, logFile: String?, outbound: JSONObject, routing: JSONObject, dns: JSONObject): String {
        val config = JSONObject()
        config.put("env", JSONObject().put("xray.tun.fd", tunFd.toString()))
        config.put("log", logConfig(logLevel, logFile))
        config.put("inbounds", JSONArray().put(tunInbound()))
        config.put("outbounds", JSONArray().put(outbound).put(freedomOutbound()).put(dnsOutbound()))
        config.put("routing", routing)
        config.put("dns", dns)
        return config.toString()
    }

    private fun assembleSocks5Inbound(socksPort: Int, logLevel: String, logFile: String?, outbound: JSONObject, routing: JSONObject, dns: JSONObject): String {
        val config = JSONObject()
        config.put("log", logConfig(logLevel, logFile))
        config.put("inbounds", JSONArray().put(socksInbound(socksPort)))
        config.put("outbounds", JSONArray().put(outbound).put(freedomOutbound()).put(dnsOutbound()))
        config.put("routing", routing)
        config.put("dns", dns)
        return config.toString()
    }

    private fun socksInbound(port: Int): JSONObject = JSONObject().apply {
        put("tag", "socks-in")
        put("port", port)
        put("listen", "127.0.0.1")
        put("protocol", "socks")
        put("settings", JSONObject().apply {
            put("auth", "noauth")
            put("udp", true)
        })
    }

    private fun logConfig(logLevel: String, logFile: String?): JSONObject =
        JSONObject().put("loglevel", logLevel).apply {
            if (!logFile.isNullOrBlank()) put("error", logFile)
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
                    if (profile.flow.isNotBlank()) put("flow", profile.flow)
                }))
            }))
        })
        put("streamSettings", streamSettings(
            network = profile.network,
            security = profile.security,
            sni = profile.sni,
            alpn = profile.alpn,
            fingerprint = profile.fingerprint,
            allowInsecure = profile.allowInsecure,
            hostHeader = profile.hostHeader,
            path = profile.path,
            serviceName = profile.serviceName,
            mode = profile.mode,
            headerType = profile.headerType,
            flow = profile.flow,
            publicKey = profile.publicKey,
            shortId = profile.shortId,
            spiderX = profile.spiderX,
        ))
        put("mux", JSONObject().put("enabled", false).put("concurrency", -1))
    }

    private fun vmessOutbound(profile: VmessProfile): JSONObject = JSONObject().apply {
        put("tag", "proxy")
        put("protocol", "vmess")
        put("settings", JSONObject().apply {
            put("vnext", JSONArray().put(JSONObject().apply {
                put("address", profile.address)
                put("port", profile.port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", profile.id)
                    put("alterId", profile.alterId)
                    put("security", profile.vmessSecurity)
                }))
            }))
        })
        put("streamSettings", streamSettings(
            network = profile.network,
            security = profile.security,
            sni = profile.sni,
            alpn = profile.alpn,
            fingerprint = profile.fingerprint,
            allowInsecure = profile.allowInsecure,
            hostHeader = profile.hostHeader,
            path = profile.path,
            serviceName = profile.serviceName,
            mode = profile.mode,
            headerType = profile.headerType,
            flow = "",
        ))
        put("mux", JSONObject().put("enabled", false).put("concurrency", -1))
    }

    private fun trojanOutbound(profile: TrojanProfile): JSONObject = JSONObject().apply {
        put("tag", "proxy")
        put("protocol", "trojan")
        put("settings", JSONObject().apply {
            put("servers", JSONArray().put(JSONObject().apply {
                put("address", profile.address)
                put("port", profile.port)
                put("password", profile.password)
            }))
        })
        put("streamSettings", streamSettings(
            network = profile.network,
            security = profile.security,
            sni = profile.sni,
            alpn = profile.alpn,
            fingerprint = profile.fingerprint,
            allowInsecure = profile.allowInsecure,
            hostHeader = profile.hostHeader,
            path = profile.path,
            serviceName = profile.serviceName,
            mode = profile.mode,
            headerType = profile.headerType,
            flow = "",
        ))
        put("mux", JSONObject().put("enabled", false).put("concurrency", -1))
    }

    private fun shadowsocksOutbound(profile: ShadowsocksProfile): JSONObject = JSONObject().apply {
        put("tag", "proxy")
        put("protocol", "shadowsocks")
        put("settings", JSONObject().apply {
            put("servers", JSONArray().put(JSONObject().apply {
                put("address", profile.address)
                put("port", profile.port)
                put("method", profile.method)
                put("password", profile.password)
            }))
        })
        put("streamSettings", streamSettings(
            network = profile.network,
            security = "none",
            sni = profile.sni,
            alpn = emptyList(),
            fingerprint = "",
            allowInsecure = false,
            hostHeader = profile.hostHeader,
            path = profile.path,
            serviceName = "",
            mode = "",
            headerType = "none",
            flow = "",
        ))
        put("mux", JSONObject().put("enabled", false).put("concurrency", -1))
    }

    private fun socksOutbound(profile: SocksProfile): JSONObject = JSONObject().apply {
        put("tag", "proxy")
        put("protocol", "socks")
        put("settings", JSONObject().apply {
            put("servers", JSONArray().put(JSONObject().apply {
                put("address", profile.address)
                put("port", profile.port)
                if (profile.username.isNotBlank()) put("users", JSONArray().put(JSONObject().apply {
                    put("user", profile.username)
                    put("pass", profile.password)
                }))
            }))
        })
        put("mux", JSONObject().put("enabled", false).put("concurrency", -1))
    }

    private fun socksOutbound(port: Int): JSONObject = JSONObject().apply {
        put("tag", "proxy")
        put("protocol", "socks")
        put("settings", JSONObject().apply {
            put("servers", JSONArray().put(JSONObject().apply {
                put("address", "127.0.0.1")
                put("port", port)
            }))
        })
        put("mux", JSONObject().put("enabled", false).put("concurrency", -1))
    }

    private fun freedomOutbound(): JSONObject = JSONObject().apply {
        put("tag", "direct")
        put("protocol", "freedom")
        put("streamSettings", JSONObject().apply {
            put("sockopt", JSONObject().put("domainStrategy", "UseIP"))
        })
    }

    private fun dnsOutbound(): JSONObject = JSONObject().apply {
        put("tag", "dns-out")
        put("protocol", "dns")
    }

    private fun streamSettings(
        network: String,
        security: String,
        sni: String,
        alpn: List<String>,
        fingerprint: String,
        allowInsecure: Boolean,
        hostHeader: String,
        path: String,
        serviceName: String,
        mode: String,
        headerType: String,
        flow: String,
        publicKey: String = "",
        shortId: String = "",
        spiderX: String = "/",
    ): JSONObject = JSONObject().apply {
        put("network", network)
        put("security", security)

        when (network) {
            "ws" -> put("wsSettings", wsLikeSettings(path, hostHeader))
            "httpupgrade" -> put("httpupgradeSettings", wsLikeSettings(path, hostHeader))
            "xhttp" -> put("xhttpSettings", xhttpSettings(path, hostHeader, sni, mode))
            "grpc" -> put("grpcSettings", JSONObject().apply {
                put("serviceName", serviceName)
                put("multiMode", mode.equals("multi", ignoreCase = true))
                if (fingerprint.isNotBlank()) put("fingerprint", fingerprint)
            })
            "http" -> put("httpSettings", JSONObject().apply {
                put("path", path)
                if (hostHeader.isNotBlank()) put("host", JSONArray().put(hostHeader))
            })
            "tcp", "raw" -> put("tcpSettings", JSONObject().apply {
                put("header", JSONObject().put("type", headerType))
            })
        }

        val isReality = security.equals("reality", ignoreCase = true)
        val isTls = security.equals("tls", ignoreCase = true)
        when {
            isReality -> put("realitySettings", JSONObject().apply {
                put("serverName", sni)
                put("fingerprint", fingerprint.ifBlank { "chrome" })
                put("publicKey", publicKey)
                put("shortId", shortId)
                put("spiderX", spiderX)
            })
            isTls -> put("tlsSettings", JSONObject().apply {
                put("serverName", sni)
                put("allowInsecure", allowInsecure)
                if (alpn.isNotEmpty()) put("alpn", JSONArray(alpn))
                if (fingerprint.isNotBlank()) put("fingerprint", fingerprint)
            })
        }
    }

    private fun wsLikeSettings(path: String, hostHeader: String): JSONObject = JSONObject().apply {
        put("path", path)
        if (hostHeader.isNotBlank()) put("headers", JSONObject().put("Host", hostHeader))
    }

    private fun xhttpSettings(path: String, hostHeader: String, sni: String, mode: String): JSONObject = JSONObject().apply {
        put("path", path)
        val host = hostHeader.ifBlank { sni }
        if (host.isNotBlank()) put("host", host)
        put("mode", mode.lowercase(Locale.ROOT).ifBlank { "auto" })
    }

    private fun routing(domainStrategy: String = "IPIfNonMatch"): JSONObject = JSONObject().apply {
        put("domainStrategy", domainStrategy)
        put("rules", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("ip", JSONArray(PRIVATE_CIDRS))
            })
            put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().put("tun"))
                put("outboundTag", "dns-out")
                put("port", "53")
                put("network", "udp,tcp")
            })
        })
    }

    private fun dns(): JSONObject = JSONObject().apply {
        put("servers", JSONArray(DNS_SERVERS_INTERNAL))
        put("queryStrategy", "UseIPv4")
        put("hosts", JSONObject().apply {
            put("one.one.one.one", JSONArray().put("1.1.1.1"))
            put("dns.google", JSONArray().put("8.8.8.8"))
        })
    }

    private fun olcrtcRouting(): JSONObject = JSONObject().apply {
        put("domainStrategy", "IPIfNonMatch")
        put("rules", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("ip", JSONArray(PRIVATE_CIDRS))
            })
            put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().put("tun"))
                put("outboundTag", "dns-out")
                put("port", "53")
                put("network", "udp,tcp")
            })
            put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("network", "udp")
            })
        })
    }

    private fun olcrtcDns(): JSONObject = JSONObject().apply {
        put("servers", JSONArray(DNS_SERVERS.map { "tcp://$it" }))
        put("queryStrategy", "UseIPv4")
    }
}