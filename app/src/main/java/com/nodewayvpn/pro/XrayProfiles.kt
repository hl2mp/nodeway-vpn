package com.nodetun.vpn

/**
 * Фабрика для парсинга и сборки Xray-профилей по схеме ссылки.
 */
object XrayProfiles {

    fun isXrayLink(link: String): Boolean {
        val scheme = link.trim().substringBefore("://", "").lowercase()
        return scheme in setOf("vless", "vmess", "trojan", "ss", "socks", "socks4", "socks5")
    }

    fun parse(link: String): Any = when (link.trim().substringBefore("://", "").lowercase()) {
        "vless" -> VlessProfile.parse(link)
        "vmess" -> VmessProfile.parse(link)
        "trojan" -> TrojanProfile.parse(link)
        "ss" -> ShadowsocksProfile.parse(link)
        "socks", "socks4", "socks5" -> SocksProfile.parse(link)
        else -> throw IllegalArgumentException("Неподдерживаемая схема: ${link.substringBefore("://")}")
    }

    fun describe(profile: Any): String = when (profile) {
        is VlessProfile -> profile.describe()
        is VmessProfile -> profile.describe()
        is TrojanProfile -> profile.describe()
        is ShadowsocksProfile -> profile.describe()
        is SocksProfile -> profile.describe()
        else -> ""
    }

    fun buildConfig(profile: Any, tunFd: Int, logLevel: String, logFile: String?): String = when (profile) {
        is VlessProfile -> XrayConfigBuilder.build(profile, tunFd, logLevel, logFile)
        is VmessProfile -> XrayConfigBuilder.build(profile, tunFd, logLevel, logFile)
        is TrojanProfile -> XrayConfigBuilder.build(profile, tunFd, logLevel, logFile)
        is ShadowsocksProfile -> XrayConfigBuilder.build(profile, tunFd, logLevel, logFile)
        is SocksProfile -> XrayConfigBuilder.build(profile, tunFd, logLevel, logFile)
        else -> throw IllegalArgumentException("Неизвестный тип профиля")
    }

    fun buildSocks5Config(profile: Any, socksPort: Int, logLevel: String, logFile: String?): String = when (profile) {
        is VlessProfile -> XrayConfigBuilder.buildForSocks5Vless(profile, socksPort, logLevel, logFile)
        is VmessProfile -> XrayConfigBuilder.buildForSocks5(profile, socksPort, logLevel, logFile)
        is TrojanProfile -> XrayConfigBuilder.buildForSocks5(profile, socksPort, logLevel, logFile)
        is ShadowsocksProfile -> XrayConfigBuilder.buildForSocks5(profile, socksPort, logLevel, logFile)
        is SocksProfile -> XrayConfigBuilder.buildForSocks5(profile, socksPort, logLevel, logFile)
        else -> throw IllegalArgumentException("Неизвестный тип профиля")
    }
}