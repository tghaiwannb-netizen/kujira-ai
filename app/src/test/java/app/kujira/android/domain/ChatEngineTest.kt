package app.kujira.android.domain

import app.kujira.android.data.model.AppSettings
import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.data.model.Role
import app.kujira.android.data.model.ToolDescriptor
import app.kujira.android.data.model.Usage
import app.kujira.android.net.llm.LlmEvent
import app.kujira.android.net.llm.LlmRequest
import app.kujira.android.net.llm.LlmStreamer
import app.kujira.android.net.llm.LlmToolCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests for the tool-call loop using a fake [LlmStreamer] and a
 * fake [ToolExecutor]: no network, no Android, no MCP.
 */
class ChatEngineTest {

    private val provider = ProviderConfig(
        id = "p1",
        kind = ProviderKind.DEEPSEEK,
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        models = listOf(ModelInfo("deepseek-chat")),
        defaultModelId = "deepseek-chat"
    )

    private val pingTool = ToolDescriptor(
        pluginId = "sentinelx",
        pluginName = "SentinelX",
        name = "sentinel_ping",
        description = "ping a host",
        inputSchemaJson = """{"type":"object","properties":{"host_id":{"type":"string"}}}"""
    )

    private fun settings(
        loopLimit: Int = 3,
        showReasoning: Boolean = true,
        systemPrompt: String = "",
    ) = AppSettings(
        toolLoopLimit = loopLimit,
        showReasoning = showReasoning,
        temperature = 0.5,
        topP = 0.8,
        maxTokens = 256,
        systemPrompt = systemPrompt
    )

    private fun request(
        history: List<Message> = emptyList(),
        loopLimit: Int = 3,
        showReasoning: Boolean = true,
        toolsEnabled: Boolean = true,
        systemPromptOverride: String? = null,
    ) = ChatRequest(
        conversationId = "c1",
        history = history,
        userText = "你好",
        provider = provider,
        apiKey = "sk-test",
        modelId = "deepseek-chat",
        settings = settings(loopLimit, showReasoning),
        systemPrompt = systemPromptOverride,
        toolsEnabled = toolsEnabled
    )

