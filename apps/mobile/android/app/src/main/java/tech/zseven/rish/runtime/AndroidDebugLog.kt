package tech.zseven.rish.runtime

import java.util.ArrayDeque

/**
 * The in-app debug log: what the native layers did, step by step, on the
 * device in a person's hand.
 *
 * Logcat needs a cable and a desktop; a person iterating on a phone has
 * neither. Every entry here is also echoed to logcat, but the buffer is what
 * the app itself can export -- a bounded ring, newest last, cleared only by
 * the person.
 *
 * **What may be written here is bounded on purpose.** An operation name, a
 * step, and a short detail. No file contents, no credentials, no message
 * text: the log describes what the code did, never what the person wrote.
 */
internal object AndroidDebugLog {
    private const val CAPACITY = 2000
    private const val MAX_DETAIL = 300

    private val lock = Any()
    private val entries = ArrayDeque<String>(CAPACITY)

    /** One step of one operation, e.g. `log("saf_picker", "grant_persisted")`. */
    fun log(op: String, step: String, detail: String? = null) {
        val trimmed = detail?.take(MAX_DETAIL)
        val line = buildString {
            append(RuntimeJson.now())
            append(' ').append(op).append(' ').append(step)
            if (!trimmed.isNullOrEmpty()) append(' ').append(trimmed)
        }
        android.util.Log.i("RishDebugLog", line)
        synchronized(lock) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(line)
        }
    }

    /** Every retained line, oldest first, as one exportable text. */
    fun export(): String = synchronized(lock) { entries.joinToString("\n") }

    fun clear() = synchronized(lock) { entries.clear() }
}
