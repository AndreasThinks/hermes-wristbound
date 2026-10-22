package dev.wristbound.companion.hermes

/** One server-sent event: the `event:` name (or "message") and its joined `data:` lines. */
data class SseEvent(val name: String, val data: String, val id: String? = null)

/**
 * Incremental SSE line parser. Comment lines (`: keepalive`) are skipped, as the Hermes docs
 * require of custom parsers.
 */
class SseParser {
    private var name: String? = null
    private var id: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Feed one line without its terminator. Returns an event when a blank line completes one. */
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> name = value
            "id" -> id = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    /** Emit a pending event, e.g. when the stream ends without a trailing blank line. */
    fun flush(): SseEvent? {
        val event = if (hasData) SseEvent(name ?: "message", data.toString(), id) else null
        name = null
        id = null
        data.setLength(0)
        hasData = false
        return event
    }
}
