package com.nodewayvpn.pro

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка [XrayConfigBuilder] — сборщика конфига ядра.
 *
 * Здесь важно место каждого ключа, а не только его наличие. Ошибка с полем
 * `fingerprint` на верхнем уровне streamSettings стоила рабочих профилей с TLS:
 * ядро молча игнорирует неизвестный ключ, uTLS не включается, и Cloudflare режет
 * рукопожатие. Ядро при этом рапортовало об успешном старте.
 */
class XrayConfigBuilderTest {

    private val id = "073e17e9-dd90-43dc-a518-1ce156b197b7"

    /** Конфиг того профиля, с которым не работал TCP+TLS. */
    private val tcpTls = VlessProfile(
        id = id,
        address = "jeri.hl2mp.ru",
        port = 443,
        remark = "CF Основной (TLS)",
        security = "tls",
        sni = "jeri.hl2mp.ru",
        alpn = listOf("h2", "http/1.1"),
        fingerprint = "edge",
        network = "tcp",
        flow = "xtls-rprx-vision",
    )

    private fun stream(profile: VlessProfile): JSONObject {
        val config = JSONObject(XrayConfigBuilder.build(profile, 7, "warning", null))
        return config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
    }

    @Test
    fun `uTLS-отпечаток лежит внутри tlsSettings`() {
        val tls = stream(tcpTls).getJSONObject("tlsSettings")

        assertEquals("edge", tls.getString("fingerprint"))
    }

    @Test
    fun `на верхнем уровне streamSettings отпечатка нет`() {
        // Регрессия: ключ кладётся в streamSettings, где такого поля не существует.
        // Go выбрасывает его молча, и профиль не работает без объяснений.
        assertFalse(stream(tcpTls).has("fingerprint"))
    }

    @Test
    fun `REALITY-профиль тоже не оставляет отпечаток наверху`() {
        val reality = VlessProfile(
            id = id,
            address = "example.com",
            port = 443,
            remark = "Reality",
            security = "reality",
            sni = "www.microsoft.com",
            fingerprint = "chrome",
            publicKey = "PUBLICKEY",
            shortId = "abcd",
            network = "xhttp",
            path = "/",
            mode = "auto",
        )

        val settings = stream(reality)

        assertFalse(settings.has("fingerprint"))
        assertEquals("chrome", settings.getJSONObject("realitySettings").getString("fingerprint"))
    }

    @Test
    fun `SNI и ALPN доезжают до tlsSettings`() {
        val tls = stream(tcpTls).getJSONObject("tlsSettings")
        val alpn = tls.getJSONArray("alpn")

        assertEquals("jeri.hl2mp.ru", tls.getString("serverName"))
        assertEquals(listOf("h2", "http/1.1"), (0 until alpn.length()).map { alpn.getString(it) })
    }

    @Test
    fun `Vision доезжает до пользователя`() {
        val config = JSONObject(XrayConfigBuilder.build(tcpTls, 7, "warning", null))
        val user = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0)

        assertEquals("xtls-rprx-vision", user.getString("flow"))
    }

    @Test
    fun `транспорт tcp собирает настройки`() {
        assertTrue(stream(tcpTls).has("tcpSettings"))
    }

    @Test
    fun `транспорт raw это тот же tcp`() {
        // Начиная с Xray 24.9.30 новые клиенты пишут type=raw. Без этой ветки
        // блок транспорта не попал бы в конфиг вообще.
        assertTrue(stream(tcpTls.copy(network = "raw")).has("tcpSettings"))
    }

    @Test
    fun `обфускация headerType доезжает до транспорта`() {
        val header = stream(tcpTls.copy(headerType = "http"))
            .getJSONObject("tcpSettings").getJSONObject("header")

        assertEquals("http", header.getString("type"))
    }

    @Test
    fun `без шифрования tlsSettings не появляется`() {
        val plain = tcpTls.copy(security = "none", sni = "", alpn = emptyList(), fingerprint = "")
        val settings = stream(plain)

        assertFalse(settings.has("tlsSettings"))
        assertFalse(settings.has("realitySettings"))
    }

    @Test
    fun `приватные сети и UDP уходят напрямую`() {
        val config = JSONObject(XrayConfigBuilder.build(tcpTls, 7, "warning", null))
        val rules: JSONArray = config.getJSONObject("routing").getJSONArray("rules")
        val direct = (0 until rules.length()).map { rules.getJSONObject(it) }

        assertTrue(direct.any { it.has("ip") && it.getString("outboundTag") == "direct" })
        assertTrue(direct.any { it.optString("network") == "udp" })
    }
}