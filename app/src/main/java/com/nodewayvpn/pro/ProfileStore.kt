package com.nodewayvpn.pro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Collections

/**
 * Хранилище профилей и подписок.
 *
 * Данные небольшие, поэтому держат их одним JSON-массивом в SharedPreferences.
 * Все методы синхронизированы: к хранилищу обращаются и JS-мост (фоновый поток
 * WebView), и поток загрузки подписки.
 */
class ProfileStore(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    var profiles: List<ServerProfile>
        get() = synchronized(lock) { read(KEY_PROFILES) { ServerProfile.fromJson(it) } }
        private set(value) = synchronized(lock) { write(KEY_PROFILES, value.map { it.toJson() }) }

    var subscriptions: List<SubscriptionSource>
        get() = synchronized(lock) { read(KEY_SUBSCRIPTIONS) { SubscriptionSource.fromJson(it) } }
        private set(value) = synchronized(lock) { write(KEY_SUBSCRIPTIONS, value.map { it.toJson() }) }

    /** Профиль, выбранный пользователем (подсвечивается в списке). */
    var selectedProfileId: String
        get() = synchronized(lock) { sp.getString(KEY_SELECTED, "").orEmpty() }
        set(value) = synchronized(lock) { sp.edit().putString(KEY_SELECTED, value).apply() }

    /**
     * Момент установки текущего подключения (мс), 0 — подключения нет.
     *
     * Хранится, чтобы счётчик времени не сбрасывался при перезапуске UI.
     */
    var connectedAt: Long
        get() = synchronized(lock) { sp.getLong(KEY_CONNECTED_AT, 0L) }
        set(value) = synchronized(lock) { sp.edit().putLong(KEY_CONNECTED_AT, value).apply() }

    /**
     * Подписки, которые сейчас грузятся — только для UI, не сохраняется.
     * Пока подписка в этом множестве, кнопка «Обновить» показывает спиннер.
     */
    val loadingSubscriptions: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf<String>())

    // region profiles

    fun profile(id: String): ServerProfile? = profiles.firstOrNull { it.id == id }

    /**
     * Добавляет ссылки, игнорируя дубликаты (та же ссылка уже есть).
     *
     * @return количество действительно добавленных профилей.
     */
    fun addProfiles(
        links: List<String>,
        names: Map<String, String>,
        source: ServerProfile.Source,
        subscriptionId: String?,
    ): Int = synchronized(lock) {
        val current = profiles
        val known = current.map { it.link }.toHashSet()
        val fresh = links.distinct().filter { known.add(it) }.map { link ->
            ServerProfile.create(
                link = link,
                name = names[link].orEmpty(),
                source = source,
                subscriptionId = subscriptionId,
            )
        }
        if (fresh.isNotEmpty()) {
            profiles = current + fresh
        }
        fresh.size
    }

    fun removeProfile(id: String) {
        synchronized(lock) {
            profiles = profiles.filterNot { it.id == id }
            if (selectedProfileId == id) selectedProfileId = ""
        }
    }

    fun renameProfile(id: String, name: String) {
        synchronized(lock) {
            profiles = profiles.map { if (it.id == id) it.copy(name = name) else it }
        }
    }

    /** Заменяет профили подписки на свежие, сохраняя выбор пользователя. */
    fun replaceSubscriptionProfiles(subscriptionId: String, fresh: List<ServerProfile>) {
        synchronized(lock) {
            val kept = profiles.filter { it.subscriptionId != subscriptionId }
            profiles = kept + fresh
            if (selectedProfileId.isNotEmpty() && profile(selectedProfileId) == null) {
                selectedProfileId = ""
            }
        }
    }

    // endregion

    // region subscriptions

    fun addSubscription(source: SubscriptionSource): SubscriptionSource = synchronized(lock) {
        subscriptions.firstOrNull { it.url == source.url }?.let { return it }
        subscriptions = subscriptions + source
        source
    }

    fun subscription(id: String): SubscriptionSource? = subscriptions.firstOrNull { it.id == id }

    fun removeSubscription(id: String) {
        synchronized(lock) {
            subscriptions = subscriptions.filterNot { it.id == id }
            profiles = profiles.filterNot { it.subscriptionId == id }
        }
    }

    fun updateSubscription(source: SubscriptionSource) {
        synchronized(lock) {
            subscriptions = subscriptions.map { if (it.id == source.id) source else it }
        }
    }

    // endregion

    fun toUiModel(activeProfileId: String): JSONObject = JSONObject().apply {
        val subscriptionNames = subscriptions.associate { it.id to it.name }
        put("connectedAt", connectedAt)

        put("profiles", JSONArray().apply {
            profiles.forEach { profile ->
                val vless = runCatching { VlessProfile.parse(profile.link) }.getOrNull()
                val olcrtc = runCatching { OlcrtcProfile.parse(profile.link) }.getOrNull()
                put(JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.resolveName())
                    put("detail", profile.describe())
                    put("source", profile.source.code)
                    put("fromSubscription", profile.subscriptionId != null)
                    put("subscriptionId", profile.subscriptionId ?: JSONObject.NULL)
                    put("subscriptionName", profile.subscriptionId
                        ?.let { subscriptionNames[it].orEmpty() }
                        .orEmpty())
                    // Тип ядра: UI по нему рисует подпись и поля карточки.
                    put("type", profile.scheme)
                    // Детали для карточки «i» в профиле.
                    put("address", vless?.address ?: olcrtc?.roomLabel.orEmpty())
                    put("port", vless?.port ?: 0)
                    put("network", vless?.network.orEmpty())
                    put("security", vless?.security.orEmpty())
                    put("sni", vless?.sni.orEmpty())
                    put("provider", olcrtc?.provider.orEmpty())
                    put("transport", olcrtc?.transport.orEmpty())
                    put("room", olcrtc?.room.orEmpty())
                    put("link", profile.link)
                })
            }
        })
        put("subscriptions", JSONArray().apply {
            subscriptions.forEach { subscription ->
                put(JSONObject().apply {
                    put("id", subscription.id)
                    put("name", subscription.name)
                    put("url", subscription.url)
                    // Полный URL в настройках не показываем — только домен, он помещается в строку.
                    put("host", hostOf(subscription.url))
                    put("refreshLabel", subscription.refreshLabel)
                    put("updatedLabel", subscription.updatedLabel)
                    put("nextRefreshInMinutes", subscription.nextRefreshInMinutes)
                    // true, пока подписка грузится: кнопка «Обновить» показывает спиннер.
                    put("loading", loadingSubscriptions.contains(subscription.id))
                    put("count", profiles.count { it.subscriptionId == subscription.id })
                })
            }
        })
        put("selectedProfileId", selectedProfileId)
        put("activeProfileId", activeProfileId)
    }

    // region persistence

    private fun <T> read(key: String, factory: (JSONObject) -> T): List<T> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    runCatching { factory(array.getJSONObject(index)) }.getOrNull()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(key: String, items: List<JSONObject>) {
        sp.edit().putString(key, JSONArray(items).toString()).apply()
    }

    // endregion

    /** Домен из URL подписки: полная ссылка в списке не помещается, домен — да. */
    private fun hostOf(url: String): String = runCatching {
        URI(url).host.orEmpty()
    }.getOrDefault("").ifBlank { url }

    private companion object {
        const val NAME = "nodeway_profiles"
        const val KEY_PROFILES = "profiles"
        const val KEY_SUBSCRIPTIONS = "subscriptions"
        const val KEY_SELECTED = "selected_profile_id"
        const val KEY_CONNECTED_AT = "connected_at"
    }
}