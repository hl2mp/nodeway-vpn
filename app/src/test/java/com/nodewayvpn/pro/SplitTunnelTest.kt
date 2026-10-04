package com.nodewayvpn.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Проверка [tunnelTargets] — решения о том, какие пакеты попадут в туннель.
 *
 * Здесь важен не сам список, а решение по собственному пакету приложения: для olcrtc
 * он обязан остаться вне туннеля во всех режимах, иначе его сокеты уходят в
 * подменённый маршрут и трафик встаёт при формально успешном подключении.
 */
class SplitTunnelTest {

    private val self = "com.nodewayvpn.pro"
    private val chrome = "com.android.chrome"

    @Test
    fun `в режиме all список пуст`() {
        val targets = tunnelTargets(TunnelMode.ALL, setOf(chrome), self, includeSelf = true)

        assertEquals(emptySet<String>(), targets)
    }

    @Test
    fun `в режиме allow свой пакет попадает в туннель`() {
        val targets = tunnelTargets(TunnelMode.ALLOW, setOf(chrome), self, includeSelf = true)

        assertEquals(setOf(chrome, self), targets)
    }

    @Test
    fun `в режиме allow свой пакет уходит из разрешённых для olcrtc`() {
        // Разрешённый и запрещённый списки Android не принимает вместе, поэтому
        // исключение делается отсутствием пакета, а не вызовом addDisallowedApplication.
        val targets = tunnelTargets(TunnelMode.ALLOW, setOf(chrome), self, includeSelf = false)

        assertEquals(setOf(chrome), targets)
        assertFalse(self in targets)
    }

    @Test
    fun `olcrtc не возвращается в туннель даже если попал в список пакетов`() {
        // splitPackages() добавляет себя в режиме ALLOW, и tunnelTargets обязан это убрать.
        val targets = tunnelTargets(TunnelMode.ALLOW, setOf(chrome, self), self, includeSelf = false)

        assertEquals(setOf(chrome), targets)
    }

    @Test
    fun `в режиме exclude свой пакет всегда исключён`() {
        val targets = tunnelTargets(TunnelMode.EXCLUDE, setOf(chrome, self), self, includeSelf = true)

        assertEquals(setOf(chrome), targets)
    }

    @Test
    fun `в режиме exclude с includeSelf всё равно исключает свой пакет`() {
        val targets = tunnelTargets(TunnelMode.EXCLUDE, setOf(chrome), self, includeSelf = false)

        assertEquals(setOf(chrome), targets)
    }
}