    private class FakeStreamer(private val rounds: List<List<LlmEvent>>) : LlmStreamer {
        val requests = mutableListOf<LlmRequest>()

        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            requests += request
            rounds.getOrNull(requests.size - 1)?.forEach { emit(it) }
        }
    }

    private class FakeToolExecutor(
        private val tools: List<ToolDescriptor>,
        private val result: ToolExecResult = ToolExecResult("pong"),
    ) : ToolExecutor {
        val calls = mutableListOf<Pair<String, String>>()

        override fun availableTools(): List<ToolDescriptor> = tools

        override suspend fun execute(tool: ToolDescriptor, argumentsJson: String): ToolExecResult {
            calls += tool.name to argumentsJson
            return result
        }
    }

    private fun engine(streamer: LlmStreamer, executor: ToolExecutor?) =
        ChatEngine(streamer, { executor })

    @Test
    fun `plain answer emits deltas then one completed message`() = runBlocking {
        val streamer = FakeStreamer(
            listOf(
                listOf(
                    LlmEvent.TextDelta("你"),
                    LlmEvent.TextDelta("好"),
                    LlmEvent.Finished(usage = Usage(10, 2, 12), finishReason = "stop")
                )
            )
        )

        val events = engine(streamer, FakeToolExecutor(listOf(pingTool))).send(request()).toList()

        assertEquals(listOf("你", "好"), events.filterIsInstance<ChatEvent.TextDelta>().map { it.text })
        val completed = events.last() as ChatEvent.Completed
        assertEquals(1, completed.rounds)
        assertFalse(completed.toolLimitReached)
        assertEquals(1, completed.messages.size)

        val message = completed.messages.single()
        assertEquals(Role.ASSISTANT, message.role)
        assertEquals("你好", message.text)
        assertEquals("p1", message.providerId)
        assertEquals("deepseek-chat", message.modelId)
        assertEquals(Usage(10, 2, 12), completed.usage)
        assertEquals(Usage(10, 2, 12), message.usage)

        val firstRequest = streamer.requests.single()
        assertEquals("deepseek-chat", firstRequest.model)
        assertEquals("sk-test", firstRequest.apiKey)
        assertEquals(listOf(Role.USER), firstRequest.messages.map { it.role })
        assertEquals("你好", firstRequest.messages.single().text)
        assertEquals(listOf("sentinel_ping"), firstRequest.tools.map { it.name })
        assertEquals(0.5, firstRequest.temperature, 0.0001)
        assertEquals(0.8, firstRequest.topP, 0.0001)
        assertEquals(256, firstRequest.maxTokens)
    }

    @Test
    fun `tool call round executes the tool and feeds the result back`() = runBlocking {
        val streamer = FakeStreamer(
            listOf(
                listOf(
                    LlmEvent.Finished(
                        toolCalls = listOf(
                            LlmToolCall("c1", "sentinel_ping", """{"host_id":"h1"}""")
                        ),
                        usage = Usage(5, 1, 6),
                        finishReason = "tool_calls"
                    )
                ),
                listOf(
                    LlmEvent.TextDelta("完成"),
                    LlmEvent.Finished(usage = Usage(6, 2, 8), finishReason = "stop")
                )
            )
        )
        val executor = FakeToolExecutor(listOf(pingTool), ToolExecResult("pong", false, 12))

        val events = engine(streamer, executor).send(request()).toList()

        val started = events.filterIsInstance<ChatEvent.ToolStarted>().single()
        assertEquals("c1", started.callId)
        assertEquals("sentinel_ping", started.toolName)
        assertEquals("sentinelx", started.pluginId)
        assertEquals("SentinelX", started.pluginName)
        assertEquals("""{"host_id":"h1"}""", started.argumentsJson)

        val finished = events.filterIsInstance<ChatEvent.ToolFinished>().single()
        assertEquals("pong", finished.output)
        assertFalse(finished.isError)
        assertEquals(12L, finished.durationMs)

        assertEquals(listOf("sentinel_ping" to """{"host_id":"h1"}"""), executor.calls)

        val completed = events.last() as ChatEvent.Completed
        assertEquals(2, completed.rounds)
        assertEquals(listOf(Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), completed.messages.map { it.role })
        assertEquals("完成", completed.messages.last().text)
        assertEquals(Usage(11, 3, 14), completed.usage)

        val callBlock = completed.messages[0].blocks.filterIsInstance<ContentBlock.ToolCall>().single()
        assertEquals("c1", callBlock.id)
        assertEquals("sentinelx", callBlock.pluginId)

        val resultBlock = completed.messages[1].blocks.filterIsInstance<ContentBlock.ToolResult>().single()
        assertEquals("c1", resultBlock.callId)
        assertEquals("pong", resultBlock.output)
        assertFalse(resultBlock.isError)

        // The second model call must include the assistant tool-call turn and the tool result.
        assertEquals(2, streamer.requests.size)
        val second = streamer.requests[1].messages
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL), second.map { it.role })
        assertEquals("sentinel_ping", second[1].toolCalls.single().name)
        assertEquals("c1", second[2].toolCallId)
        assertTrue(second[2].text.contains("pong"))
    }

    @Test
    fun `tool errors are fed back and the loop continues`() = runBlocking {
        val streamer = FakeStreamer(
            listOf(
                listOf(
                    LlmEvent.Finished(
                        toolCalls = listOf(LlmToolCall("c1", "sentinel_ping", "{}")),
                        finishReason = "tool_calls"
                    )
                ),
                listOf(LlmEvent.TextDelta("工具挂了，但我还能回答"), LlmEvent.Finished())
            )
        )
        val executor = FakeToolExecutor(listOf(pingTool), ToolExecResult("connection refused", isError = true))

        val events = engine(streamer, executor).send(request()).toList()

        val finished = events.filterIsInstance<ChatEvent.ToolFinished>().single()
        assertTrue(finished.isError)
        assertEquals("connection refused", finished.output)

        val completed = events.last() as ChatEvent.Completed
        val resultBlock = completed.messages[1].blocks.filterIsInstance<ContentBlock.ToolResult>().single()
        assertTrue(resultBlock.isError)

        // The model sees the failure marker, not a bare string.
        assertTrue(streamer.requests[1].messages.last().text.startsWith("工具执行失败："))
    }

    @Test
    fun `unknown tool is reported without crashing the loop`() = runBlocking {
        val streamer = FakeStreamer(
            listOf(
                listOf(
                    LlmEvent.Finished(
                        toolCalls = listOf(LlmToolCall("c9", "missing_tool", "{}")),
                        finishReason = "tool_calls"
                    )
                ),
                listOf(LlmEvent.TextDelta("done"), LlmEvent.Finished())
            )
        )

        val events = engine(streamer, FakeToolExecutor(listOf(pingTool))).send(request()).toList()

        val finished = events.filterIsInstance<ChatEvent.ToolFinished>().single()
        assertTrue(finished.isError)
        assertTrue(finished.output.contains("未知工具"))
        assertTrue(events.last() is ChatEvent.Completed)
    }

    @Test
    fun `tool loop limit stops further tool calls`() = runBlocking {
        val rounds = List(3) {
            listOf(
                LlmEvent.Finished(
                    toolCalls = listOf(LlmToolCall("c$it", "sentinel_ping", "{}")),
                    finishReason = "tool_calls"
                )
            )
        }
        val streamer = FakeStreamer(rounds)
        val executor = FakeToolExecutor(listOf(pingTool))

        val events = engine(streamer, executor).send(request(loopLimit = 2)).toList()

        val completed = events.last() as ChatEvent.Completed
        assertEquals(2, completed.rounds)
        assertTrue(completed.toolLimitReached)
        assertEquals(2, streamer.requests.size)
        // Round 1 executed the tool; round 2 hit the limit before executing.
        assertEquals(1, executor.calls.size)
        assertTrue(completed.messages.last().text.contains("上限"))
    }

    @Test
    fun `stream failure becomes a failed event with partial text`() = runBlocking {
        val streamer = FakeStreamer(
            listOf(
                listOf(
                    LlmEvent.TextDelta("已经写了一半"),
                    LlmEvent.Failed("API Key 无效或已过期（401）", 401, false)
                )
            )
        )

        val events = engine(streamer, FakeToolExecutor(listOf(pingTool))).send(request()).toList()

        val failed = events.last() as ChatEvent.Failed
        assertEquals("API Key 无效或已过期（401）", failed.message)
        assertEquals(401, failed.httpCode)
        assertFalse(failed.retryable)
        assertEquals("已经写了一半", failed.partialText)
        assertTrue(failed.partialMessages.isEmpty())
        assertTrue(events.none { it is ChatEvent.Completed })
    }

    @Test
    fun `failure after a tool round keeps the already produced messages`() = runBlocking {
        val streamer = FakeStreamer(
            listOf(
                listOf(
                    LlmEvent.Finished(
                        toolCalls = listOf(LlmToolCall("c1", "sentinel_ping", "{}")),
                        finishReason = "tool_calls"
                    )
                ),
                listOf(LlmEvent.TextDelta("半句"), LlmEvent.Failed("服务端错误（500）", 500, true))
            )
        )

        val events = engine(streamer, FakeToolExecutor(listOf(pingTool))).send(request()).toList()

        val failed = events.last() as ChatEvent.Failed
        assertEquals(500, failed.httpCode)
        assertTrue(failed.retryable)
        assertEquals(2, failed.partialMessages.size)
        assertEquals(Role.ASSISTANT, failed.partialMessages[0].role)
        assertEquals(Role.TOOL, failed.partialMessages[1].role)
    }

    @Test
    fun `reasoning deltas are hidden when showReasoning is off but kept in the message`() = runBlocking {
        val streamer = FakeStreamer(
            listOf(
                listOf(
                    LlmEvent.ReasoningDelta("默默思考"),
                    LlmEvent.TextDelta("答案"),
                    LlmEvent.Finished()
                )
            )
        )

        val events = engine(streamer, FakeToolExecutor(listOf(pingTool)))
            .send(request(showReasoning = false))
            .toList()

        assertTrue(events.filterIsInstance<ChatEvent.ReasoningDelta>().isEmpty())
        val message = (events.last() as ChatEvent.Completed).messages.last()
        assertEquals("默默思考", message.reasoning)
        assertEquals("答案", message.text)
    }

    @Test
    fun `tools are not advertised and never called when tools are disabled`() = runBlocking {
        val streamer = FakeStreamer(
            listOf(listOf(LlmEvent.TextDelta("hi"), LlmEvent.Finished()))
        )
        val executor = FakeToolExecutor(listOf(pingTool))

        val events = engine(streamer, executor).send(request(toolsEnabled = false)).toList()

        assertTrue(events.last() is ChatEvent.Completed)
        assertTrue(streamer.requests.single().tools.isEmpty())
        assertTrue(executor.calls.isEmpty())
    }

    @Test
    fun `history is converted to turns without replaying reasoning`() = runBlocking {
        val history = listOf(
            Message(
                conversationId = "c1",
                role = Role.USER,
                blocks = listOf(ContentBlock.Text("之前的提问"))
            ),
            Message(
                conversationId = "c1",
                role = Role.ASSISTANT,
                blocks = listOf(
                    ContentBlock.Reasoning("旧思考"),
                    ContentBlock.Text("旧回答"),
                    ContentBlock.ToolCall("c0", "sentinel_ping", "{}", "sentinelx", "SentinelX")
                )
            ),
            Message(
                conversationId = "c1",
                role = Role.TOOL,
                blocks = listOf(
                    ContentBlock.ToolResult("c0", "sentinel_ping", "sentinelx", "pong")
                )
            )
        )

        val streamer = FakeStreamer(
            listOf(listOf(LlmEvent.TextDelta("新回答"), LlmEvent.Finished()))
        )
        engine(streamer, FakeToolExecutor(listOf(pingTool))).send(request(history = history)).toList()

        val sent = streamer.requests.single().messages
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER), sent.map { it.role })
        assertEquals("之前的提问", sent[0].text)
        assertEquals("旧回答", sent[1].text)
        assertNull(sent[1].reasoning)
        assertEquals("c0", sent[1].toolCalls.single().id)
        assertEquals("c0", sent[2].toolCallId)
        assertEquals("pong", sent[2].text)
        assertEquals("你好", sent[3].text)
    }

    @Test
    fun `system prompt override and settings prompt reach the request`() = runBlocking {
        val streamer = FakeStreamer(
            listOf(listOf(LlmEvent.TextDelta("ok"), LlmEvent.Finished()))
        )
        val overrideEngine = engine(streamer, FakeToolExecutor(listOf(pingTool)))

        overrideEngine
            .send(request(systemPromptOverride = "会话级提示词"))
            .toList()
        assertEquals("会话级提示词", streamer.requests[0].systemPrompt)

        val settingsStreamer = FakeStreamer(
            listOf(listOf(LlmEvent.TextDelta("ok"), LlmEvent.Finished()))
        )
        val settingsEngine = ChatEngine(settingsStreamer, toolExecutorProvider = { FakeToolExecutor(listOf(pingTool)) })
        settingsEngine.send(
            request().copy(settings = settings().copy(systemPrompt = "全局提示词"))
        ).toList()
        assertEquals("全局提示词", settingsStreamer.requests[0].systemPrompt)
    }

    @Test
    fun `collector cancellation stops the underlying stream`() = runBlocking {
        val streamer = EndlessStreamer()

        val first = withTimeout(2_000) {
            engine(streamer, FakeToolExecutor(listOf(pingTool))).send(request()).first()
        }

        assertEquals("x", (first as ChatEvent.TextDelta).text)
        assertTrue(streamer.cancelled)
    }

    @Test
    fun `toLlmTurns expands multi-result tool messages`() {
        val message = Message(
            conversationId = "c1",
            role = Role.TOOL,
            blocks = listOf(
                ContentBlock.ToolResult("c1", "first", "p1", "one"),
                ContentBlock.ToolResult("c2", "second", "p1", "two", isError = true)
            )
        )
        val turns = ChatEngine.toLlmTurns(listOf(message))
        assertEquals(2, turns.size)
        assertEquals("c1", turns[0].toolCallId)
        assertEquals("one", turns[0].text)
        assertEquals("c2", turns[1].toolCallId)
        assertTrue(turns[1].text.contains("工具执行失败"))
    }

    private class EndlessStreamer : LlmStreamer {
        @Volatile
        var cancelled: Boolean = false

        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            try {
                while (true) {
                    emit(LlmEvent.TextDelta("x"))
                    delay(5)
                }
            } finally {
                cancelled = true
            }
        }
    }
}
