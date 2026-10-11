package com.nodewayvpn.pro

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlin.coroutines.resume

/**
 * Замер отклика через живой туннель.
 *
 * Что изменилось по сравнению с версией на HttpURLConnection:
 *  - прогрев повторяется до [WARMUP_ATTEMPTS] раз: одна потерянная SYN-ка или
 *    медленный Reality-хендшейк на свежем ядре больше не убивают профиль;
 *  - если прогрев прошёл, профиль не может стать «-1»: даже если все три замера
 *    после него упали, возвращается время прогрева;
 *  - запрос идёт сырым сокетом, а для SOCKS5 имя хоста уходит прокси как есть
 *    (createUnresolved), поэтому www.google.com резолвится на стороне туннеля,
 *    а не системным DNS телефона (и не берётся IPv6-адрес, до которого VPS
 *    может не дотянуться);
 *  - в логе (tag TunnelProbe) каждая неудача пишется с типом исключения, чтобы
 *    было видно, timeout это, reset или EOF;
 *  - отмена мгновенная: блокирующий сокет корутина сама не прерывает, поэтому при
 *    отмене (кнопка «стоп» или таймаут профиля) сокет закрывается снаружи, и
 *    чтение немедленно падает вместо ожидания до [ATTEMPT_TIMEOUT_MS].
 */
object TunnelProbe {

    private const val TAG = "TunnelProbe"

    private const val HOST = "www.google.com"
    private const val PORT = 80

    /** Таймаут и на connect (рукопожатие SOCKS), и на ожидание первой строки ответа. */
    private const val ATTEMPT_TIMEOUT_MS = 4_000

    private const val WARMUP_ATTEMPTS = 3
    private const val RETRY_PAUSE_MS = 300L
    private const val MEASUREMENT_COUNT = 2

    private const val LOOPBACK = "127.0.0.1"

    private val REQUEST = (
            "GET /generate_204 HTTP/1.1\r\n" +
                    "Host: $HOST\r\n" +
                    "Connection: close\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "\r\n"
            ).toByteArray(Charsets.US_ASCII)

    /**
     * @param socksPort порт локального SOCKS5, либо null — прямое соединение
     * (VLESS в режиме TUN: пакет приложения и так внутри туннеля).
     * @return отклик в мс либо null, если туннель не ответил ни в одной из попыток прогрева.
     */
    suspend fun measure(socksPort: Int?): Int? = withContext(Dispatchers.IO) {
        var warmup: Int? = null
        for (attempt in 1..WARMUP_ATTEMPTS) {
            warmup = probeOnce(socksPort, attempt)
            if (warmup != null) break
            if (attempt < WARMUP_ATTEMPTS) delay(RETRY_PAUSE_MS)
        }
        val first = warmup ?: return@withContext null

        val results = ArrayList<Int>(MEASUREMENT_COUNT)
        repeat(MEASUREMENT_COUNT) { index ->
            probeOnce(socksPort, index + 1)?.let(results::add)
        }
        // Прогрев уже доказал, что профиль жив: не превращаем его в «не отвечает».
        results.minOrNull() ?: first
    }

    /**
     * Один запрос с мгновенной отменой.
     *
     * Блокирующая работа идёт прямо в теле `suspendCancellableCoroutine`, а
     * обработчик отмены вызывается на потоке того, кто отменяет, и закрывает
     * сокет: connect/read выходят с исключением сразу. Результат отменённой
     * корутины всё равно отбрасывается, поэтому ошибку закрытия не логируем.
     */
    private suspend fun probeOnce(socksPort: Int?, attempt: Int): Int? =
        suspendCancellableCoroutine<Int?> { cont ->
            val socket = runCatching { createSocket(socksPort) }.getOrNull()
            if (socket == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { runCatching { socket.close() } }

            val result = try {
                socket.use { blockingProbe(it, socksPort, attempt) }
            } catch (e: Exception) {
                if (!cont.isCancelled) {
                    Log.i(TAG, "attempt $attempt failed via ${routeName(socksPort)}: ${e.javaClass.simpleName}: ${e.message}")
                }
                null
            }
            cont.resume(result)
        }

    private fun createSocket(socksPort: Int?): Socket =
        if (socksPort == null) {
            Socket()
        } else {
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOOPBACK, socksPort)))
        }

    private fun routeName(socksPort: Int?): String = socksPort?.let { "socks:$it" } ?: "direct"

    /** connect + отправка + первая строка ответа. Исключения уходят вызывающему. */
    private fun blockingProbe(socket: Socket, socksPort: Int?, attempt: Int): Int? {
        socket.soTimeout = ATTEMPT_TIMEOUT_MS
        socket.tcpNoDelay = true

        // Через SOCKS5 имя отдаём прокси неразрешённым: резолвит туннель.
        val target = if (socksPort == null) {
            InetSocketAddress(HOST, PORT)
        } else {
            InetSocketAddress.createUnresolved(HOST, PORT)
        }

        val startedAt = System.nanoTime()
        socket.connect(target, ATTEMPT_TIMEOUT_MS)
        socket.getOutputStream().apply {
            write(REQUEST)
            flush()
        }
        val status = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
        val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000).toInt()

        if (status == null || !status.startsWith("HTTP/")) {
            Log.i(TAG, "attempt $attempt: bad reply (${status ?: "EOF"})")
            return null
        }
        Log.d(TAG, "attempt $attempt: $status in ${elapsedMs}ms via ${routeName(socksPort)}")
        return elapsedMs
    }
}