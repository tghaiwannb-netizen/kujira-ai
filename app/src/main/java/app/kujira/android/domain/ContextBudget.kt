package app.kujira.android.domain

import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.Role
import kotlin.math.ceil

enum class ContextLevel { NORMAL, WARN, CRITICAL }

/**
 * Approximate context usage for the active conversation.
 *
 * Token counts are estimates: providers do not expose a tokenizer, and the
 * app talks to many different model families. The estimate deliberately errs
 * on the safe side (one CJK character ≈ one token, Latin ≈ 4 chars/token).
 */
data class ContextUsage(
    val usedTokens: Int = 0,
    val windowTokens: Int? = null,
    val systemTokens: Int = 0,
    val summaryTokens: Int = 0,
    val messageTokens: Int = 0,
    val compacted: Boolean = false,
) {
    val ratio: Float?
        get() = windowTokens?.takeIf { it > 0 }?.let { usedTokens.toFloat() / it }

    val level: ContextLevel
        get() {
            val value = ratio ?: return ContextLevel.NORMAL
            return when {
                value >= 0.90f -> ContextLevel.CRITICAL
                value >= 0.75f -> ContextLevel.WARN
                else -> ContextLevel.NORMAL
            }
        }

    val remainingTokens: Int?
        get() = windowTokens?.let { (it - usedTokens).coerceAtLeast(0) }
}

object TokenEstimator {

    /** Cheap cross-provider estimate; good enough for a budget indicator. */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var tokens = 0.0
        for (char in text) {
            when {
                char.isWhitespace() -> Unit
                isCjk(char) -> tokens += 1.0
                else -> tokens += 0.25
            }
        }
        return ceil(tokens).toInt()
    }

    fun estimate(message: Message): Int {
        var total = MESSAGE_OVERHEAD
        total += estimate(message.text)
        total += estimate(message.reasoning)
        for (block in message.blocks) {
            when (block) {
                is ContentBlock.ToolCall -> {
                    total += estimate(block.name) + estimate(block.argumentsJson)
                }

                is ContentBlock.ToolResult -> {
                    // UI projection copies results onto the assistant turn; the
                    // TOOL turn is the canonical copy, so count it once.
                    if (message.role == Role.TOOL) {
                        total += estimate(block.toolName) + estimate(block.output)
                    }
                }

                is ContentBlock.Text, is ContentBlock.Reasoning -> Unit
            }
        }
        return total
    }

    fun estimate(messages: List<Message>): Int = messages.sumOf(::estimate)

    /**
     * A compact text representation used by the compactor. Tool payloads are
     * truncated because the summary only needs their gist, not the full JSON.
     */
    fun transcript(messages: List<Message>): String = buildString {
        for (message in messages) {
            when (message.role) {
                Role.USER -> appendLine("用户：${message.text}")
                Role.ASSISTANT -> {
                    append("助手：${message.text}")
                    val calls = message.blocks.filterIsInstance<ContentBlock.ToolCall>()
                    if (calls.isNotEmpty()) {
                        append(" [调用工具：")
                        append(calls.joinToString("; ") { "${it.name}(${it.argumentsJson.take(600)})" })
                        append("]")
                    }
                    appendLine()
                }

                Role.TOOL -> {
                    val results = message.blocks.filterIsInstance<ContentBlock.ToolResult>()
                    if (results.isEmpty()) {
                        appendLine("工具结果：${message.text.take(MAX_TOOL_RESULT_CHARS)}")
                    } else {
                        for (result in results) {
                            val status = if (result.isError) "失败" else "成功"
                            appendLine(
                                "工具结果（${result.toolName}，$status）：" +
                                    result.output.take(MAX_TOOL_RESULT_CHARS)
                            )
                        }
                    }
                }

                Role.SYSTEM -> appendLine("系统：${message.text}")
            }
            appendLine()
        }
    }.trim()

    /** Rough conversion used when a single message is larger than a chunk. */
    fun truncateToTokens(text: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        val estimate = estimate(text)
        if (estimate <= maxTokens) return text
        val targetChars = (text.length.toLong() * maxTokens / estimate).toInt()
        return text.take(targetChars.coerceAtLeast(1))
    }

    private fun isCjk(char: Char): Boolean {
        val code = char.code
        return code in 0x3400..0x4DBF ||
            code in 0x4E00..0x9FFF ||
            code in 0x3040..0x30FF ||
            code in 0xAC00..0xD7AF
    }

    private const val MESSAGE_OVERHEAD = 4
    private const val MAX_TOOL_RESULT_CHARS = 1500
}

fun formatTokenCount(tokens: Int): String = when {
    tokens >= 1_000_000 -> {
        val value = tokens / 1_000_000.0
        if (value >= 10) "${value.toInt()}M" else String.format(java.util.Locale.US, "%.1fM", value)
    }

    tokens >= 1_000 -> {
        val value = tokens / 1_000.0
        if (value >= 100) "${value.toInt()}K" else String.format(java.util.Locale.US, "%.1fK", value)
    }

    else -> tokens.toString()
}

fun formatCost(costUsd: Double): String = when {
    costUsd <= 0.0 -> "$0"
    costUsd < 0.0001 -> "<$0.0001"
    costUsd < 0.01 -> String.format(java.util.Locale.US, "$%.4f", costUsd)
    costUsd < 1.0 -> String.format(java.util.Locale.US, "$%.3f", costUsd)
    else -> String.format(java.util.Locale.US, "$%.2f", costUsd)
}
