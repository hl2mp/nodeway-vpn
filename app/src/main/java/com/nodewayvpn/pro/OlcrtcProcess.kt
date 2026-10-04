package com.nodewayvpn.pro

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Отдельный процесс `olcrtc`, который поднимает локальный SOCKS5.
 *
 * Ядро приезжает бинарником `libolcrtc.so` в `jniLibs`: файлы из nativeLibraryDir
 * помечены в SELinux как `apk_data_file` и доступны для exec, в отличие от
 * домашнего каталога приложения. Отдельный процесс нужен ещё и потому, что в
 * одном процессе нельзя держать два Go-рантайма: libXray уже занимает `TLS_SLOT_APP`.
 *
 * Код olcrtc не меняется — приложение только пишет YAML и запускает CLI.
 */
class OlcrtcProcess(private val context: Context) {

    private var process: Process? = null
    private var configFile: File? = null

    /** Последние строки вывода процесса — попадают в текст ошибки в UI. */
    private val recentOutput = ArrayDeque<String>(LOG_TAIL_LINES)

    /**
     * Запускает туннель и ждёт готовности SOCKS5.
     *
     * @return порт, на котором поднялся SOCKS5.
     * @throws IllegalStateException бинарник не собран, процесс упал или не успел поднять порт.
     */
    suspend fun start(profile: OlcrtcProfile, timeoutMs: Long = START_TIMEOUT_MS): Int =
        withContext(Dispatchers.IO) {
            stop()

            val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
            if (!binary.exists()) {
                throw IllegalStateException(
                    "В сборке нет $BINARY_NAME — запустите tools/build-olcrtc.ps1",
                )
            }

            val config = File(context.cacheDir, CONFIG_NAME)
            config.writeText(profile.toYaml(LOOPBACK, SOCKS_PORT))
            configFile = config

            val started = ProcessBuilder(binary.absolutePath, config.absolutePath)
                .redirectErrorStream(true)
                .start()
            process = started

            drainLogs(started)

            awaitSocksPort(started, SOCKS_PORT, System.currentTimeMillis() + timeoutMs)
            SOCKS_PORT
        }

    /** Останавливает процесс: сначала SIGTERM, чтобы olcrtc свернул сессию штатно. */
    fun stop() {
        val running = process ?: return
        process = null

        val pid = pidOf(running)
        val signalled = if (pid > 0) {
            runCatching { Os.kill(pid, OsConstants.SIGTERM) }.isSuccess
        } else {
            false
        }
        if (!signalled) running.destroy() else awaitExit(running)

        if (!hasExited(running)) running.destroy()
        runCatching { running.waitFor() }

        configFile?.delete()
        configFile = null
    }

    /** Ждёт завершения процесса, но не дольше таймаута. */
    private fun awaitExit(running: Process) {
        val deadline = System.currentTimeMillis() + STOP_GRACE_MS
        while (System.currentTimeMillis() < deadline) {
            if (hasExited(running)) return
            runCatching { Thread.sleep(POLL_INTERVAL_MS) }
        }
    }

    /**
     * `Process.isAlive` и `waitFor(timeout)` появились только в API 26,
     * а минимальная версия приложения ниже, поэтому состояние читаем через `exitValue`.
     */
    private fun hasExited(running: Process): Boolean = runCatching { running.exitValue() }.isSuccess

    /** Читает логи процесса, пока он жив, попутно запоминая хвост для сообщения об ошибке. */
    private fun drainLogs(running: Process) {
        Thread({
            runCatching {
                running.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val text = Journal.stripTimestamp(line)
                    Log.i(TAG, text)
                    // Пайп читаем всегда, даже с выключенным журналом: опустишь
                    // его — клиент встанет, как только заполнится буфер канала.
                    Journal.append(Journal.SOURCE_OLCRTC, text)
                    synchronized(recentOutput) {
                        if (recentOutput.size >= LOG_TAIL_LINES) recentOutput.removeFirst()
                        recentOutput.addLast(text)
                    }
                }
            }
            Log.i(TAG, "olcrtc output closed")
        }, "olcrtc-logs").apply {
            isDaemon = true
            start()
        }
    }

    /** Хвост вывода одной строкой — в сообщении об ошибке. */
    private fun outputTail(): String = synchronized(recentOutput) {
        if (recentOutput.isEmpty()) "" else ": " + recentOutput.joinToString(" / ")
    }

    /**
     * Ждёт, пока SOCKS5 начнёт принимать подключения.
     *
     * Проверка идёт настоящим SOCKS5-приветствием, а не голым connect(): сервер
     * не должен получать оборванные на середине соединения.
     */
    private suspend fun awaitSocksPort(
        running: Process,
        port: Int,
        deadline: Long,
    ) {
        while (System.currentTimeMillis() < deadline) {
            if (hasExited(running)) {
                throw IllegalStateException("olcrtc завершился${outputTail()}")
            }
            if (handshakes(port)) return
            delay(PROBE_INTERVAL_MS)
        }
        throw IllegalStateException("olcrtc не поднял SOCKS5 за ${START_TIMEOUT_MS / 1000} с")
    }

    private fun handshakes(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, port), PROBE_TIMEOUT_MS)
            socket.soTimeout = PROBE_TIMEOUT_MS
            socket.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
            socket.getInputStream().read() >= 0
        }
    }.getOrElse { error ->
        if (error !is IOException) Log.d(TAG, "SOCKS probe failed", error)
        false
    }

    /** `Process.pid()` есть не на всех версиях API, поэтому достаём его рефлексией. */
    private fun pidOf(running: Process): Int = runCatching {
        Process::class.java.getMethod("pid").invoke(running) as Int
    }.getOrDefault(-1)

    companion object {
        private const val TAG = "OlcrtcProcess"

        /** Имя файла обязано начинаться с `lib`, иначе он не попадёт в nativeLibraryDir. */
        private const val BINARY_NAME = "libolcrtc.so"
        private const val CONFIG_NAME = "olcrtc.yaml"

        private const val LOOPBACK = "127.0.0.1"
        private const val SOCKS_PORT = 10808

        private const val START_TIMEOUT_MS = 90_000L
        private const val STOP_GRACE_MS = 6_000L
        private const val PROBE_INTERVAL_MS = 400L
        private const val PROBE_TIMEOUT_MS = 1_000
        private const val POLL_INTERVAL_MS = 100L
        private const val LOG_TAIL_LINES = 4
    }
}