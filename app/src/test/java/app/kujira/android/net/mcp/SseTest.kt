package app.kujira.android.net.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseTest {

    /** Shape captured from the live SentinelX hub (event line + CRLF + blank line). */
    private val liveFrame = "event: message\r\n" +
        "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}\r\n" +
        "\r\n"

    @Test
    fun `decodes one live frame`() {
        val payloads = Sse.dataPayloads(liveFrame)
        assertEquals(1, payloads.size)
        val frame = JsonRpc.decode(payloads[0])
        assertEquals("2025-06-18", McpJson.string(frame.result, "protocolVersion"))
    }

    @Test
    fun `multi line data is joined with newline`() {
        val body = "data: line one\ndata: line two\n\n"
        assertEquals(listOf("line one\nline two"), Sse.dataPayloads(body))
    }

    @Test
    fun `comments and keep alives are ignored`() {
        val body = ": keep-alive\n\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n"
        val payloads = Sse.dataPayloads(body)
        assertEquals(1, payloads.size)
        assertTrue(payloads[0].startsWith("{\"jsonrpc\""))
    }

    @Test
    fun `unterminated final event is flushed`() {
        assertEquals(listOf("tail"), Sse.dataPayloads("data: tail"))
    }

    @Test
    fun `extractResponse picks the frame matching the request id`() {
        val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"a\":1}}\n\n" +
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"b\":2}}\n\n"
        val frame = Sse.extractResponse(body, McpJson.parse("2"))
        assertEquals("2", (frame?.result as? kotlinx.serialization.json.JsonObject)?.get("b")?.toString())
    }

    @Test
    fun `extractResponse falls back to the last response frame`() {
        val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"}\n\n" +
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"result\":{\"ok\":true}}\n\n"
        val frame = Sse.extractResponse(body, McpJson.parse("99"))
        assertTrue(frame?.result != null)
    }

    @Test
    fun `extractResponse returns null for garbage`() {
        assertNull(Sse.extractResponse("event: message\ndata: not-json\n\n", McpJson.parse("1")))
        assertNull(Sse.extractResponse("", McpJson.parse("1")))
    }

    @Test
    fun `incremental decoder feeds line by line`() {
        val decoder = SseEventDecoder()
        assertTrue(decoder.feedLine("event: message").isEmpty())
        assertTrue(decoder.feedLine("data: {\"a\":1}").isEmpty())
        assertEquals(listOf("{\"a\":1}"), decoder.feedLine(""))
        assertTrue(decoder.feedLine("").isEmpty())
    }
}
