package com.nodewayvpn.pro

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileStoreTest {

    private fun createFakeSharedPreferences(): SharedPreferences {
        val storeMap = mutableMapOf<String, String>()
        return object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = HashMap(storeMap)
            override fun getString(key: String, defValue: String?): String? = storeMap[key] ?: defValue
            override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = null
            override fun getInt(key: String, defValue: Int): Int = storeMap[key]?.toInt() ?: defValue
            override fun getLong(key: String, defValue: Long): Long = storeMap[key]?.toLong() ?: defValue
            override fun getFloat(key: String, defValue: Float): Float = storeMap[key]?.toFloat() ?: defValue
            override fun getBoolean(key: String, defValue: Boolean): Boolean = storeMap[key]?.toBoolean() ?: defValue
            override fun contains(key: String): Boolean = storeMap.containsKey(key)
            override fun edit(): SharedPreferences.Editor = FakeEditor(storeMap)
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
    }

    private class FakeEditor(private val map: MutableMap<String, String>) : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, String?>()
        override fun putString(key: String, value: String?): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { changes[key] = value.toString(); return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String): SharedPreferences.Editor { changes[key] = null; return this }
        override fun clear(): SharedPreferences.Editor { map.clear(); return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            changes.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }

    @Test
    fun `создание нового профиля ставит источник MANUAL`() {
        val store = ProfileStore(sharedPreferences = createFakeSharedPreferences())
        val link = "vless://073e17e9-dd90-43dc-a518-1ce156b197b7@example.com:443#Test"
        val profile = store.saveProfile(id = null, link = link, name = "Мой профиль")

        assertNotNull(profile.id)
        assertEquals("Мой профиль", profile.name)
        assertEquals(link, profile.link)
        assertEquals(ServerProfile.Source.MANUAL, profile.source)
        assertNull(profile.subscriptionId)

        val retrieved = store.profile(profile.id)
        assertNotNull(retrieved)
        assertEquals("Мой профиль", retrieved?.name)
    }

    @Test
    fun `обновление профиля сохраняет subscriptionId и source`() {
        val store = ProfileStore(sharedPreferences = createFakeSharedPreferences())
        val initialLink = "vless://073e17e9-dd90-43dc-a518-1ce156b197b7@example.com:443#Old"
        store.addProfiles(
            links = listOf(initialLink),
            names = mapOf(initialLink to "Старое имя"),
            source = ServerProfile.Source.SUBSCRIPTION,
            subscriptionId = "sub-42",
        )

        val existing = store.profiles.single()
        assertEquals(ServerProfile.Source.SUBSCRIPTION, existing.source)
        assertEquals("sub-42", existing.subscriptionId)

        val updatedLink = "vless://073e17e9-dd90-43dc-a518-1ce156b197b7@new-host.com:8443#New"
        val updated = store.saveProfile(id = existing.id, link = updatedLink, name = "Новое имя")

        assertEquals(existing.id, updated.id)
        assertEquals("Новое имя", updated.name)
        assertEquals(updatedLink, updated.link)
        assertEquals(ServerProfile.Source.SUBSCRIPTION, updated.source)
        assertEquals("sub-42", updated.subscriptionId)
    }
}
