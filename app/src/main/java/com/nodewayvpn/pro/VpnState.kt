package com.nodewayvpn.pro

/** Состояние туннеля в терминах, которые понимает UI. */
enum class VpnState(val code: String) {
    DISCONNECTED("disconnected"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    DISCONNECTING("disconnecting"),
    ERROR("error"),

    /**
     * Идёт проверка профилей: туннель поочерёдно поднимается на каждом из них.
     *
     * Отдельное состояние, а не флаг, потому что туннель тут то поднимается, то
     * гаснет — показывать пользователю мельтешение «Подключено / Отключено»
     * было бы враньём. Кнопка питания при этом тёмная и не нажимается.
     */
    PINGING("pinging"),
}

/**
 * Состояние туннеля целиком — всё, что Activity знает о текущем подключении.
 *
 * Нужно потому, что сервис переживает смахивание приложения из недавних: процесс
 * с foreground-сервисом остаётся жив, а Activity создаётся заново и без события
 * от сервиса показывала бы «Отключено» при живом туннеле. Сервис и Activity
 * находятся в одном процессе, поэтому снимок читается напрямую, без IPC.
 */
data class VpnSnapshot(
    val state: VpnState,
    val profileId: String,
    val connectedAt: Long,
    /** Отклик через туннель в мс. 0 — замера ещё не было или туннель не ответил. */
    val latencyMs: Int = 0,
    /** Идёт проверка профилей: номер текущего (с нуля) и всего. */
    val pingIndex: Int = 0,
    val pingTotal: Int = 0,
    /** Профиль, который проверяется прямо сейчас. */
    val pingProfileId: String = "",
    /**
     * Результаты проверки: id профиля → отклик в мс, либо -1, если не отвечает.
     *
     * Живут только в памяти процесса и намеренно не сохраняются: на устройстве
     * не должно оставаться записи «к каким адресам и когда стучались».
     */
    val pingResults: Map<String, Int> = emptyMap(),
) {
    companion object {
        val DISCONNECTED = VpnSnapshot(VpnState.DISCONNECTED, "", 0L)
    }
}

/**
 * Ход проверки профилей в виде, удобном Activity: не счётчики полей снимка,
 * а готовые к вопросу «идёт ли проверка».
 */
data class PingProgress(
    val index: Int = 0,
    val total: Int = 0,
    val profileId: String = "",
    val results: Map<String, Int> = emptyMap(),
) {
    /** Проверка идёт: очередь непуста и ещё не пройдена. */
    val running: Boolean
        get() = total > 0 && index < total
}