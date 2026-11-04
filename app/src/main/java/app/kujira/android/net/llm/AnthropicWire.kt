package app.kujira.android.net.llm

import app.kujira.android.data.model.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Wire-format translation for Anthropic's `/v1/messages` protocol.
 *
 * Anthropic differs from OpenAI in three important ways handled here:
 * - the system prompt is a top-level field, never a message;
 * - tool results are ordinary `user` messages containing `tool_result` blocks;
 * - messages must start with `user` (and alternate), so consecutive same-role
 *   turns are merged into one message.
 */
internal object AnthropicWire {

    const val API_VERSION = "2023-06-01"
    private const val USER_AGENT = "Kujira/1.0 (Android)"

    private val TOOL_NAME = Regex("^[a-zA-Z0-9_-]{1,64}$")

    fun endpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Base URL 不能为空" }
        return if (trimmed.endsWith("/messages")) trimmed else "$trimmed/messages"
    }

    fun headers(request: LlmRequest): Headers = Headers.Builder()
        .add("Content-Type", "application/json")
        .add("Accept", "text/event-stream")
        .add("User-Agent", USER_AGENT)
        .add("anthropic-version", API_VERSION)
        .apply { request.apiKey.trim().takeIf { it.isNotEmpty() }?.let { add("x-api-key", it) } }
        .build()

    fun httpRequest(request: LlmRequest, json: Json = LlmJson): Request =
        Request.Builder()
            .url(endpoint(request.provider.baseUrl))
            .headers(headers(request))
            .post(requestBody(request, json).toString().toRequestBody(JsonMediaType))
            .build()

    fun requestBody(request: LlmRequest, json: Json = LlmJson): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_tokens", request.maxTokens.coerceAtLeast(1))
        put("stream", true)
        systemText(request.messages, request.systemPrompt)?.let { put("system", it) }
        put("temperature", request.temperature.coerceIn(0.0, 1.0))
        put("top_p", request.topP.coerceIn(0.0, 1.0))

        putJsonArray("messages") { convertMessages(request.messages, json).forEach { add(it) } }

        val tools = request.tools.filter { TOOL_NAME.matches(it.name) }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                for (tool in tools) {
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", OpenAiWire.schemaOf(tool.inputSchemaJson, json))
                        }
                    )
                }
            }
        }
    }

    /**
     * Builds the top-level `system` field out of [LlmRequest.systemPrompt] plus
     * any SYSTEM turns that ended up in the message list.
     */
    fun systemText(turns: List<LlmTurn>, systemPrompt: String? = null): String? {
        val parts = buildList {
            systemPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            turns.filter { it.role == Role.SYSTEM }
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }
                .forEach(::add)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    fun convertMessages(turns: List<LlmTurn>, json: Json = LlmJson): List<JsonObject> {
        data class Acc(val role: String) {
            val blocks = mutableListOf<JsonObject>()
        }

        val messages = mutableListOf<Acc>()

        fun add(role: String, block: JsonObject) {
            val last = messages.lastOrNull()
            if (last != null && last.role == role) last.blocks += block
            else messages += Acc(role).also { it.blocks += block }
        }

        for (turn in turns) {
            when (turn.role) {
                Role.SYSTEM -> Unit // hoisted into the top-level system field

                Role.USER -> if (turn.text.isNotEmpty()) add("user", textBlock(turn.text))

                Role.ASSISTANT -> {
                    if (turn.text.isNotEmpty()) add("assistant", textBlock(turn.text))
                    for (call in turn.toolCalls) {
                        add("assistant", toolUseBlock(call, json))
                    }
                }

                Role.TOOL -> {
                    val callId = turn.toolCallId
                    if (!callId.isNullOrBlank()) add("user", toolResultBlock(callId, turn.text))
                }
            }
        }

        // Anthropic requires the message list to start with a user turn.
        while (messages.isNotEmpty() && messages.first().role != "user") {
            messages.removeAt(0)
        }

        return messages.map { message ->
            buildJsonObject {
                put("role", message.role)
                putJsonArray("content") { message.blocks.forEach { add(it) } }
            }
        }
    }

    /** Same conversion but as a raw JSON array, handy for tests. */
    internal fun messagesArray(turns: List<LlmTurn>, json: Json = LlmJson): JsonArray =
        buildJsonArray { convertMessages(turns, json).forEach { add(it) } }

    private fun textBlock(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun toolUseBlock(call: LlmToolCall, json: Json): JsonObject = buildJsonObject {
        put("type", "tool_use")
        put("id", call.id)
        put("name", call.name)
        put(
            "input",
            runCatching { json.parseToJsonElement(call.argumentsJson.ifBlank { "{}" }) }
                .getOrNull()
                ?.takeIf { it !is JsonNull }
                ?: buildJsonObject { }
        )
    }

    private fun toolResultBlock(callId: String, output: String): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", callId)
        put("content", output)
    }
}
