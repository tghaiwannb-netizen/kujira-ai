package app.kujira.android.net.mcp

/**
 * Incremental decoder for `text/event-stream` bodies.
 *
 * MCP servers answer a POST with either `application/json` or an SSE stream;
 * when it is SSE we must scan frames until the one whose JSON-RPC id matches
 * our request (the hub always emits `event: message` + `data: {...}`).
 *
 * The decoder is line-oriented so the transport can stop as soon as the
 * matching frame arrives instead of waiting for the end of the body.
 */
class SseEventDecoder {

    private val data = StringBuilder()
    private var hasData = false

    /**
     * Feeds one line (without the trailing `\n`; a trailing `\r` is tolerated).
     * Returns the payloads of all events completed by this line.
     */
    fun feedLine(line: String): List<String> {
        val clean = if (line.endsWith("\r")) line.substring(0, line.length - 1) else line

        // A blank line dispatches the pending event.
        if (clean.isEmpty()) return dispatch()

        // Comment / keep-alive.
        if (clean.startsWith(":")) return emptyList()

        // Fields other than "data" (event:, id:, retry:) do not affect the payload.
        if (!clean.startsWith(DATA_FIELD)) return emptyList()
        if (clean.length > DATA_FIELD.length && clean[DATA_FIELD.length] != ':') return emptyList()

        val value = if (clean.length > DATA_FIELD.length) {
            val raw = clean.substring(DATA_FIELD.length + 1)
            if (raw.startsWith(" ")) raw.substring(1) else raw
        } else {
            ""
        }
        if (hasData) data.append('\n')
        data.append(value)
        hasData = true
        return emptyList()
    }

    /** Flushes a final unterminated event. */
    fun finish(): List<String> = dispatch()

    private fun dispatch(): List<String> {
        if (!hasData) return emptyList()
        val payload = data.toString().trim()
        data.setLength(0)
        hasData = false
        return if (payload.isEmpty()) emptyList() else listOf(payload)
    }

    private companion object {
        const val DATA_FIELD = "data"
    }
}

/** Helpers for complete (already-read) SSE bodies. */
object Sse {

    /** Extracts every `data:` payload from a complete SSE body. */
    fun dataPayloads(raw: String): List<String> {
        val decoder = SseEventDecoder()
        val payloads = mutableListOf<String>()
        for (line in raw.split('\n')) {
            payloads += decoder.feedLine(line)
        }
        payloads += decoder.finish()
        return payloads
    }

    /**
     * Extracts the JSON-RPC frame matching [expectedId] from a complete SSE
     * body, falling back to the last response frame (some servers omit ids on
     * single-response streams).
     */
    fun extractResponse(raw: String, expectedId: kotlinx.serialization.json.JsonElement?): JsonRpcResponse? {
        var last: JsonRpcResponse? = null
        for (payload in dataPayloads(raw)) {
            val frame = JsonRpc.decodeOrNull(payload) ?: continue
            if (!frame.isResponse) continue
            last = frame
            if (expectedId != null && JsonRpc.sameId(frame.id, expectedId)) return frame
        }
        return last
    }
}
