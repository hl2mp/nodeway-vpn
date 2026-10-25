package com.nodetun.vpn

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

    /**
     * Стиль темы оформления: "classic" (iOS-like) или "m3" (Material 3).
     *
     * По умолчанию "classic" — текущий дизайн. При смене стиля WebView перезагружается
     * с соответствующим HTML-файлом (index.html или theme-m3.html).
     */
    var themeStyle: String
        get() = sp.getString(KEY_THEME_STYLE, THEME_STYLE_CLASSIC).orEmpty().ifBlank { THEME_STYLE_CLASSIC }
        set(value) = sp.edit().putString(KEY_THEME_STYLE, value).apply()

    // --- Обновления ---

    /** Время последней проверки обновлений (epoch ms). */
    var updateLastCheck: Long
        get() = sp.getLong(KEY_UPDATE_LAST_CHECK, 0)
        set(value) = sp.edit().putLong(KEY_UPDATE_LAST_CHECK, value).apply()

    /** Есть ли доступная новая версия. */
    var updateAvailable: Boolean
        get() = sp.getBoolean(KEY_UPDATE_AVAILABLE, false)
        set(value) = sp.edit().putBoolean(KEY_UPDATE_AVAILABLE, value).apply()

    /** Версия из тега GitHub (например, "1.0.5"). */
    var updateVersion: String
        get() = sp.getString(KEY_UPDATE_VERSION, "").orEmpty()
        set(value) = sp.edit().putString(KEY_UPDATE_VERSION, value).apply()

    /** Ссылка на скачивание APK из GitHub Release assets. */
    var updateDownloadUrl: String
        get() = sp.getString(KEY_UPDATE_DOWNLOAD_URL, "").orEmpty()
        set(value) = sp.edit().putString(KEY_UPDATE_DOWNLOAD_URL, value).apply()

    /** Имя файла APK. */
    var updateFileName: String
        get() = sp.getString(KEY_UPDATE_FILE_NAME, "").orEmpty()
        set(value) = sp.edit().putString(KEY_UPDATE_FILE_NAME, value).apply()

    /** Размер файла в байтах. */
    var updateFileSize: Long
        get() = sp.getLong(KEY_UPDATE_FILE_SIZE, 0)
        set(value) = sp.edit().putLong(KEY_UPDATE_FILE_SIZE, value).apply()

    /** Release notes (опционально). */
    var updateNotes: String
        get() = sp.getString(KEY_UPDATE_NOTES, "").orEmpty()
        set(value) = sp.edit().putString(KEY_UPDATE_NOTES, value).apply()

    /** Нужно ли проверять обновления: true, если прошло > 24 часов с последней проверки. */
    fun shouldCheckUpdates(): Boolean = System.currentTimeMillis() - updateLastCheck > UPDATE_CHECK_INTERVAL_MS

    private companion object {
        const val NAME = "nodeway_vpn"
        const val KEY_TUNNEL_MODE = "tunnel_mode"
        const val KEY_SPLIT_PACKAGES = "split_packages"
        const val KEY_JOURNAL = "journal_enabled"
        const val KEY_THEME = "theme_mode"
        const val KEY_THEME_STYLE = "theme_style"
        const val TUNNEL_MODE_ALL = "all"
        const val THEME_SYSTEM = "system"
        const val THEME_STYLE_CLASSIC = "classic"
        const val THEME_STYLE_M3 = "m3"
        const val KEY_UPDATE_LAST_CHECK = "update_last_check"
        const val KEY_UPDATE_AVAILABLE = "update_available"
        const val KEY_UPDATE_VERSION = "update_version"
        const val KEY_UPDATE_DOWNLOAD_URL = "update_download_url"
        const val KEY_UPDATE_FILE_NAME = "update_file_name"
        const val KEY_UPDATE_FILE_SIZE = "update_file_size"
        const val KEY_UPDATE_NOTES = "update_notes"
        const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}