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
    ) {
        val vlessLinks: List<Link> get() = links.filter { it.scheme == "vless" }

        /** Схемы, которые приложение пока не поддерживает (olcrtc://, vmess:// …). */
        val unsupported: List<Link> get() = links.filter { it.scheme != "vless" }
    }

    private val SCHEME = "([a-zA-Z][a-zA-Z0-9+.\\-]*)://"

    /** Ссылка начинается в начале строки или после пробела. */
    private val LINK_REGEX = Regex("(?:^|\\s+)$SCHEME[^\\s]+")

    /** Директива `#key: value` в строке-заголовке. */
    private val DIRECTIVE_REGEX = Regex("#([a-zA-Z][a-zA-Z0-9_\\-]*)\\s*:\\s*([^#]*)")

    fun parse(rawInput: String): Result {
        val text = unwrapBase64(rawInput).trim()
        if (text.isEmpty()) return Result(emptyList())

        val tokens = splitTokens(text)
        val links = mutableListOf<Link>()
        val directives = mutableListOf<String>()

        tokens.forEach { token ->
            // Токен, начинающийся с «#», — это заголовок с директивами
            // («#support-url: … #name: … #refresh: 1h»), а не конфигурация.
            val match = if (token.startsWith("#")) null else LINK_REGEX.find(token)
            if (match != null) {
                // find, а не matchEntire: подпись ссылки может содержать пробелы
                // («…#CF Основной (TLS)»), поэтому берём только саму ссылку.
                links += Link(raw = match.value.trim(), scheme = match.groupValues[1].lowercase(Locale.ROOT))
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

    /** Подписки часто приходят в base64; пробуем декодировать, если ссылок в тексте нет. */
    private fun unwrapBase64(input: String): String {
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
