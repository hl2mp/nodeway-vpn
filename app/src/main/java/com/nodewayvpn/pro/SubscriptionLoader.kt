package com.nodewayvpn.pro

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Загружает подписку и разбирает её ответ.
 *
 * Подписки отдают либо plain-текст, либо base64 — разбором занимается
 * [LinkListParser].
 */
object SubscriptionLoader {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    /** Панели отдают 403/404 на дефолтном агенте, поэтому притворяемся клиентом. */
    private const val USER_AGENT =
        "v2rayNG/1.9.0 (nodeway-android)"

    data class Loaded(
        val parsed: LinkListParser.Result,
        val raw: String,
    )

    fun fetch(url: String): Loaded {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "Подписка должна быть http(s)-ссылкой"
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("Сервер подписки вернул код $status")
            }

            val encoding = connection.contentEncoding.orEmpty()
            val stream = if (encoding.contains("gzip", ignoreCase = true)) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }

            val body = stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
            return Loaded(parsed = LinkListParser.parse(body), raw = body)
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}