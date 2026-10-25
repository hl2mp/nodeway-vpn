package com.nodetun.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивание APK обновления в фоновом потоке.
 *
 * Файл сохраняется в context.cacheDir. Поддерживает отмену через volatile флаг.
 * Прогресс отдаётся через callback.
 */
class ApkDownloader(private val context: Context) {

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun isCancelled(): Boolean = cancelled

    /**
     * Скачивает APK по URL.
     *
     * @param url      ссылка на APK
     * @param fileName имя файла
     * @param onProgress вызов с процентами (0-100)
     * @param onFinish вызов с результатом File или null
     */
    fun download(
        url: String,
        fileName: String,
        onProgress: (Int) -> Unit = {},
        onFinish: (File?) -> Unit = {}
    ) {
        cancelled = false
        Thread({
            var file: File? = null
            try {
                val downloadUrl = URL(url)
                val connection = downloadUrl.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }

                val totalSize = connection.contentLength.toLong()
                val targetFile = File(context.cacheDir, fileName)

                connection.inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var lastProgress = -1

                        while (!cancelled) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            if (totalSize > 0) {
                                val percent = (downloaded * 100 / totalSize).toInt()
                                if (percent != lastProgress && percent % 5 == 0) {
                                    lastProgress = percent
                                    Handler(Looper.getMainLooper()).post { onProgress(percent) }
                                }
                            }
                        }

                        if (!cancelled) {
                            file = targetFile
                            Handler(Looper.getMainLooper()).post { onProgress(100) }
                        }
                    }
                }

                connection.disconnect()
            } catch (_: Exception) {
                // Ошибка скачивания — отдаём null
            }

            Handler(Looper.getMainLooper()).post { onFinish(file) }
        }, "apk-downloader").apply {
            isDaemon = true
            start()
        }
    }
}
