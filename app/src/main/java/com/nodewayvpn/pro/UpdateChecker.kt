package com.nodetun.vpn

import android.content.Context
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка обновлений приложения через GitHub Releases API.
 *
 * Запрос: GET https://api.github.com/repos/hl2mp/nodeway-vpn/releases/latest
 * Ответ содержит tag_name, assets[].browser_download_url, assets[].name, assets[].size, body.
 *
 * Результат сохраняется в [Prefs] и кешируется на 24 часа.
 */
object UpdateChecker {

    private const val GITHUB_API = "https://api.github.com/repos/hl2mp/nodeway-vpn/releases/latest"

    /**
     * Проверяет наличие новой версии.
     *
     * Вызывается из фона (не из UI-потока). Возвращает true, если найдена новая версия.
     * Если проверка не нужна (кеш 24 часа) или произошла ошибка — false.
     *
     * @param force если true — игнорировать кеш 24 часа (ручная проверка из настроек).
     */
    fun checkForUpdates(context: Context, force: Boolean = false): Boolean {
        val prefs = Prefs(context)
        if (!force && !prefs.shouldCheckUpdates()) return false

        return try {
            val json = fetchJson(GITHUB_API)
            val tagName = json.optString("tag_name", "")
            val version = tagName.removePrefix("v")

            // Сравниваем с текущей версией
            val current = BuildConfig.VERSION_NAME
            if (!isNewer(version, current)) {
                prefs.updateLastCheck = System.currentTimeMillis()
                prefs.updateAvailable = false
                false
            } else {
                // Ищем APK в assets
                val assets = json.optJSONArray("assets")
                var downloadUrl = ""
                var fileName = ""
                var fileSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            fileName = name
                            fileSize = asset.optLong("size", 0)
                            break
                        }
                    }
                }

                // Не обновляем updateLastCheck при найденном обновлении:
                // чтобы автоматическая проверка через 24 ч могла сработать снова,
                // а пользователь мог повторить ручную проверку в настройках.
                prefs.updateAvailable = true
                prefs.updateVersion = version
                prefs.updateDownloadUrl = downloadUrl
                prefs.updateFileName = fileName
                prefs.updateFileSize = fileSize
                prefs.updateNotes = json.optString("body", "")
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun fetchJson(urlString: String): JSONObject {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "NodeTun-App")
        }

        return try {
            connection.inputStream.use { stream ->
                val string = stream.bufferedReader().readText()
                JSONObject(string)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Сравнивает версии формата major.minor.patch.
     * Возвращает true, если newVersion строго больше currentVersion.
     */
    private fun isNewer(newVersion: String, currentVersion: String): Boolean {
        val newParts = newVersion.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(newParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val n = if (i < newParts.size) newParts[i] else 0
            val c = if (i < currentParts.size) currentParts[i] else 0
            if (n != c) return n > c
        }
        return false
    }
}
