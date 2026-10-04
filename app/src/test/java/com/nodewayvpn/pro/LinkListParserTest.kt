package com.nodewayvpn.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkListParserTest {

    private val key = "23611390568299b7da91472b6c78edf994a784b881626e4b36a2fd8e1df8048c"

    /** Реальный формат панели: директивы и все ссылки в одной строке через пробел. */
    private val panelResponse =
        "#support-url: https://t.me/nodeway_bot #name: Nodeway - VPN #refresh: 1h " +
            "vless://073e17e9-dd90-43dc-a518-1ce156b197b7@jeri.hl2mp.ru:443" +
            "?encryption=none&security=tls&type=tcp#CF%20%D0%9E%D1%81%D0%BD%D0%BE%D0%B2%D0%BD%D0%BE%D0%B9 " +
            "olcrtc://wbstream?vp8channel@hl2mpru#$key\$UK Обход списков (WB) " +
            "olcrtc://telemost?vp8channel@07339722921845#$key\$RU Обход списков (YA) " +
            "olcrtc://jitsi?datachannel@https://meet.egovm.ru/hl2mpru#$key\$UK Обход списков (RT)"

    @Test
    fun `панельная подписка отдаёт vless и olcrtc вместе`() {
        val parsed = LinkListParser.parse(panelResponse)

        assertEquals(4, parsed.supported.size)
        assertEquals(1, parsed.links.count { it.scheme == "vless" })
        assertEquals(3, parsed.links.count { it.scheme == "olcrtc" })
        assertTrue(parsed.unsupported.isEmpty())
    }

    @Test
    fun `заголовок и интервал обновления читаются, а имя подписки не затирается`() {
        val parsed = LinkListParser.parse(panelResponse)

        assertEquals("Nodeway - VPN", parsed.title)
        assertEquals(60, parsed.refreshMinutes)
        assertEquals("https://t.me/nodeway_bot", parsed.supportUrl)
    }

    @Test
    fun `MIMO-комментарий с пробелами остаётся частью ссылки`() {
        val parsed = LinkListParser.parse(panelResponse)
        val olcrtc = parsed.supported.filter { it.scheme == "olcrtc" }

        assertEquals(
            listOf(
                "UK Обход списков (WB)",
                "RU Обход списков (YA)",
                "UK Обход списков (RT)",
            ),
            olcrtc.map { OlcrtcProfile.parse(it.raw).remark },
        )
    }

    @Test
    fun `буфер обмена с одной olcrtc-ссылкой разбирается целиком`() {
        val parsed = LinkListParser.parse("olcrtc://jitsi?datachannel@room-01#$key\$Тестовый узел")

        assertEquals(1, parsed.supported.size)
        val profile = OlcrtcProfile.parse(parsed.supported.single().raw)
        assertEquals("jitsi", profile.provider)
        assertEquals("datachannel", profile.transport)
        assertEquals("room-01", profile.room)
        assertEquals("Тестовый узел", profile.remark)
    }

    @Test
    fun `локальное поле ##name не перезаписывает заголовок подписки`() {
        val body = """
            #name: Zarazaex Free RU
            #refresh: 10m

            olcrtc://jitsi?datachannel@room-01#$key
            ##name: RU-1
            ##comment: basic free node

            olcrtc://jitsi?datachannel@room-02#$key
            ##name: RU-2
        """.trimIndent()

        val parsed = LinkListParser.parse(body)

        assertEquals("Zarazaex Free RU", parsed.title)
        assertEquals(10, parsed.refreshMinutes)
        assertEquals(2, parsed.supported.size)
        assertEquals(
            listOf("RU-1", "RU-2"),
            parsed.supported.map { parsed.serverField(it.raw, "name") },
        )
    }

    @Test
    fun `схема без тела scheme остаётся ссылкой`() {
        val parsed = LinkListParser.parse("vless://uuid@host:443#A#B vmess://host:1234")

        assertEquals(1, parsed.supported.size)
        assertEquals(1, parsed.unsupported.size)
        assertEquals("vmess", parsed.unsupported.single().scheme)
    }
}