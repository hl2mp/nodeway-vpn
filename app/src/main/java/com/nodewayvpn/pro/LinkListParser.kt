package com.nodewayvpn.pro

import android.util.Base64
import java.util.Locale

/**
 * Разбирает содержимое буфера обмена и ответ подписки в список ссылок.
 *
 * Поддерживает три формата, которые встречаются на практике:
 *  - plain-текст, где ссылки идут через пробел или перевод строки;
 *  - base64 от тех же ссылок (`format=v2ray` у некоторых панелей);
 *  - заголовок с директивами вида `#name: … #refresh: 12h #support-url: …`.
 */
object LinkListParser {

    /** Одна найденная ссылка: сама строка и её схема. */
    data class Link(val raw: String, val scheme: String)

    data class Result(
        val links: List<Link>,
        val title: String = "",
        val refreshMinutes: Int = 0,
        val supportUrl: String = "",
        /** Локальные поля `##key: value` — по сырой ссылке, которой они относятся. */
        val serverFields: Map<String, Map<String, String>> = emptyMap(),
    ) {
        /** Ссылки, которые приложение умеет импортировать. */
        val supported: List<Link>
            get() = links.filter { it.scheme == SCHEME_VLESS || (it.scheme == SCHEME_OLCRTC) }

        /** Схемы, которые приложение пока не поддерживает (vmess:// …). */
        val unsupported: List<Link> get() = links - supported.toSet()

        /** Имя из локального поля `##name:` для указанной ссылки. */
        fun serverField(link: String, key: String): String =
            serverFields[link]?.get(key)?.trim().orEmpty()
    }

    private val SCHEME = "([a-zA-Z][a-zA-Z0-9+.\\-]*)://"

    /**
     * Ссылка в начале токена.
     *
     * Саму ссылку берём целиком, а не до первого пробела: после `splitTokens` токен
     * уже заканчивается ровно там, где начинается следующая схема, а хвост с пробелами
     * («#CF Основной (TLS)», «$UK Обход списков (WB)») относится к этой ссылке.
     */
    private val SCHEME_AT_START_REGEX = Regex("^$SCHEME")

    /**
     * Директива `#key: value` в строке-заголовке.
     *
     * Взгляд назад отсекает `##name:` — это локальное поле конкретного сервера,
     * а не директива подписки, иначе оно перезаписало бы заголовок.
     */
    private val DIRECTIVE_REGEX = Regex("(?<!#)#([a-zA-Z][a-zA-Z0-9_\\-]*)\\s*:\\s*([^#]*)")

    /** Локальное поле конкретного сервера: `##name: RU-1`. */
    private val SERVER_FIELD_REGEX = Regex("##([a-zA-Z][a-zA-Z0-9_\\-]*)\\s*:\\s*(.*)")

    private const val SCHEME_VLESS = "vless"
    private const val SCHEME_OLCRTC = "olcrtc"

    fun parse(rawInput: String): Result {
        val text = unwrapBase64(rawInput).trim()
        if (text.isEmpty()) return Result(emptyList())

        val tokens = mergeRemarks(splitTokens(text))
        val links = mutableListOf<Link>()
        val directives = mutableListOf<String>()
        val serverFields = mutableMapOf<String, MutableMap<String, String>>()
        var lastLink: Link? = null

        tokens.forEach { token ->
            // Токен, начинающийся с «#», — это заголовок с директивами
            // («#support-url: … #name: … #refresh: 1h»), а не конфигурация.
            // «##» — локальное поле, привязанное к предыдущей ссылке.
            if (token.startsWith("##")) {
                val owner = lastLink ?: return@forEach
                serverFields.getOrPut(owner.raw) { mutableMapOf() }.apply {
                    SERVER_FIELD_REGEX.findAll(token).forEach { hit ->
                        put(hit.groupValues[1].lowercase(Locale.ROOT), hit.groupValues[2].trim())
                    }
                }
                return@forEach
            }

            val scheme = if (token.startsWith("#")) {
                null
            } else {
                SCHEME_AT_START_REGEX.find(token)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            }
            if (scheme != null) {
                val link = Link(raw = token, scheme = scheme)
                links += link
                lastLink = link
            } else {
                directives += token
            }
        }

        var title = ""
        var refresh = 0
        var support = ""
        directives.forEach { line ->
            DIRECTIVE_REGEX.findAll(line).forEach { hit ->
                when (hit.groupValues[1].lowercase(Locale.ROOT)) {
                    "name" -> title = hit.groupValues[2].trim()
                    "refresh" -> refresh = parseRefreshMinutes(hit.groupValues[2])
                    "support-url" -> support = hit.groupValues[2].trim()
                }
            }
        }

        return Result(
            links = links.distinctBy { it.raw },
            title = title,
            refreshMinutes = refresh,
            supportUrl = support,
            serverFields = serverFields,
        )
    }

