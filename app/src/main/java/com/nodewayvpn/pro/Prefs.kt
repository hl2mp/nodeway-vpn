package com.nodewayvpn.pro

import android.content.Context

/**
 * Tiny persistent store for the user's VLESS link.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var lastLink: String
        get() = sp.getString(KEY_LINK, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LINK, value.trim()).apply()

    /** Режим раздельного туннелирования: "all", "allow" или "exclude". */
    // ai-generated
    var tunnelMode: String
        get() = sp.getString(KEY_TUNNEL_MODE, TUNNEL_MODE_ALL).orEmpty().ifBlank { TUNNEL_MODE_ALL }
        set(value) = sp.edit().putString(KEY_TUNNEL_MODE, value).apply()

    /**
     * Пакеты, выбранные пользователем для раздельного туннелирования.
     *
     * Возвращается копия: `getStringSet` отдаёт живой сет, который нельзя мутировать.
     */
    // ai-generated
    var splitPackages: Set<String>
        get() = LinkedHashSet(sp.getStringSet(KEY_SPLIT_PACKAGES, emptySet()).orEmpty())
        set(value) = sp.edit().putStringSet(KEY_SPLIT_PACKAGES, LinkedHashSet(value)).apply()

    /**
     * Вести ли журнал: вывод ядер и события подключения.
     *
     * По умолчанию выключено — журнал нужен при разборе проблем, а не постоянно.
     * Выключается и карточка журнала, и запись в logcat приложения не меняется:
     * logcat кольцевой и бесплатный, в отличие от файла, который ядро пишет само.
     */
    var journalEnabled: Boolean
        get() = sp.getBoolean(KEY_JOURNAL, false)
        set(value) = sp.edit().putBoolean(KEY_JOURNAL, value).apply()

    private companion object {
        const val NAME = "nodeway_vpn"
        const val KEY_LINK = "last_link"
        const val KEY_TUNNEL_MODE = "tunnel_mode"
        const val KEY_SPLIT_PACKAGES = "split_packages"
        const val KEY_JOURNAL = "journal_enabled"
        const val TUNNEL_MODE_ALL = "all"
    }
}