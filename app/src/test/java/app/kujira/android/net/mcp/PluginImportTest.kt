package app.kujira.android.net.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginImportTest {

    @Test
    fun `imports claude desktop http servers`() {
        val result = PluginImport.parse(
            """
            {
              "mcpServers": {
                "我的知识库": { "url": "https://mcp.example.com/mcp", "headers": { "X-Api-Key": "abc" } },
                "另一个": { "type": "http", "url": "http://10.0.0.2:8080/mcp" }
              }
            }
            """.trimIndent()
        )
        assertNull(result.error)
        assertEquals(2, result.plugins.size)
        val first = result.plugins.first { it.name == "我的知识库" }
        assertEquals("https://mcp.example.com/mcp", first.url)
        assertEquals("abc", first.headers["X-Api-Key"])
        assertEquals("🔌", first.emoji)
    }

    @Test
    fun `accepts a bare server map`() {
        val result = PluginImport.parse("""{"only": {"url": "https://x.dev/mcp"}}""")
        assertEquals(1, result.plugins.size)
        assertEquals("only", result.plugins[0].name)
    }

    @Test
    fun `reads url from nested transport and server`() {
        val result = PluginImport.parse(
            """{"mcpServers": {"a": {"transport": {"type": "http", "url": "https://a.dev/sse"}}, "b": {"server": {"url": "https://b.dev/mcp"}}}}"""
        )
        assertEquals(setOf("https://a.dev/sse", "https://b.dev/mcp"), result.plugins.map { it.url }.toSet())
    }

    @Test
    fun `skips stdio servers with a reason`() {
        val result = PluginImport.parse(
            """{"mcpServers": {"本地": {"command": "npx", "args": ["-y", "server"]}, "远程": {"url": "https://x.dev/mcp"}}}"""
        )
        assertEquals(1, result.plugins.size)
        assertEquals(1, result.skipped.size)
        assertEquals("本地", result.skipped[0].name)
        assertTrue(result.skipped[0].reason.contains("stdio"))
    }

    @Test
    fun `preserves authorization header and warns about it`() {
        val result = PluginImport.parse(
            """{"mcpServers": {"鉴权": {"url": "https://x.dev/mcp", "headers": {"Authorization": "Bearer secret-token"}}}}"""
        )
        assertEquals("Bearer secret-token", result.plugins[0].headers["Authorization"])
        assertTrue(result.warnings.any { it.contains("Authorization") })
    }

    @Test
    fun `rejects garbage`() {
        assertEquals("不是合法的 JSON", PluginImport.parse("{oops").error)
        assertEquals("请先粘贴 mcpServers JSON", PluginImport.parse("   ").error)
        assertEquals("没有找到任何服务器配置", PluginImport.parse("""{"mcpServers":{}}""").error)
    }

    @Test
    fun `skips non http urls`() {
        val result = PluginImport.parse("""{"mcpServers": {"ws": {"url": "ws://x.dev/mcp"}}}""")
        assertTrue(result.plugins.isEmpty())
        assertEquals(1, result.skipped.size)
    }
}
