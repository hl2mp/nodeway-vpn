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
    val DEFAULT_LOG_LEVEL = if (BuildConfig.DEBUG) "info" else "warning"

    /** Полное молчание: ядро не пишет ни в файл, ни в logcat. */
    const val LOG_LEVEL_NONE = "none"

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

    fun build(
        profile: VlessProfile,
        tunFd: Int,
        logLevel: String = DEFAULT_LOG_LEVEL,
        logFile: String? = null,
    ): String = assemble(tunFd, logLevel, logFile, vlessOutbound(profile), routing(), dns())

    /**
     * Конфиг для olcrtc: Xray не знает протокол, поэтому роль апстрима играет
     * локальный SOCKS5 от отдельного процесса olcrtc.
     */
    fun buildForOlcrtc(
        socksPort: Int,
        tunFd: Int,
        logLevel: String = DEFAULT_LOG_LEVEL,
        logFile: String? = null,
    ): String =
        assemble(tunFd, logLevel, logFile, socksOutbound(socksPort), olcrtcRouting(), olcrtcDns())

    /**
     * Конфиг для быстрой проверки VLESS-профиля через локальный SOCKS5.
     *
     * Вместо TUN-inbound используем SOCKS5-inbound на localhost.
     * Это позволяет мерять пинг БЕЗ поднятия VPN-интерфейса и без
     * разрешения пользователя на VPN.
     *
     * @param socksPort порт, на котором Xray будет слушать SOCKS5 (например, 10809)
     * @return JSON-конфиг для Xray-core
     */
    fun buildForSocks5Vless(
        profile: VlessProfile,
        socksPort: Int,
        logLevel: String = DEFAULT_LOG_LEVEL,
        logFile: String? = null,
    ): String = assembleSocks5Inbound(socksPort, logLevel, logFile, vlessOutbound(profile), routing(), dns())

    /**
     * Собирает конфиг с SOCKS5-inbound (вместо TUN).
     * Используется для проверки профилей без VPN.
     */
    private fun assembleSocks5Inbound(
        socksPort: Int,
        logLevel: String,
        logFile: String?,
        outbound: JSONObject,
        routing: JSONObject,
        dns: JSONObject,
    ): String {
        val config = JSONObject()

        config.put("log", logConfig(logLevel, logFile))

        config.put("inbounds", JSONArray().put(socksInbound(socksPort)))
        config.put("outbounds", JSONArray().put(outbound).put(freedomOutbound()))
        config.put("routing", routing)
        config.put("dns", dns)

        return config.toString()
    }

    /**
     * SOCKS5 inbound на localhost для принятия трафика от TunnelProbe.
     * auth: "noauth" — без аутентификации, так как это локальный loopback.
     * udp: true — на случай, если понадобится UDP через прокси.
     */
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

    private fun assemble(
        tunFd: Int,
        logLevel: String,
        logFile: String?,
        outbound: JSONObject,
        routing: JSONObject,
        dns: JSONObject,
    ): String {
        val config = JSONObject()

        // Xray-core applies root level "env" entries with os.Setenv() before building
        // the config; proxy/tun/tun_android.go reads the descriptor from "xray.tun.fd".
        config.put("env", JSONObject().put("xray.tun.fd", tunFd.toString()))

        config.put("log", logConfig(logLevel, logFile))

        config.put("inbounds", JSONArray().put(tunInbound()))
        config.put("outbounds", JSONArray().put(outbound).put(freedomOutbound()))
        config.put("routing", routing)
        config.put("dns", dns)

        return config.toString()
    }

    /**
     * Секция `log`.
     *
     * Поле `error` отдаёт ядру файл, который приложение читает и показывает в журнале.
     * Приёмника логов в AAR нет: gomobile сам пишет вывод Go только в logcat,
     * откуда его достать штатно нельзя — чтение logcat требует привилегий системы.
     *
     * Уровень `none` вместе с пустым путём — это выключенный журнал из настроек:
     * ядро молчит, файл не создаётся, читать нечего.
     */
    private fun logConfig(logLevel: String, logFile: String?): JSONObject =
        JSONObject().put("loglevel", logLevel).apply {
            if (!logFile.isNullOrBlank()) put("error", logFile)
        }

    /**
     * SOCKS5-апстрим на loopback. Настройки стрима нет — SOCKS уже терминирует
     * TCP, а UDP через него не ходит в принципе.
     */
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

        // `raw` — новое имя того же TCP-транспорта (начиная с Xray 24.9.30), старые
        // клиенты присылают `tcp`. Обе строки обязаны вести в одно место: без `raw`
        // блок транспорта просто не попал бы в конфиг, и обфускация headerType
        // потерялась бы молча.
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
            "tcp", "raw" -> put("tcpSettings", JSONObject().apply {
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
                // uTLS-отпечаток живёт именно здесь, внутри tlsSettings. На верхнем
                // уровне streamSettings такого поля нет, и Go молча выбрасывает
                // неизвестный ключ: ядро уходит в TLS без маскировки, и Cloudflare
                // режет рукопожатие — хотя тот же профиль на REALITY работает,
                // потому что там отпечаток клался в правильный блок.
                if (profile.fingerprint.isNotBlank()) put("fingerprint", profile.fingerprint)
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

    /**
     * Роутинг для olcrtc.
     *
     * Отличие от VLESS — прямой DNS не годится: сеть, где работает WebRTC-туннель,
     * обычно блокирует и его тоже. Поэтому DNS уходит в туннель (см. [olcrtcDns]),
     * приватные сети идут напрямую, остальное — в туннель.
     *
     * UDP при этом остаётся прямым. SOCKS5 UDP не перевозит, и если завернуть его
     * в туннель, резолвер начнёт сыпать ошибками, а QUIC-запросы — падать без
     * шанса на TCP-фолбэк. Для обхода блокировок достаточно TCP.
     */
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
                put("outboundTag", "direct")
                put("network", "udp")
            })
        })
    }

    /**
     * DNS для olcrtc идёт через туннель, поэтому только TCP: UDP-резолвер
     * через SOCKS работать не будет.
     */
    private fun olcrtcDns(): JSONObject = JSONObject().apply {
        put("servers", JSONArray(DNS_SERVERS.map { "tcp+$it" }))
        put("queryStrategy", "UseIP")
    }
}