package app.kujira.android.net.llm

import app.kujira.android.data.model.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

/**
 * Incremental aggregator for Anthropic `/v1/messages` streaming events.
 *
 * Handles the full event sequence:
 * `message_start` → `content_block_start` → `content_block_delta`
 * (`text_delta`, `thinking_delta`, `input_json_delta`) → `content_block_stop`
 * → `message_delta` → `message_stop`, plus `error` and `ping`.
 *
 * Tool inputs arrive as JSON *fragments* and are only parsed when the block
 * closes in `content_block_stop`, exactly like the official SDKs do.
 */
internal class AnthropicStreamState(private val json: Json = LlmJson) {

    private class BlockAcc {
        var type: String = ""
        var toolId: String? = null
        var toolName: String? = null
        var startInput: kotlinx.serialization.json.JsonElement? = null
        val jsonDelta = StringBuilder()
    }

    private val blocks = sortedMapOf<Int, BlockAcc>()
    private val toolCalls = mutableListOf<LlmToolCall>()
    private var promptTokens = 0
    private var completionTokens = 0
    private var stopReason: String? = null

    /** Set once `message_stop` has been observed. */
    var sawMessageStop: Boolean = false
        private set

    /** Processes one SSE event and returns the deltas that should be emitted right away. */
    fun accept(eventName: String?, data: String): List<LlmEvent> {
        if (data.isBlank()) return emptyList()
        val payload = runCatching { json.parseToJsonElement(data).asObject() }.getOrNull()
            ?: return emptyList()
        val type = eventName?.takeIf { it.isNotBlank() }
            ?: payload.stringOf("type")
            ?: return emptyList()
        val events = mutableListOf<LlmEvent>()

        when (type) {
            "message_start" -> {
                payload["message"].asObject()?.let { message ->
                    message["usage"].asObject()?.let { usage ->
                        promptTokens = usage.intOf("input_tokens") ?: promptTokens
                        completionTokens = usage.intOf("output_tokens") ?: completionTokens
                    }
                }
            }

            "content_block_start" -> {
                val index = payload.intOf("index") ?: blocks.size
                val block = payload["content_block"].asObject()
                val acc = BlockAcc()
                acc.type = block?.stringOf("type").orEmpty()
                when (acc.type) {
                    "tool_use" -> {
                        acc.toolId = block?.stringOf("id")
                        acc.toolName = block?.stringOf("name")
                        acc.startInput = block?.get("input")
                    }

                    "text" -> block?.stringOf("text")?.takeIf { it.isNotEmpty() }
                        ?.let { events += LlmEvent.TextDelta(it) }

                    "thinking" -> block?.stringOf("thinking")?.takeIf { it.isNotEmpty() }
                        ?.let { events += LlmEvent.ReasoningDelta(it) }
                }
                blocks[index] = acc
            }

            "content_block_delta" -> {
                val index = payload.intOf("index") ?: 0
                val delta = payload["delta"].asObject() ?: return emptyList()
                val acc = blocks.getOrPut(index) { BlockAcc() }
                when (delta.stringOf("type")) {
                    "text_delta" -> delta.stringOf("text")?.takeIf { it.isNotEmpty() }
                        ?.let { events += LlmEvent.TextDelta(it) }

                    "thinking_delta" -> delta.stringOf("thinking")?.takeIf { it.isNotEmpty() }
                        ?.let { events += LlmEvent.ReasoningDelta(it) }

                    "input_json_delta" -> delta.stringOf("partial_json")?.let { acc.jsonDelta.append(it) }

                    // Non-visible deltas: extended-thinking signatures and citations.
                    else -> Unit
                }
            }

            "content_block_stop" -> {
                val index = payload.intOf("index") ?: 0
                val acc = blocks[index]
                if (acc?.type == "tool_use") {
                    val name = acc.toolName?.takeIf { it.isNotBlank() }
                    if (name != null) {
                        toolCalls += LlmToolCall(
                            id = acc.toolId?.takeIf { it.isNotBlank() } ?: "tool_$index",
                            name = name,
                            argumentsJson = resolveToolInput(acc)
                        )
                    }
                }
            }

            "message_delta" -> {
                payload["delta"].asObject()?.stringOf("stop_reason")?.let { stopReason = it }
                payload["usage"].asObject()?.let { usage ->
                    completionTokens = usage.intOf("output_tokens") ?: completionTokens
                }
            }

            "message_stop" -> sawMessageStop = true

            "error" -> {
                val error = payload["error"].asObject()
                val errorType = error?.stringOf("type").orEmpty()
                val message = error?.stringOf("message") ?: "未知错误"
                throw LlmStreamException(
                    message = "Claude 服务返回错误：$message" +
                        if (errorType.isNotEmpty()) "（$errorType）" else "",
                    retryable = errorType in RETRYABLE_ERROR_TYPES
                )
            }

            // "ping" and unknown future events are harmless.
            else -> Unit
        }

        return events
    }

    /** Builds the single terminal event for this stream. */
    fun finished(): LlmEvent.Finished {
        val usage = if (promptTokens == 0 && completionTokens == 0) {
            null
        } else {
            Usage(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = promptTokens + completionTokens
            )
        }
        return LlmEvent.Finished(
            toolCalls = toolCalls.toList(),
            usage = usage,
            finishReason = stopReason ?: if (toolCalls.isNotEmpty()) "tool_use" else "end_turn"
        )
    }

    private fun resolveToolInput(acc: BlockAcc): String {
        val raw = if (acc.jsonDelta.isNotEmpty()) {
            acc.jsonDelta.toString()
        } else {
            acc.startInput?.takeIf { it !is JsonNull }?.toString()
        }
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "{}"
        return runCatching { json.parseToJsonElement(text).toString() }.getOrDefault(text)
    }

    private companion object {
        val RETRYABLE_ERROR_TYPES = setOf("overloaded_error", "rate_limit_error", "api_error")
    }
}
