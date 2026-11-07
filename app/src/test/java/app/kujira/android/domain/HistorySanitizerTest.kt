package app.kujira.android.domain

import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySanitizerTest {

    private fun assistant(vararg blocks: ContentBlock) = Message(
        id = "a",
        conversationId = "c",
        role = Role.ASSISTANT,
        blocks = blocks.toList()
    )

    private fun tool(vararg results: ContentBlock.ToolResult) = Message(
        id = "t",
        conversationId = "c",
        role = Role.TOOL,
        blocks = results.toList()
    )

    private fun call(id: String) = ContentBlock.ToolCall(id, "sentinel_exec", "{}", "sentinelx", "SentinelX")

    private fun result(id: String) = ContentBlock.ToolResult(id, "sentinel_exec", "sentinelx", "ok")

    @Test
    fun `inline result is split into a tool turn`() {
        val messages = sanitizeHistory(listOf(assistant(ContentBlock.Text("run"), call("c1"), result("c1"))))
        assertEquals(listOf(Role.ASSISTANT, Role.TOOL), messages.map { it.role })
        assertTrue(messages[0].blocks.none { it is ContentBlock.ToolResult })
        assertEquals("c1", messages[1].blocks.filterIsInstance<ContentBlock.ToolResult>().single().callId)
    }

    @Test
    fun `mirrored result does not create a duplicate tool turn`() {
        val messages = sanitizeHistory(
            listOf(
                assistant(ContentBlock.Text("run"), call("c1"), result("c1")),
                tool(result("c1"))
            )
        )
        assertEquals(2, messages.size)
        assertEquals(1, messages.count { it.role == Role.TOOL })
        assertEquals(1, messages.flatMap { it.blocks }.filterIsInstance<ContentBlock.ToolResult>().size)
    }

    @Test
    fun `orphan tool call is dropped`() {
        val messages = sanitizeHistory(listOf(assistant(ContentBlock.Text("run"), call("missing"))))
        val kept = messages.single()
        assertTrue(kept.blocks.filterIsInstance<ContentBlock.ToolCall>().isEmpty())
        assertEquals("run", kept.text)
    }

    @Test
    fun `orphan tool result is dropped`() {
        val messages = sanitizeHistory(listOf(tool(result("missing"))))
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `normal transcript passes through`() {
        val messages = listOf(
            Message(conversationId = "c", role = Role.USER, blocks = listOf(ContentBlock.Text("hi"))),
            assistant(ContentBlock.Text("hello"))
        )
        assertEquals(messages, sanitizeHistory(messages))
        assertFalse(messages.isEmpty())
    }
}
