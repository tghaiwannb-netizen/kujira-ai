package app.kujira.android.net.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpContentTest {

    @Test
    fun `joins text blocks`() {
        val result = McpJson.parseObject(
            """{"content":[{"type":"text","text":"hello"},{"type":"text","text":"world"}],"isError":false}"""
        )!!
        val toolResult = McpContent.toToolResult(result)
        assertEquals("hello\nworld", toolResult.output)
        assertFalse(toolResult.isError)
    }

    @Test
    fun `marks isError`() {
        val result = McpJson.parseObject(
            """{"content":[{"type":"text","text":"path_not_allowed"}],"isError":true}"""
        )!!
        assertTrue(McpContent.toToolResult(result).isError)
    }

    @Test
    fun `image blocks are summarised instead of dumping base64`() {
        val result = McpJson.parseObject(
            """{"content":[{"type":"image","data":"AAAA","mimeType":"image/png"}]}"""
        )!!
        val output = McpContent.toToolResult(result).output
        assertTrue(output.startsWith("[图片 image/png"))
        assertFalse(output.contains("AAAA"))
    }

    @Test
    fun `resource blocks expose text or uri`() {
        assertEquals(
            "file body",
            McpContent.renderBlock(
                McpJson.parse("""{"type":"resource","resource":{"uri":"file:///a","text":"file body"}}""")!!
            )
        )
        assertEquals(
            "[资源] file:///a",
            McpContent.renderBlock(
                McpJson.parse("""{"type":"resource","resource":{"uri":"file:///a"}}""")!!
            )
        )
    }

    @Test
    fun `resource link renders name and uri`() {
        val rendered = McpContent.renderBlock(
            McpJson.parse("""{"type":"resource_link","name":"报告","uri":"https://x/y"}""")!!
        )
        assertTrue(rendered!!.contains("报告"))
        assertTrue(rendered.contains("https://x/y"))
    }

    @Test
    fun `structured content is used when there are no content blocks`() {
        val result = McpJson.parseObject(
            """{"content":[],"structuredContent":{"ok":true,"pong":true}}"""
        )!!
        val toolResult = McpContent.toToolResult(result)
        assertEquals("{\"ok\":true,\"pong\":true}", toolResult.output)
        assertEquals("{\"ok\":true,\"pong\":true}", toolResult.structuredJson)
    }

    @Test
    fun `empty result yields a placeholder`() {
        assertEquals("(空结果)", McpContent.toToolResult(McpJson.parseObject("{}")!!).output)
    }
}
