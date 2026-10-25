package com.nodewayvpn.pro

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Разбор ответа бинарника `olcrtc --version`.
 *
 * Версию ядра показывает настройка «Ядро olcrtc», а печатает её сам бинарник:
 * одной строкой `olcrtc <версия>`.
 */
class OlcrtcVersionTest {

    @Test
    fun `версия достаётся из строки бинарника`() {
        assertEquals("v0.12.0", OlcrtcProcess.parseVersion("olcrtc v0.12.0\n"))
    }

    @Test
    fun `сборка из рабочей копии сохраняет ревизию`() {
        assertEquals("(devel) 1a2b3c4d", OlcrtcProcess.parseVersion("olcrtc (devel) 1a2b3c4d\n"))
    }

    @Test
    fun `версия с пометкой правок не теряется`() {
        assertEquals(
            "(devel) 1a2b3c4d, modified",
            OlcrtcProcess.parseVersion("olcrtc (devel) 1a2b3c4d, modified\n"),
        )
    }

    @Test
    fun `пустой ответ означает неизвестную версию`() {
        assertEquals("", OlcrtcProcess.parseVersion(""))
        assertEquals("", OlcrtcProcess.parseVersion("\n\n"))
    }
}
