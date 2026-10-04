package com.nodewayvpn.pro

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Читает файл, в который Xray-core пишет логи (`log.error` в конфиге), и
 * складывает строки в [Journal].
 *
 * Отдельного приёмника логов у AAR нет: gomobile сам выводит Go-строки в logcat
 * с тегом `GoLog`, а читать logcat из приложения нельзя — это привилегия системы.
 * Файл в кеше — единственный доступный канал, который ядро открывает само.
 *
 * Ядро дописывает в файл, поэтому достаточно держать позицию чтения и дочитывать
 * хвост. Перезапуск ядра пересоздаёт файл: если он стал короче позиции, читаем заново.
 */
class XrayLogTailer(private val file: File) {

    private var thread: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({ follow() }, "xray-log").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun follow() {
        val pending = StringBuilder()
        var position = 0L

        try {
            while (running) {
                val chunk = readTail(position) ?: run {
                    // Файла ещё нет: ядро создаст его при старте.
                    Thread.sleep(POLL_INTERVAL_MS)
                    null
                }

                if (chunk != null) {
                    position += chunk.size

                    pending.append(String(chunk, Charsets.UTF_8))
                    // Последняя строка может быть неполной — ждём остатка.
                    val text = pending.toString()
                    val lastBreak = text.lastIndexOf('\n')
                    if (lastBreak >= 0) {
                        Journal.appendAll(
                            Journal.SOURCE_XRAY,
                            text.substring(0, lastBreak)
                                .split('\n')
                                .map { Journal.stripTimestamp(it) },
                        )
                        pending.setLength(0)
                        pending.append(text, lastBreak + 1, text.length)
                    }

                    // Ядро пишет в файл само и остановить его нельзя, поэтому при
                    // переполнении перестаём читать: место освободит остановка
                    // подключения, файл и так удаляется на каждом старте.
                    if (position >= MAX_BYTES) {
                        Log.w(TAG, "log file reached ${MAX_BYTES / 1024 / 1024} MB, stop tailing")
                        return
                    }
                }

                Thread.sleep(POLL_INTERVAL_MS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "xray log tail failed", e)
        }
    }

    /** Дочитывает файл с позиции, либо null, если данных пока нет. */
    private fun readTail(position: Long): ByteArray? {
        if (!file.exists()) return null

        val length = file.length()
        if (length <= position) return null

        // Большими порциями не тянем: файл может расти быстрее, чем мы читаем.
        val size = minOf(length - position, MAX_CHUNK.toLong()).toInt()
        val buffer = ByteArray(size)

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(position)
            raf.readFully(buffer)
        }
        return buffer
    }

    private companion object {
        const val TAG = "XrayLogTailer"

        const val POLL_INTERVAL_MS = 250L
        const val MAX_CHUNK = 256 * 1024

        /** Больше 8 МБ логов за одну сессию читать бессмысленно. */
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}