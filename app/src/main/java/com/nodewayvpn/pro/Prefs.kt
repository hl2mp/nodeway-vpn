package com.nodewayvpn.pro

import android.content.Context

/** Небольшое хранилище настроек: режим туннелирования, список приложений, журнал, тема. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Режим раздельного туннелирования: "all", "allow" или "exclude". */
    var tunnelMode: String
        get() = sp.getString(KEY_TUNNEL_MODE, TUNNEL_MODE_ALL).orEmpty().ifBlank { TUNNEL_MODE_ALL }
        set(value) = sp.edit().putString(KEY_TUNNEL_MODE, value).apply()

    /**
     * Пакеты, выбранные пользователем для раздельного туннелирования.
     *
     * Возвращается копия: `getStringSet` отдаёт живой сет, который нельзя мутировать.
     */
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

    /**
     * Выбор темы оформления: "system", "light" или "dark".
     *
     * По умолчанию "system": тема системы меняется вместе с настройками устройства,
     * и отдельный переключатель в приложении для этого не нужен. Явный выбор
     * пользователя перекрывает систему — за тем, чтобы ночью не отвлекал светлый
     * экран, даже если телефон в светлой теме.
     */
    var themeMode: String
        get() = sp.getString(KEY_THEME, THEME_SYSTEM).orEmpty().ifBlank { THEME_SYSTEM }
        set(value) = sp.edit().putString(KEY_THEME, value).apply()

    private companion object {
        const val NAME = "nodeway_vpn"
        const val KEY_TUNNEL_MODE = "tunnel_mode"
        const val KEY_SPLIT_PACKAGES = "split_packages"
        const val KEY_JOURNAL = "journal_enabled"
        const val KEY_THEME = "theme_mode"
        const val TUNNEL_MODE_ALL = "all"
        const val THEME_SYSTEM = "system"
    }
}