package com.nodewayvpn.pro

import java.util.Locale

/**
 * Разобранная ссылка `olcrtc://` и генерация YAML-конфига клиента `mode: cnc`.
 *
 * Формат URI (docs/uri.ru.md):
 * `olcrtc://<Provider>?<Transport><payload>@<RoomID>#<EncryptionKey>$<MIMO>`
 *
 * Пример:
 * `olcrtc://jitsi?datachannel@https://meet.egovm.ru/hl2mpru#<64 hex>$UK Обход списков`
 *
 * Транспорт запускается отдельным процессом, приложение только собирает для него
 * конфиг и следит за локальным SOCKS5-портом.
 */
data class OlcrtcProfile(
    val provider: String,
    val transport: String,
    val room: String,
    val key: String,
    val remark: String,
    val payload: Map<String, String> = emptyMap(),
) {

    /** Имя комнаты без схемы — для показа в карточке. */
    val roomLabel: String
        get() = room.removePrefix("https://").removePrefix("http://").trimEnd('/')

    fun describe(): String = "olcrtc · ${provider.uppercase(Locale.ROOT)} / $transport"

    /**
     * Собирает YAML для клиента `mode: cnc`.
     *
     * Только документированные поля: загрузчик у olcrtc строгий и отвергает
     * неизвестные ключи, поэтому лишние параметры из URI молча отбрасываются.
     */
    fun toYaml(socksHost: String, socksPort: Int, dns: String = DEFAULT_DNS): String = buildString {
        appendLine("mode: cnc")
        appendLine("auth:")
        appendLine("  provider: $provider")
        appendLine("room:")
        appendLine("  id: ${room.asYamlString()}")
        appendLine("crypto:")
        appendLine("  key: ${key.asYamlString()}")
        appendLine("net:")
        appendLine("  transport: $transport")
        appendLine("  dns: ${dns.asYamlString()}")
        transportSection()?.let { append(it) }
        appendLine("socks:")
        appendLine("  host: ${socksHost.asYamlString()}")
        appendLine("  port: $socksPort")
    }

    /** Блок настроек транспорта, либо null, если для него параметров нет. */
    private fun transportSection(): String? {
        val lines = mutableListOf<String>()

        // vp8-настройки есть и у vp8channel, и у seichannel.
        val vp8 = transportSectionOf(
            "vp8",
            listOf("vp8-fps" to "fps", "vp8-batch" to "batch_size"),
        )
        if (vp8 != null) lines += vp8

        when (transport) {
            "seichannel" -> transportSectionOf(
                "sei",
                listOf(
                    "fps" to "fps",
                    "batch" to "batch_size",
                    "frag" to "fragment_size",
                    "ack-ms" to "ack_timeout_ms",
                ),
            )?.let { lines += it }

            "videochannel" -> transportSectionOf(
                "video",
                listOf(
                    "video-w" to "width",
                    "video-h" to "height",
                    "video-fps" to "fps",
                    "video-codec" to "codec",
                    "video-qr-size" to "qr_size",
                    "video-qr-recovery" to "qr_recovery",
                    "video-tile-module" to "tile_module",
                    "video-tile-rs" to "tile_rs",
                ),
            )?.let { lines += it }
        }

        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n", postfix = "\n")
    }

    private fun transportSectionOf(section: String, mapping: List<Pair<String, String>>): String? {
        val body = mapping.mapNotNull { (uriKey, yamlKey) ->
            payload[uriKey]?.trim()?.takeIf { it.isNotEmpty() }?.let { "  $yamlKey: $it" }
        }
        return body.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "$section:\n", postfix = "\n")
    }

    companion object {

        const val DEFAULT_DNS = "8.8.8.8:53"

        private val SCHEME = "olcrtc://"

        private val PROVIDERS = setOf("jitsi", "telemost", "wbstream", "none")
        private val TRANSPORTS = setOf("datachannel", "vp8channel", "seichannel", "videochannel")

        private val KEY_REGEX = Regex("^[0-9a-fA-F]{64}$")

        fun isOlcrtcLink(link: String): Boolean =
            link.trim().startsWith(SCHEME, ignoreCase = true)

        fun parse(link: String): OlcrtcProfile {
            val raw = link.trim()
            require(isOlcrtcLink(raw)) { "Ссылка должна начинаться с olcrtc://" }

            val body = raw.substring(SCHEME.length)

            // Фрагмент идёт после первого '#': сначала ключ, затем после '$' — MIMO-комментарий.
            val hashIndex = body.indexOf('#')
            require(hashIndex > 0) { "В ссылке нет ключа шифрования" }
            val fragment = body.substring(hashIndex + 1)
            val head = body.substring(0, hashIndex)

            val key = fragment.substringBefore('$').trim()
            val remark = fragment.substringAfter('$', "").trim()

            val room = head.substringAfter('@', "").trim()
            require(room.isNotEmpty()) { "В ссылке нет ID комнаты" }

            val authority = head.substringBefore('@')
            val provider = authority.substringBefore('?').lowercase(Locale.ROOT)
            val transportPart = authority.substringAfter('?', "")
            val transport = transportPart.substringBefore('<').lowercase(Locale.ROOT)

            require(provider in PROVIDERS) { "Неизвестный провайдер: $provider" }
            require(transport.isNotEmpty() && transport in TRANSPORTS) {
                "Неизвестный транспорт: ${transport.ifBlank { "—" }}"
            }
            require(KEY_REGEX.matches(key)) { "Ключ должен состоять из 64 hex-символов" }

            return OlcrtcProfile(
                provider = provider,
                transport = transport,
                room = room,
                key = key,
                remark = remark,
                payload = parsePayload(transportPart.substringAfter('<', "").substringBefore('>')),
            )
        }

        /** Параметры транспорта: `vp8-fps=60&vp8-batch=64`. */
        private fun parsePayload(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            val result = mutableMapOf<String, String>()
            raw.split('&').forEach { pair ->
                val key = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
            }
            return result
        }

        /** Двойные кавычки и обратный слэш — единственное, что нужно экранировать в YAML. */
        private fun String.asYamlString(): String =
            "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}