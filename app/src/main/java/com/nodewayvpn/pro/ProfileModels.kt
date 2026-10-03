package com.nodewayvpn.pro

import org.json.JSONObject
import java.util.UUID

/**
 * Конфигурация сервера, импортированная из буфера обмена или из подписки.
 *
 * Сама ссылка хранится как есть — разбором занимается [VlessProfile].
 */
data class ServerProfile(
    val id: String,
    val name: String,
    val link: String,
    val source: Source,
    val subscriptionId: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {

    enum class Source(val code: String) {
        CLIPBOARD("clipboard"),
        SUBSCRIPTION("subscription");

        companion object {
            fun from(code: String?): Source =
                entries.firstOrNull { it.code == code } ?: CLIPBOARD
        }
    }

    /** Короткое описание «адрес:порт · защита/сеть» для карточки в UI. */
    fun describe(): String = runCatching {
        val parsed = VlessProfile.parse(link)
        "${parsed.address}:${parsed.port} · ${parsed.security.uppercase()}/${parsed.network}"
    }.getOrDefault("Ссылка не распознана")

    /** Название из фрагмента ссылки, иначе — адрес. */
    fun resolveName(): String = name.ifBlank {
        runCatching { VlessProfile.parse(link).remark }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { VlessProfile.parse(link).address }.getOrDefault("Сервер")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("link", link)
        put("source", source.code)
        put("subscriptionId", subscriptionId ?: JSONObject.NULL)
        put("addedAt", addedAt)
    }

    companion object {
        fun create(
            link: String,
            name: String,
            source: Source,
            subscriptionId: String? = null,
        ): ServerProfile = ServerProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            link = link,
            source = source,
            subscriptionId = subscriptionId,
        )

        fun fromJson(json: JSONObject): ServerProfile = ServerProfile(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = json.optString("name"),
            link = json.optString("link"),
            source = Source.from(json.optString("source")),
            subscriptionId = if (json.isNull("subscriptionId")) null else json.optString("subscriptionId"),
            addedAt = json.optLong("addedAt", System.currentTimeMillis()),
        )
    }
}

/**
 * Подписка со списком конфигураций. Сервер отдаёт либо обычный текст со ссылками,
 * либо base64 — это разбирает [LinkListParser].
 */
data class SubscriptionSource(
    val id: String,
    val url: String,
    val name: String,

    /** Интервал автообновления из директивы `#refresh`, в минутах; 0 — только вручную. */
    val refreshMinutes: Int = 0,
    val supportUrl: String = "",
    val addedAt: Long = System.currentTimeMillis(),

    /** Когда подписка последний раз успешно обновилась. */
    val updatedAt: Long = 0L,
) {

    val refreshMillis: Long get() = refreshMinutes * 60_000L

    val refreshLabel: String
        get() = when {
            refreshMinutes <= 0 -> "обновление вручную"
            refreshMinutes < 60 -> "автообновление раз в $refreshMinutes мин"
            refreshMinutes % (24 * 60) == 0 -> "автообновление раз в ${refreshMinutes / (24 * 60)} д"
            else -> "автообновление раз в ${refreshMinutes / 60} ч"
        }

    /** «Обновлено 5 мин назад» — для подписи в списке подписок. */
    val updatedLabel: String
        get() = when {
            updatedAt <= 0L -> "ещё не обновлялась"
            else -> {
                val minutes = ((System.currentTimeMillis() - updatedAt) / 60_000L).toInt()
                when {
                    minutes < 1 -> "обновлено только что"
                    minutes < 60 -> "обновлено $minutes мин назад"
                    else -> "обновлено ${minutes / 60} ч назад"
                }
            }
        }

    /** Через сколько минут положено обновиться в следующий раз. */
    val nextRefreshInMinutes: Int
        get() = if (refreshMinutes <= 0 || updatedAt <= 0L) {
            -1
        } else {
            val left = ((updatedAt + refreshMillis - System.currentTimeMillis()) / 60_000L).toInt()
            left.coerceAtLeast(0)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("name", name)
        put("refreshMinutes", refreshMinutes)
        // Старое поле оставляем, чтобы не ломать сохранённые данные прошлых версий.
        put("refreshHours", refreshMinutes / 60)
        put("supportUrl", supportUrl)
        put("addedAt", addedAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun create(url: String, name: String = ""): SubscriptionSource = SubscriptionSource(
            id = UUID.randomUUID().toString(),
            url = url,
            name = name,
        )

        fun fromJson(json: JSONObject): SubscriptionSource {
            val minutes = if (json.has("refreshMinutes")) {
                json.optInt("refreshMinutes", 0)
            } else {
                json.optInt("refreshHours", 0) * 60
            }
            return SubscriptionSource(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                url = json.optString("url"),
                name = json.optString("name"),
                refreshMinutes = minutes,
                supportUrl = json.optString("supportUrl"),
                addedAt = json.optLong("addedAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", 0L),
            )
        }
    }
}
