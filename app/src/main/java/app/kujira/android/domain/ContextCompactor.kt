package app.kujira.android.domain

import app.kujira.android.data.model.Message
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.Role
import app.kujira.android.data.model.Usage
import app.kujira.android.net.llm.LlmEvent
import app.kujira.android.net.llm.LlmRequest
import app.kujira.android.net.llm.LlmStreamer
import app.kujira.android.net.llm.LlmTurn

data class CompactionResult(
    val summary: String,
    val summarizedMessages: Int,
    val usage: Usage?,
)

class ContextCompactionException(message: String) : Exception(message)

/**
 * Non-destructive history compression.
 *
 * Older turns are sent to the active model with a strict "keep facts, code,
 * tool results and open tasks" prompt. The resulting summary is stored on the
 * conversation; the original messages remain visible in the transcript.
 *
 * Long prefixes are split into token-bounded chunks and folded into a running
 * summary, so compaction itself does not overflow the context window.
 */
class ContextCompactor(
    private val streamer: LlmStreamer,
) {

    suspend fun summarize(
        provider: ProviderConfig,
        apiKey: String,
        modelId: String,
        messages: List<Message>,
        previousSummary: String? = null,
        sessionId: String = "kujira-compact",
        chunkTokenLimit: Int = DEFAULT_CHUNK_TOKENS,
        maxSummaryTokens: Int = DEFAULT_SUMMARY_TOKENS,
    ): CompactionResult {
        require(messages.isNotEmpty()) { "没有需要压缩的消息" }
        val chunks = chunkTranscripts(messages, chunkTokenLimit)
        if (chunks.isEmpty()) throw ContextCompactionException("没有可压缩的对话内容")

        var summary = previousSummary?.trim().orEmpty()
        var usage: Usage? = null

        for ((index, chunk) in chunks.withIndex()) {
            val prompt = buildString {
                if (summary.isNotBlank()) {
                    appendLine("已有摘要（请合并更新，保留其中仍然有效的事实）：")
                    appendLine(summary.take(MAX_SUMMARY_CHARS))
                    appendLine()
                }
                appendLine("请把下面第 ${index + 1}/${chunks.size} 段对话压缩进摘要。")
                appendLine("必须保留：用户目标、已确认事实、关键数字/路径/命令、代码与配置、工具调用结果、未完成事项、用户偏好。")
                appendLine("删除寒暄和重复内容；不要添加新的推测。用简洁 Markdown 输出。")
                appendLine()
                appendLine("<conversation>")
                appendLine(chunk)
                appendLine("</conversation>")
            }

            val request = LlmRequest(
                provider = provider,
                apiKey = apiKey,
                model = modelId,
                systemPrompt = SYSTEM_PROMPT,
                messages = listOf(LlmTurn(Role.USER, prompt)),
                tools = emptyList(),
                temperature = 0.2,
                topP = 1.0,
                maxTokens = maxSummaryTokens,
                sessionId = sessionId
            )

            val text = StringBuilder()
            var failure: LlmEvent.Failed? = null
            streamer.stream(request).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> text.append(event.text)
                    is LlmEvent.ReasoningDelta -> Unit
                    is LlmEvent.Finished -> event.usage?.let {
                        usage = usage?.plus(it) ?: it
                    }

                    is LlmEvent.Failed -> failure = event
                }
            }
            failure?.let { throw ContextCompactionException(it.message) }
            val chunkSummary = text.toString().trim()
            if (chunkSummary.isEmpty()) {
                throw ContextCompactionException("模型没有返回摘要内容")
            }
            summary = if (summary.isBlank()) chunkSummary else "$summary\n\n$chunkSummary"
            if (summary.length > MAX_SUMMARY_CHARS) {
                val half = MAX_SUMMARY_CHARS / 2
                summary = summary.take(half) + "\n\n...\n\n" + summary.takeLast(half)
            }
        }

        return CompactionResult(
            summary = summary.trim(),
            summarizedMessages = messages.size,
            usage = usage
        )
    }

    private fun chunkTranscripts(messages: List<Message>, tokenLimit: Int): List<String> {
        val segments = messages.map { message ->
            val raw = TokenEstimator.transcript(listOf(message))
            if (TokenEstimator.estimate(raw) > tokenLimit) {
                TokenEstimator.truncateToTokens(raw, tokenLimit)
            } else {
                raw
            }
        }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentTokens = 0
        for (segment in segments) {
            val tokens = TokenEstimator.estimate(segment)
            if (current.isNotEmpty() && currentTokens + tokens > tokenLimit) {
                chunks += current.toString().trim()
                current.clear()
                currentTokens = 0
            }
            if (current.isNotEmpty()) current.appendLine().appendLine()
            current.append(segment)
            currentTokens += tokens
        }
        if (current.isNotEmpty()) chunks += current.toString().trim()
        return chunks.filter { it.isNotBlank() }
    }

    private companion object {
        const val DEFAULT_CHUNK_TOKENS = 12_000
        const val DEFAULT_SUMMARY_TOKENS = 1_400
        const val MAX_SUMMARY_CHARS = 8_000

        const val SYSTEM_PROMPT =
            "你是 Kujira 的上下文压缩器。输出要紧凑、事实准确、可直接供后续模型继续工作。"
    }
}
