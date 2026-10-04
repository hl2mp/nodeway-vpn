package com.nodewayvpn.pro

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Журнал приложения: события UI, вывод olcrtc и Xray-core в одном списке.
 *
 * Пишут все, кто заинтересован — сервис, процессы ядер, мост из WebView, — с
 * разных потоков, читает страница на главном. Поэтому записи лежат в кольцевом
 * буфере под замком, а подписчику отдаётся копия: список переживает пересоздание
 * WebView, и журнал не пропадает при повороте экрана.
 *
 * Буфер конечен намеренно. Xray на уровне `info` пишет строку на каждое соединение,
 * и без ограничения журнал разросся бы быстрее, чем его успевают читать.
 */
object Journal {

    /** События приложения: подключение, смена профиля, ошибки ввода. */
    const val SOURCE_APP = "app"

    /** Вывод клиента olcrtc. */
    const val SOURCE_OLCRTC = "olcrtc"

    /** Вывод ядра Xray. */
    const val SOURCE_XRAY = "xray"

    const val LEVEL_INFO = "info"
    const val LEVEL_WARN = "warn"
    const val LEVEL_ERROR = "error"

    data class Entry(
        val at: Long,
        val source: String,
        val text: String,
        val level: String = LEVEL_INFO,
    )

    fun interface Listener {
        fun onJournalEntries(entries: List<Entry>)
    }

    /** Сколько записей держим до вытеснения старых. */
    private const val LIMIT = 400

    /**
     * Выключатель из настроек.
     *
     * Пока выключен, [append] ничего не пишет: и ядра, и сервис зовут его без
     * оглядки на настройку, поэтому проверка живёт здесь, а не в сотне мест.
     */
    @Volatile
    var enabled: Boolean = true

    /** Включает или выключает журнал. При выключении накопленное стирается. */
    fun applyEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) clear()
    }

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(LIMIT)
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Подписывает слушателя и отдаёт накопленный буфер — чтобы не терять записи. */
    fun subscribe(listener: Listener): List<Entry> = synchronized(lock) {
        listeners += listener
        buffer.toList()
    }

    fun unsubscribe(listener: Listener) {
        listeners -= listener
    }

    /** Добавляет одну запись. Уровень выводится из текста, если не задан явно. */
    fun append(source: String, text: String, level: String? = null) {
        if (!enabled) return
        val clean = text.trim()
        if (clean.isEmpty()) return

        val entry = Entry(
            at = System.currentTimeMillis(),
            source = source,
            text = clean,
            level = level ?: levelOf(clean),
        )
        val batch = synchronized(lock) {
            if (buffer.size >= LIMIT) buffer.removeFirst()
            buffer.addLast(entry)
            listOf(entry)
        }
        listeners.forEach { runCatching { it.onJournalEntries(batch) } }
    }

    /**
     * Добавляет пачку строк одним событием.
     *
     * Вывод ядер приходит построчно, но странице дешевле получить сразу пачку,
     * чем двадцать вызовов `evaluateJavascript`.
     */
    fun appendAll(source: String, lines: List<String>) {
        if (lines.isEmpty() || !enabled) return
        lines.forEach { append(source, it) }
    }

    /** Стирает накопленное, но не трогает подписчиков. */
    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    /**
     * Убирает повторяющийся префикс `2006/01/02 15:04:05.000000 `.
     *
     * Его пишут оба ядра, а в журнале своя колонка времени — вдвое короче строка
     * и без визуального шума.
     */
    fun stripTimestamp(line: String): String = TIMESTAMP_PREFIX.replace(line, "").trim()

    /**
     * Определяет серьёзность строки по её началу.
     *
     * Оба ядра пишут уровень в начале строки, но по-разному: Xray — в квадратных
     * скобках, olcrtc — словом с двоеточием. Остальное считаем обычным сообщением:
     * в тексте ядер «error» встречается и в безобидных местах.
     */
    fun levelOf(text: String): String {
        XRAY_LEVEL.find(text)?.let {
            return when (it.groupValues[1]) {
                "Error" -> LEVEL_ERROR
                "Warning" -> LEVEL_WARN
                else -> LEVEL_INFO
            }
        }
        return if (GO_LEVEL.containsMatchIn(text)) LEVEL_ERROR else LEVEL_INFO
    }

    private val TIMESTAMP_PREFIX =
        Regex("^\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\s+")

    private val XRAY_LEVEL = Regex("^\\[(Info|Warning|Error|Debug)]")

    /** Строка от olcrtc начинается с уровня либо несёт его в начале после префикса. */
    private val GO_LEVEL = Regex("^.*?\\b(FATAL|ERROR|ERRO|WARN)\\b")
}