package com.nodewayvpn.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VlessProfileTest {

    @Test
    fun `round-trip parse(toLink(parse(link))) == parse(link) для vless`() {
        val links = listOf(
            "vless://073e17e9-dd90-43dc-a518-1ce156b197b7@jeri.hl2mp.ru:443?encryption=none&security=tls&type=tcp#CF%20%D0%9E%D1%81%D0%BD%D0%BE%D0%B2%D0%BD%D0%BE%D0%B9",
            "vless://a0000000-0000-0000-0000-000000000000@1.2.3.4:8443?security=reality&sni=google.com&fp=chrome&pbk=11223344556677889900aabbccddeeff&sid=12345678&spx=/&type=grpc&serviceName=mygrpc#RealityNode",
            "vless://b0000000-0000-0000-0000-000000000000@example.com:80?type=ws&path=/ws-path&host=example.com#WsNode",
        )
        for (link in links) {
            val p1 = VlessProfile.parse(link)
            val generatedLink = p1.toLink()
            val p2 = VlessProfile.parse(generatedLink)
            assertEquals("Round-trip failed for link: $link", p1, p2)
        }
    }

    @Test
    fun `битая vless ссылка отвергается с понятным исключением`() {
        assertThrows(IllegalArgumentException::class.java) {
            VlessProfile.parse("http://example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VlessProfile.parse("vless://invalid-uuid@example.com:443")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VlessProfile.parse("vless://073e17e9-dd90-43dc-a518-1ce156b197b7@example.com")
        }
    }
}