    /**
     * Делит текст на токены, не разрывая `#фрагмент` с пробелами:
     * новый токен начинается только со схемы, поэтому `vless://…#CF Основной (TLS)`
     * остаётся одной ссылкой.
     */
    private fun splitTokens(text: String): List<String> {
        val marker = Regex("(?:^|\\s+)(?=$SCHEME)")
        val tokens = mutableListOf<String>()
        var index = 0
        marker.findAll(text).forEach { hit ->
            // Не отрываем значение директивы: «#support-url: https://…».
            val isDirectiveValue = hit.range.first > 0 && text[hit.range.first - 1] == ':'
            if (isDirectiveValue) return@forEach
            if (hit.range.first > index) {
                tokens += text.substring(index, hit.range.first).trim()
            }
            index = hit.range.last + 1
        }
        if (index < text.length) tokens += text.substring(index).trim()

        return tokens
            .flatMap { line -> if ('\n' in line || '\r' in line) line.lines() else listOf(line) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Склеивает обратно MIMO-комментарий olcrtc-ссылки.
     *
     * В отличие от VLESS, где `#фрагмент` percent-encoded, у olcrtc хвост после `$`
     * — свободный текст с пробелами («$UK Обход списков (WB)»). Токенизация режет его
     * на слова, поэтому все куски до следующей схемы возвращаются в ссылку.
     */
    private fun mergeRemarks(tokens: List<String>): List<String> {
        val merged = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            merged += token

            val hasRemark = token.startsWith("olcrtc://", ignoreCase = true) &&
                token.substringAfter('#', "").contains('$')
            if (!hasRemark) {
                index++
                continue
            }

            var next = index + 1
            while (next < tokens.size &&
                !tokens[next].contains("://") &&
                !tokens[next].startsWith("#")
            ) {
                merged[merged.lastIndex] += " " + tokens[next]
                next++
            }
            index = next
        }
        return merged
    }

    /**
     * Подписки часто приходят в base64; пробуем декодировать, если ссылок в тексте нет.
     *
     * Общая с deeplink: ссылка импорта `nodeway://import#…` приходит в том же виде
     * и разворачивается тем же правилом — в base64url нет ни «:», ни «/», а в ссылке
     * всегда есть `://`, поэтому флаг в формате не нужен.
     */
    internal fun unwrapBase64(input: String): String {
        val candidate = input.trim()
        if (candidate.contains("://")) return candidate

        val normalised = candidate
            .replace("\r", "")
            .replace("\n", "")
            .replace(' ', '+')
            .replace('-', '+')
            .replace('_', '/')

        val padded = normalised.padEnd((normalised.length + 3) / 4 * 4, '=')
        return runCatching { String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8) }
            .getOrElse { candidate }
    }

    /**
     * Разбирает значение директивы `#refresh`: `12h`, `30m`, `1d`, голое число (часы).
     * Возвращает интервал в минутах; 0 — автообновление не запрошено.
     */
    private fun parseRefreshMinutes(value: String): Int {
        val raw = value.trim().trim('"', '\'').lowercase(Locale.ROOT)
        val digits = Regex("(\\d+)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return when {
            raw.endsWith("d") -> (digits ?: 1) * 24 * 60
            raw.endsWith("h") -> (digits ?: 1) * 60
            raw.endsWith("m") -> digits ?: 1
            digits != null -> digits * 60
            else -> 0
        }
    }
}
