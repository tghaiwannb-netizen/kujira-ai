package app.kujira.android.domain

import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTranscriptTest {

    private fun assistant(callId: String) = Message(
        id = "assistant",
        conversationId = "c",
        role = Role.ASSISTANT,
        blocks = listOf(
            ContentBlock.Text("running"),
            ContentBlock.ToolCall(callId, "sentinel_exec", """{"command":"df -h"}""", "sentinelx", "SentinelX")
        )
    )

    private fun tool(callId: String, output: String) = Message(
        id = "tool",
        conversationId = "c",
        role = Role.TOOL,
        blocks = listOf(
            ContentBlock.ToolResult(callId, "sentinel_exec", "sentinelx", output, false, 12)
        )
    )

    @Test
    fun `folds tool results into the assistant turn`() {
        val merged = mergeToolResults(listOf(assistant("c1"), tool("c1", "/dev/vda1 40G")))

        assertEquals(2, merged.size)
        assertEquals(Role.TOOL, merged[1].role)
        val result = merged[0].blocks.filterIsInstance<ContentBlock.ToolResult>().single()
        assertEquals("c1", result.callId)
        assertEquals("/dev/vda1 40G", result.output)
    }

    @Test
    fun `does not duplicate results already present`() {
        val already = assistant("c1").copy(
            blocks = assistant("c1").blocks + ContentBlock.ToolResult(
                "c1",
                "sentinel_exec",
                "sentinelx",
                "already there"
            )
        )
        val merged = mergeToolResults(listOf(already, tool("c1", "new copy")))

        val results = merged[0].blocks.filterIsInstance<ContentBlock.ToolResult>()
        assertEquals(1, results.size)
        assertEquals("already there", results.single().output)
    }

    @Test
    fun `keeps transcripts without tool results unchanged`() {
        val messages = listOf(
            Message(conversationId = "c", role = Role.USER, blocks = listOf(ContentBlock.Text("hi"))),
            Message(conversationId = "c", role = Role.ASSISTANT, blocks = listOf(ContentBlock.Text("hello")))
        )
        assertEquals(messages, mergeToolResults(messages))
        assertTrue(mergeToolResults(emptyList()).isEmpty())
    }
}
