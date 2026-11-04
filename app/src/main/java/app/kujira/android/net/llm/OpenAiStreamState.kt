package app.kujira.android.net.llm

import app.kujira.android.data.model.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Incremental aggregator for OpenAI-compatible streaming chunks.
 *
 * One SSE `data:` payload is fed to [accept]; visible text/reasoning deltas are
 * returned immediately as events, while tool-call fragments are accumulated by
 * index (OpenAI splits `id`, `name` and `arguments` across many chunks). The
 * terminal [finished] event carries the reconstructed calls, usage and
 * `finish_reason`.
 */
internal class OpenAiStreamState(private val json: Json = LlmJson) {

    private class ToolCallAcc {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private val toolCalls = sortedMapOf<Int, ToolCallAcc>()
    private var finishReason: String? = null
    private var usage: Usage? = null

    /** Loads a `data: <json>` payload and returns the deltas it contains. */
    fun accept(data: String): List<LlmEvent> {
        val payload = data.trim()
        if (payload.isEmpty() || payload == "[DONE]") return emptyList()
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return emptyList()

        root["error"]?.let { errorElement ->
            val error = errorElement.asObject()
            val message = error?.stringOf("message") ?: errorElement.asString() ?: "未知错误"
            val type = error?.stringOf("type").orEmpty()
            throw LlmStreamException(
                message = "模型服务返回错误：$message" + if (type.isNotEmpty()) "（$type）" else "",
                retryable = type in RETRYABLE_ERROR_TYPES
            )
        }

        root["usage"].asObject()?.let { raw ->
            val prompt = raw.intOf("prompt_tokens", "input_tokens") ?: 0
            val completion = raw.intOf("completion_tokens", "output_tokens") ?: 0
            usage = Usage(
                promptTokens = prompt,
                completionTokens = completion,
                totalTokens = raw.intOf("total_tokens") ?: (prompt + completion)
            )
        }

        val choice = root["choices"].asArray()?.firstOrNull().asObject() ?: return emptyList()
        val events = mutableListOf<LlmEvent>()

        choice["delta"].asObject()?.let { delta ->
            delta["content"].asString()?.takeIf { it.isNotEmpty() }
                ?.let { events += LlmEvent.TextDelta(it) }
            // DeepSeek exposes reasoning as `reasoning_content`, OpenRouter as `reasoning`.
            (delta["reasoning_content"].asString() ?: delta["reasoning"].asString())
                ?.takeIf { it.isNotEmpty() }
                ?.let { events += LlmEvent.ReasoningDelta(it) }

            delta["tool_calls"].asArray()?.forEach { element ->
                val call = element.asObject() ?: return@forEach
                val index = call.intOf("index") ?: toolCalls.keys.lastOrNull() ?: 0
                val acc = toolCalls.getOrPut(index) { ToolCallAcc() }
                call.stringOf("id")?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                call["function"].asObject()?.let { function ->
                    function.stringOf("name")?.takeIf { it.isNotBlank() }?.let { acc.name = it }
                    function["arguments"].asArgumentFragment()?.let { acc.arguments.append(it) }
                }
            }

            // Legacy single-function-call format (old OpenAI-compatible servers).
            delta["function_call"].asObject()?.let { function ->
                val acc = toolCalls.getOrPut(0) { ToolCallAcc() }
                function.stringOf("name")?.takeIf { it.isNotBlank() }?.let { acc.name = it }
                function["arguments"].asArgumentFragment()?.let { acc.arguments.append(it) }
            }
        }

        choice["finish_reason"].asString()?.let { finishReason = it }
        return events
    }

    /** Builds the single terminal event for this stream. */
    fun finished(): LlmEvent.Finished {
        val calls = toolCalls.mapNotNull { (index, acc) ->
            val name = acc.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LlmToolCall(
                id = acc.id?.takeIf { it.isNotBlank() } ?: "call_$index",
                name = name,
                argumentsJson = normalizeArguments(acc.arguments.toString())
            )
        }
        return LlmEvent.Finished(
            toolCalls = calls,
            usage = usage,
            finishReason = finishReason ?: if (calls.isNotEmpty()) "tool_calls" else "stop"
        )
    }

    private fun normalizeArguments(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "{}"
        return runCatching { json.parseToJsonElement(trimmed).toString() }.getOrDefault(trimmed)
    }

    private companion object {
        val RETRYABLE_ERROR_TYPES = setOf("rate_limit_error", "server_error", "overloaded_error")
    }
}
