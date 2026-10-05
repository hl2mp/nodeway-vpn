package com.nodewayvpn.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppIconsTest {

    @Test
    fun `имя пакета достаётся из адреса иконки`() {
        val pkg = AppIcons.packageOf("https://nodeway.internal/icon/com.example.app")

        assertEquals("com.example.app", pkg)
    }

    @Test
    fun `хост нечувствителен к регистру`() {
        val pkg = AppIcons.packageOf("https://NodeWay.Internal/icon/com.example.app")

        assertEquals("com.example.app", pkg)
    }

    @Test
    fun `имя пакета декодируется ровно один раз`() {
        // Двойное декодирование исказило бы имя, в котором встретился процент.
        val pkg = AppIcons.packageOf("https://nodeway.internal/icon/com.example.a%2520b")

        assertEquals("com.example.a%20b", pkg)
    }

    @Test
    fun `чужой адрес остаётся за WebView`() {
        assertNull(AppIcons.packageOf("https://example.com/icon/com.example.app"))
        assertNull(AppIcons.packageOf("http://nodeway.internal/icon/com.example.app"))
        assertNull(AppIcons.packageOf("https://nodeway.internal/other/com.example.app"))
    }

    @Test
    fun `пустое имя и вложенный путь не проходят`() {
        assertNull(AppIcons.packageOf("https://nodeway.internal/icon/"))
        assertNull(AppIcons.packageOf("https://nodeway.internal/icon/../../etc/passwd"))
        assertNull(AppIcons.packageOf("https://nodeway.internal/icon/com.example/extra"))
        assertNull(AppIcons.packageOf("не адрес вовсе"))
        assertNull(AppIcons.packageOf(""))
    }
}