package chat.homebuddy.core.network

/**
 * Server-sent events, one line at a time. `data:` lines collect until a blank line ends the
 * event. Comment lines (": ping", the server's keepalive) and other fields are ignored.
 */
class SseParser {
    private val data = StringBuilder()
    private var hasData = false

    /** Feed one line (without its line break). Returns an event's data when the line completes one. */
    fun feed(line: String): String? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val field = line.substringBefore(':')
        val value = line.substringAfter(':', missingDelimiterValue = "").removePrefix(" ")
        if (field == "data") {
            if (hasData) data.append('\n')
            data.append(value)
            hasData = true
        }
        return null
    }

    /** At the end of the stream: an event that wasn't followed by a blank line still counts. */
    fun finish(): String? = flush()

    private fun flush(): String? {
        if (!hasData) return null
        return data.toString().also {
            data.clear()
            hasData = false
        }
    }
}
