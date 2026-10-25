package com.nodetun.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивание APK обновления в фоновом потоке.
 *
 * Файл сохраняется в context.cacheDir. Поддерживает отмену через volatile флаг.
 * При отмене соединение закрывается принудительно, чтобы чтение из потока не висело вечно.
 * Прогресс отдаётся через callback.
 */
class ApkDownloader(private val context: Context) {

    @Volatile
    private var cancelled = false

    private var connection: HttpURLConnection? = null

    /** Ссылка на поток чтения, чтобы при отмене прервать блокирующий input.read(). */
    @Volatile
    private var inputStream: InputStream? = null

    fun cancel() {
        cancelled = true
        // Закрываем поток чтения, чтобы заблокированный input.read() в потоке
        // скачивания сразу выбросил исключение и поток смог завершиться.
        val stream = inputStream
        if (stream != null) {
            try { stream.close() } catch (_: Exception) {}
        }
        // Закрываем соединение, чтобы освободить ресурсы сети.
        val conn = connection
        if (conn != null) conn.disconnect()
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
        connection = null
        inputStream = null
        Thread({
            var file: File? = null
            try {
                val downloadUrl = URL(url)
                val conn = downloadUrl.openConnection() as HttpURLConnection
                connection = conn
                conn.apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }

                val totalSize = conn.contentLength.toLong()
                val targetFile = File(context.cacheDir, fileName)

                conn.inputStream.use { input ->
                    inputStream = input
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

                        inputStream = null

                        if (!cancelled) {
                            file = targetFile
                            Handler(Looper.getMainLooper()).post { onProgress(100) }
                        }
                    }
                }

                conn.disconnect()
                connection = null
                inputStream = null
            } catch (_: Exception) {
                // Ошибка скачивания — отдаём null
            } finally {
                connection = null
            }

            Handler(Looper.getMainLooper()).post { onFinish(file) }
        }, "apk-downloader").apply {
            isDaemon = true
            start()
        }
    }
}
