package com.nodewayvpn.pro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import kotlin.math.max

/*
 * Иконки приложений для списка раздельного туннелирования.
 *
 * Страница просит иконку обычной картинкой по адресу
 * https://nodeway.internal/icon/<package>, а нативная часть отдаёт её прямо из
 * PackageManager, перехватив запрос. Раньше иконка ехала через мост и вставлялась
 * в картинку строкой base64: переход в JS, перекодирование, ожидание ответа.
 * Теперь браузер сам решает, что грузить и когда, и в этом не участвует ни мост,
 * ни base64.
 *
 * Хост выбран так, чтобы ничего не значить в сети: такой адрес не резолвится, и
 * запрос всё равно не уйдёт наружу, потому что его перехватываем мы.
 */
internal object AppIcons {

    /** Адрес, который в сети ничего не означает. */
    const val HOST = "nodeway.internal"

    /** Префикс, который страница подставляет к имени пакета. */
    const val BASE_URL = "https://$HOST/icon/"

    private const val PATH_PREFIX = "/icon/"

    /** Сторона иконки в пикселях: с запасом на плотные экраны, CSS уменьшает. */
    private const val SIZE_PX = 96

    /** Предельный объём кэша в байтах. */
    private const val CACHE_BYTES = 512 * 1024

    /** На сколько суток браузер вправе запомнить иконку у себя. */
    private const val CACHE_CONTROL = "max-age=86400"

    /**
     * Иконки в виде PNG-байтов.
     *
     * Пустой массив означает «иконки нет» и тоже запоминается: иначе каждый
     * показ списка снова дёргал бы PackageManager для пакета без иконки.
     */
    private val cache = object : LruCache<String, ByteArray>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /** Пустой поток для ответа без тела. */
    private val EMPTY: InputStream = ByteArrayInputStream(ByteArray(0))

    /**
     * Имя пакета из адреса вида https://nodeway.internal/icon/com.example.app.
     *
     * null — адрес не наш: его должен разбирать сам WebView.
     *
     * Путь берётся сырым: getPath() уже декодирует проценты, и второе
     * декодирование исказило бы имя, в котором встретился процентный знак.
     */
    fun packageOf(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val raw = uri.rawPath ?: return null
        if (!raw.startsWith(PATH_PREFIX)) return null
        val encoded = raw.removePrefix(PATH_PREFIX)
        // Вложенный путь — это уже не имя пакета: не отдаём ничего.
        if (encoded.isEmpty() || encoded.contains('/')) return null
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Ответ на запрос иконки.
     *
     * Отсутствие иконки тоже отвечаем сами — пустым 404. Вернуть null значило бы
     * отправить адрес в сеть, где он не резолвится, и ждать таймаута на каждый
     * пакет без иконки.
     */
    fun respond(context: Context, packageName: String): WebResourceResponse {
        val bytes = bytesFor(context, packageName)
        if (bytes.isEmpty()) return empty(404, "Not Found")
        return WebResourceResponse(
            "image/png",
            "png",
            200,
            "OK",
            mapOf(
                "Cache-Control" to CACHE_CONTROL,
                "Content-Length" to bytes.size.toString(),
            ),
            ByteArrayInputStream(bytes),
        )
    }

    private fun empty(status: Int, reason: String): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), EMPTY)

    /** PNG-байты иконки: считаются один раз на пакет, дальше отдаются из кэша. */
    private fun bytesFor(context: Context, packageName: String): ByteArray {
        cache.get(packageName)?.let { return it }
        val bytes = runCatching { encode(context, packageName) }.getOrDefault(ByteArray(0))
        cache.put(packageName, bytes)
        return bytes
    }

    /**
     * Рисует иконку в квадрат и кодирует PNG.
     *
     * Раньше тот же рисунок уходил в base64-строку для вставки в картинку на
     * стороне JS; теперь байты отдаются прямо браузеру.
     */
    private fun encode(context: Context, packageName: String): ByteArray {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        if (width <= 0 || height <= 0) return ByteArray(0)

        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        // Масштабируем с сохранением пропорций и центрируем, иначе иконки будут растянуты.
        val scale = max(SIZE_PX.toFloat() / width, SIZE_PX.toFloat() / height)
        val canvas = Canvas(bitmap)
        canvas.translate((SIZE_PX - width * scale) / 2f, (SIZE_PX - height * scale) / 2f)
        canvas.scale(scale, scale)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}