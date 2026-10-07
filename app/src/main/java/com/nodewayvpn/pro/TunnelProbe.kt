package com.nodewayvpn.pro

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLConnection

/**
 * Замер отклика через живой туннель.
 *
 * Считает TTFB до `generate_204` — того самого адреса, который меряют другие
 * клиенты. Именно «через туннель», а не до сервера: проверка обычным сокетом
 * ничего не сказала бы о работоспособности профиля.
 *
 * Маршрут зависит от транспорта, и это не деталь реализации:
 *  - VLESS — пакет приложения всегда внутри туннеля (в режиме `all` там все, в
 *    `allow` мы добавляем себя явно, в `exclude` сами себя вычищем), поэтому
 *    запрос уходит прямо в TUN;
 *  - olcrtc — наш пакет нарочно вне туннеля, иначе процесс olcrtc не смог бы
 *    поднять туннель. Наивный запрос здесь измерил бы прямое соединение и
 *    показал отличные 30 мс при мёртвом туннеле, поэтому идём через локальный
 *    SOCKS5 — это и есть выход туннеля.
 */
object TunnelProbe {

    private const val TAG = "TunnelProbe"

    /**
     * Именно `www`: без него сервер отвечает редиректом, и лишний круг уходит
     * в измерение вместо туннеля.
     *
     * Схема `http`, а не `https`, тоже намеренно: TLS-рукопожатие здесь стоило бы
     * дороже самого замера. Внутри туннеля трафик и так шифруется апстримом.
     */
    private const val PROBE_URL = "http://www.google.com/generate_204"

    private const val TIMEOUT_MS = 5_000

    /**
     * Сколько замеров делать после прогрева. Два достаточно, чтобы сгладить
     * джиттер ОС и пиковые задержки, не удлиняя проверку профилей.
     */
    private const val MEASUREMENT_COUNT = 2

    /**
     * @param socksPort порт локального SOCKS5 от olcrtc, либо null для VLESS.
     * @return отклик в миллисекундах либо null, если туннель не ответил.
     *
     * Первый запрос — прогрев. Он поглощает DNS-резолюцию, TCP-рукопожатие и
     * медленный старт TCP, поэтому без него каждый профиль показывал время
     * старта соединения вместо реального отклика: хорошие сервера выглядели
     * медленными, а первый замер был завышен десятками-сотнями миллисекунд.
     * Следующие замеры идут уже по «тёплому» пути и берутся за минимум, чтобы
     * не ловить случайные пики загрузки системы.
     */
    suspend fun measure(socksPort: Int?): Int? = withContext(Dispatchers.IO) {
        // Прогрев: если туннель не ответил на первого запроса — он мёртв,
        // остальные замеры бессмысленны.
        if (probeOnce(socksPort) == null) return@withContext null

        val results = ArrayList<Int>(MEASUREMENT_COUNT)
        repeat(MEASUREMENT_COUNT) {
            probeOnce(socksPort)?.let(results::add)
        }
        results.minOrNull()
    }

    /**
     * Единичный HTTP-запрос с замером TTFB.
     *
     * @return отклик в мс либо null, если соединение не установилось или
     * ответ не пришёл в пределах [TIMEOUT_MS].
     */
    private fun probeOnce(socksPort: Int?): Int? {
        val connection = runCatching {
            (openTarget(socksPort) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // Редиректы и кэш мешают: нас интересует ровно один круг до адреса.
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
            }
        }.getOrElse { error ->
            Log.d(TAG, "Probe could not start", error)
            return null
        }

        return try {
            val startedAt = System.nanoTime()
            // Код ответа приходит с заголовками, то есть это и есть TTFB.
            val code = connection.responseCode
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            // Любой HTTP-ответ доказывает, что туннель ходит: сам код не важен,
            // важно что соединение дошло до сервера и вернулось.
            Log.d(TAG, "Probe: $code in ${elapsedMs}ms via ${socksPort?.let { "socks:$it" } ?: "tun"}")
            elapsedMs.toInt()
        } catch (e: Exception) {
            Log.d(TAG, "Probe failed", e)
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * Прямое соединение для VLESS и SOCKS5-обёртка для olcrtc.
     *
     * Именно так, а не через `openConnection(proxyFor(...))`: передача null вместо
     * прокси бросает `IllegalArgumentException`, и ветка VLESS молча умирала бы на
     * каждом замере. Прямое соединение — это вариант без аргумента.
     */
    private fun openTarget(socksPort: Int?): URLConnection {
        val url = URL(PROBE_URL)
        return if (socksPort == null) {
            url.openConnection()
        } else {
            url.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOOPBACK, socksPort)))
        }
    }

    private const val LOOPBACK = "127.0.0.1"
}