package app.kujira.android.domain

import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.Role
import app.kujira.android.data.model.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    @Test
    fun `estimates cjk and latin text differently`() {
        assertTrue(TokenEstimator.estimate("你好世界") >= 4)
        assertTrue(TokenEstimator.estimate("hello world") < TokenEstimator.estimate("你好世界"))
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun `counts tool payloads and message overhead`() {
        val message = Message(
            conversationId = "c",
            role = Role.ASSISTANT,
            blocks = listOf(
                ContentBlock.Text("hello"),
                ContentBlock.ToolCall(
                    id = "call",
                    name = "sentinel_exec",
                    argumentsJson = """{"command":"df -h"}""",
                    pluginId = "sentinelx",
                    pluginName = "SentinelX"
                ),
                ContentBlock.ToolResult(
                    callId = "call",
                    toolName = "sentinel_exec",
                    pluginId = "sentinelx",
                    output = "/dev/vda1 40G 12G 26G 32% /"
                )
            )
        )
        assertTrue(TokenEstimator.estimate(message) > TokenEstimator.estimate(message.text))
    }

    @Test
    fun `context usage levels track the window`() {
        assertEquals(ContextLevel.NORMAL, ContextUsage(usedTokens = 10, windowTokens = 100).level)
        assertEquals(ContextLevel.WARN, ContextUsage(usedTokens = 76, windowTokens = 100).level)
        assertEquals(ContextLevel.CRITICAL, ContextUsage(usedTokens = 91, windowTokens = 100).level)
        assertEquals(9, ContextUsage(usedTokens = 91, windowTokens = 100).remainingTokens)
        assertEquals(null, ContextUsage(usedTokens = 10).ratio)
    }

    @Test
    fun `formats token counts and costs for the UI`() {
        assertEquals("999", formatTokenCount(999))
        assertEquals("1.2K", formatTokenCount(1_234))
        assertEquals("1.0M", formatTokenCount(1_000_000))
        assertEquals("$0", formatCost(0.0))
        assertEquals("$0.0041", formatCost(0.00412))
        assertEquals("$1.25", formatCost(1.25))
    }

    @Test
    fun `usage sums cost cache reasoning and keeps billing source`() {
        val first = Usage(
            promptTokens = 10,
            completionTokens = 2,
            totalTokens = 12,
            cacheReadTokens = 5,
            reasoningTokens = 1,
            costUsd = 0.1,
            billingSource = "go",
            allowanceUsd = 0.01
        )
        val second = Usage(
            promptTokens = 1,
            completionTokens = 1,
            totalTokens = 2,
            costUsd = 0.2
        )
        val sum = first + second
        assertEquals(11, sum.promptTokens)
        assertEquals(3, sum.completionTokens)
        assertEquals(14, sum.totalTokens)
        assertEquals(5, sum.cacheReadTokens)
        assertEquals(1, sum.reasoningTokens)
        assertEquals(0.3, sum.costUsd!!, 0.0000001)
        assertEquals("go", sum.billingSource)
        assertEquals(0.01, sum.allowanceUsd!!, 0.0000001)
    }

    @Test
    fun `transcript includes tool calls and results`() {
        val messages = listOf(
            Message(
                conversationId = "c",
                role = Role.USER,
                blocks = listOf(ContentBlock.Text("查磁盘"))
            ),
            Message(
                conversationId = "c",
                role = Role.ASSISTANT,
                blocks = listOf(
                    ContentBlock.Text("我看一下。"),
                    ContentBlock.ToolCall("1", "sentinel_exec", """{"command":"df -h"}""", "sentinelx", "SentinelX")
                )
            ),
            Message(
                conversationId = "c",
                role = Role.TOOL,
                blocks = listOf(
                    ContentBlock.ToolResult("1", "sentinel_exec", "sentinelx", "/dev/vda1 40G")
                )
            )
        )
        val transcript = TokenEstimator.transcript(messages)
        assertTrue(transcript.contains("用户：查磁盘"))
        assertTrue(transcript.contains("sentinel_exec"))
        assertTrue(transcript.contains("/dev/vda1"))
    }
}
