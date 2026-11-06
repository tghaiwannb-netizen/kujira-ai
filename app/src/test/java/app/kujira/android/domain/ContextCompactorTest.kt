package app.kujira.android.domain

import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.data.model.Role
import app.kujira.android.data.model.Usage
import app.kujira.android.net.llm.LlmEvent
import app.kujira.android.net.llm.LlmRequest
import app.kujira.android.net.llm.LlmStreamer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactorTest {

    private val provider = ProviderConfig(
        id = "opencode-go",
        kind = ProviderKind.OPENCODE_GO,
        name = "OpenCode Go",
        baseUrl = "https://opencode.ai/inference/go/openai/v1",
        models = listOf(ModelInfo("deepseek-v4.1-flash", contextWindow = 1_000_000)),
        defaultModelId = "deepseek-v4.1-flash"
    )

    private class FakeStreamer : LlmStreamer {
        val requests = mutableListOf<LlmRequest>()

        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            requests += request
            emit(LlmEvent.TextDelta("摘要${requests.size}"))
            emit(LlmEvent.Finished(usage = Usage(10, 5, 15)))
        }
    }

    private fun messages(count: Int): List<Message> = List(count) { index ->
        Message(
            conversationId = "c",
            role = if (index % 2 == 0) Role.USER else Role.ASSISTANT,
            blocks = listOf(
                ContentBlock.Text("第 $index 条消息 " + "数据库迁移和服务器状态。".repeat(8))
            )
        )
    }

    @Test
    fun `compacts long history in chunks and carries the previous summary`() = runBlocking {
        val streamer = FakeStreamer()
        val compactor = ContextCompactor(streamer)

        val result = compactor.summarize(
            provider = provider,
            apiKey = "sk-test",
            modelId = "deepseek-v4.1-flash",
            messages = messages(5),
            previousSummary = "旧摘要：用户在做数据库迁移",
            chunkTokenLimit = 30,
            maxSummaryTokens = 100
        )

        assertTrue(streamer.requests.size >= 2)
        assertTrue(streamer.requests.first().messages.single().text.contains("旧摘要"))
        assertTrue(result.summary.contains("摘要1"))
        assertTrue(result.summary.contains("摘要${streamer.requests.size}"))
        assertEquals(5, result.summarizedMessages)
        assertEquals(10 * streamer.requests.size, result.usage?.promptTokens)
        assertEquals(5 * streamer.requests.size, result.usage?.completionTokens)
    }

    @Test
    fun `uses the active model and a stable compaction session`() = runBlocking {
        val streamer = FakeStreamer()
        val compactor = ContextCompactor(streamer)

        compactor.summarize(
            provider = provider,
            apiKey = "sk-test",
            modelId = "deepseek-v4.1-flash",
            messages = messages(1),
            sessionId = "conversation-42",
            chunkTokenLimit = 10_000
        )

        val request = streamer.requests.single()
        assertEquals("deepseek-v4.1-flash", request.model)
        assertEquals("conversation-42", request.sessionId)
        assertTrue(request.tools.isEmpty())
        assertEquals(0.2, request.temperature, 0.0001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty message lists`() {
        runBlocking {
            ContextCompactor(FakeStreamer()).summarize(
                provider = provider,
                apiKey = "sk-test",
                modelId = "deepseek-v4.1-flash",
                messages = emptyList()
            )
        }
    }
}
