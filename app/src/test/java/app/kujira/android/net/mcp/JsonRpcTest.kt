package app.kujira.android.net.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonRpcTest {

    @Test
    fun `request encodes jsonrpc id method and params`() {
        val frame = JsonRpc.request(7L, "tools/list", McpJson.parseObject("""{"cursor":"abc"}"""))
        assertEquals(
            """{"jsonrpc":"2.0","id":7,"method":"tools/list","params":{"cursor":"abc"}}""",
            frame
        )
    }

    @Test
    fun `request omits params when not provided`() {
        assertEquals("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", JsonRpc.request(1L, "ping"))
    }

    @Test
    fun `notification has no id`() {
        val frame = JsonRpc.notification("notifications/initialized")
        assertEquals("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", frame)
    }

    @Test
    fun `decodes a result frame`() {
        val response = JsonRpc.decode(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","serverInfo":{"name":"SentinelX","version":"3.4.11"}}}"""
        )
        assertTrue(response.isResponse)
        assertEquals("1", response.id?.toString()?.replace("\"", ""))
        assertEquals("SentinelX", response.requireResultObject("initialize").let { McpJson.string(it["serverInfo"], "name") })
        assertNull(response.error)
    }

    @Test
    fun `decodes an error frame`() {
        val response = JsonRpc.decode(
            """{"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"Method not found","data":{"detail":"nope"}}}"""
        )
        assertEquals(JsonRpc.METHOD_NOT_FOUND, response.error?.code)
        assertEquals("Method not found", response.error?.message)
        assertTrue(JsonRpc.isMethodNotFound(response.error))
        assertTrue(response.error!!.messageFor("ping").contains("-32601"))
        assertTrue(response.error!!.messageFor("ping").contains("nope"))
    }

    @Test
    fun `requireResultObject throws the rpc error`() {
        val response = JsonRpc.decode("""{"jsonrpc":"2.0","id":3,"error":{"code":-32000,"message":"boom"}}""")
        try {
            response.requireResultObject("tools/call")
            fail("expected McpException")
        } catch (expected: McpException) {
            assertTrue(expected.message!!.contains("boom"))
        }
    }

    @Test
    fun `decodes a batch`() {
        val frames = JsonRpc.decodeBatch(
            """[{"jsonrpc":"2.0","id":1,"result":{}},{"jsonrpc":"2.0","method":"notifications/progress"}]"""
        )
        assertEquals(2, frames.size)
        assertTrue(frames[0].isResponse)
        assertTrue(!frames[1].isResponse)
    }

    @Test
    fun `sameId tolerates number vs string ids`() {
        assertTrue(JsonRpc.sameId(McpJson.parse("9"), McpJson.parse("\"9\"")))
        assertTrue(JsonRpc.sameId(McpJson.parse("9"), McpJson.parse("9")))
        assertTrue(!JsonRpc.sameId(McpJson.parse("9"), McpJson.parse("10")))
        assertTrue(!JsonRpc.sameId(null, McpJson.parse("9")))
    }

    @Test
    fun `decode rejects non json payloads`() {
        try {
            JsonRpc.decode("<html>nope</html>")
            fail("expected McpException")
        } catch (expected: McpException) {
            val message = expected.message.orEmpty()
            assertTrue(message.contains("非 JSON") || message.contains("意外的 JSON"))
        }
    }

    @Test
    fun `empty text is not a notification`() {
        try {
            JsonRpc.decode("   ")
            fail("expected McpException")
        } catch (expected: McpException) {
            assertTrue(expected.message!!.contains("空响应"))
        }
    }
}
