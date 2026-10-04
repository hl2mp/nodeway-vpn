package com.nodewayvpn.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OlcrtcProfileTest {

    private val key = "23611390568299b7da91472b6c78edf994a784b881626e4b36a2fd8e1df8048c"

    @Test
    fun `комната-URL с двоеточием и слэшами сохраняется целиком`() {
        val profile = OlcrtcProfile.parse("olcrtc://jitsi?datachannel@https://meet.egovm.ru/hl2mpru#$key\$UK")

        assertEquals("jitsi", profile.provider)
        assertEquals("datachannel", profile.transport)
        assertEquals("https://meet.egovm.ru/hl2mpru", profile.room)
        assertEquals("meet.egovm.ru/hl2mpru", profile.roomLabel)
        assertEquals("UK", profile.remark)
    }

    @Test
    fun `числовая комната telemost разбирается без схемы`() {
        val profile = OlcrtcProfile.parse("olcrtc://telemost?vp8channel@07339722921845#$key")

        assertEquals("telemost", profile.provider)
        assertEquals("07339722921845", profile.room)
        assertEquals("", profile.remark)
    }

    @Test
    fun `YAML клиента собирается по схеме из документации`() {
        val yaml = OlcrtcProfile.parse("olcrtc://jitsi?datachannel@room-01#$key")
            .toYaml("127.0.0.1", 10808)

        assertEquals(
            """
            mode: cnc
            auth:
              provider: jitsi
            room:
              id: "room-01"
            crypto:
              key: "$key"
            net:
              transport: datachannel
              dns: "8.8.8.8:53"
            socks:
              host: "127.0.0.1"
              port: 10808

            """.trimIndent(),
            yaml,
        )
    }

    @Test
    fun `параметры транспорта попадают в свой блок`() {
        val vp8 = OlcrtcProfile
            .parse("olcrtc://wbstream?vp8channel<vp8-fps=60&vp8-batch=64>@room-01#$key")
            .toYaml("127.0.0.1", 10808)

        assertTrue(vp8.contains("vp8:\n  fps: 60\n  batch_size: 64"))

        val sei = OlcrtcProfile
            .parse("olcrtc://wbstream?seichannel<fps=60&batch=64&frag=900&ack-ms=2000>@room-01#$key")
            .toYaml("127.0.0.1", 10808)

        assertTrue(sei.contains("sei:\n  fps: 60\n  batch_size: 64\n  fragment_size: 900\n  ack_timeout_ms: 2000"))

        val video = OlcrtcProfile
            .parse("olcrtc://telemost?videochannel<video-w=1080&video-h=720&video-codec=qrcode>@room-01#$key")
            .toYaml("127.0.0.1", 10808)

        assertTrue(video.contains("video:\n  width: 1080\n  height: 720\n  codec: qrcode"))
    }

    @Test
    fun `неизвестные параметры в YAML не попадают`() {
        val yaml = OlcrtcProfile
            .parse("olcrtc://jitsi?datachannel<something=else>@room-01#$key")
            .toYaml("127.0.0.1", 10808)

        // Строгий загрузчик olcrtc отвергает неизвестные поля.
        assertFalse(yaml.contains("something"))
    }

    @Test
    fun `битая ссылка отвергается с понятной причиной`() {
        assertThrows(IllegalArgumentException::class.java) {
            OlcrtcProfile.parse("olcrtc://jitsi?datachannel@room-01#short-key")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OlcrtcProfile.parse("olcrtc://unknown?datachannel@room-01#$key")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OlcrtcProfile.parse("olcrtc://jitsi?datachannel@room-01")
        }
    }
}