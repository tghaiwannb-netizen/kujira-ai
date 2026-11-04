package app.kujira.android.net.llm

import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.data.model.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Wire-format translation for the OpenAI `/chat/completions` protocol.
 *
 * Shared by OpenAI, DeepSeek, OpenRouter and any custom OpenAI-compatible
 * endpoint. The functions are pure so the payload shape is unit-testable.
 */
internal object OpenAiWire {

    private const val USER_AGENT = "Kujira/1.0 (Android)"

    /** Appends `/chat/completions` unless the user already configured the full endpoint. */
    fun endpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Base URL 不能为空" }
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    fun headers(request: LlmRequest): Headers {
        val builder = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "text/event-stream")
            .add("User-Agent", USER_AGENT)
        request.apiKey.trim().takeIf { it.isNotEmpty() }
            ?.let { builder.add("Authorization", "Bearer $it") }
        if (request.provider.kind == ProviderKind.OPENROUTER) {
            builder.add("HTTP-Referer", "https://kujira.app")
            builder.add("X-Title", "Kujira")
        }
        return builder.build()
    }

    fun httpRequest(request: LlmRequest, json: Json = LlmJson): Request =
        Request.Builder()
            .url(endpoint(request.provider.baseUrl))
            .headers(headers(request))
            .post(requestBody(request, json).toString().toRequestBody(JsonMediaType))
            .build()

    fun requestBody(request: LlmRequest, json: Json = LlmJson): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        putJsonObject("stream_options") { put("include_usage", true) }

        val messages = buildList {
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                add(buildJsonObject { put("role", "system"); put("content", it.trim()) })
            }
            for (turn in request.messages) {
                messageObject(turn, json)?.let(::add)
            }
        }
        putJsonArray("messages") { messages.forEach { add(it) } }

        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                for (tool in request.tools) {
                    add(
                        buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", schemaOf(tool.inputSchemaJson, json))
                            }
                        }
                    )
                }
            }
            put("tool_choice", "auto")
        }

        if (!isReasoningOnlyModel(request.provider, request.model)) {
            put("temperature", request.temperature)
            put("top_p", request.topP)
        }
        if (usesMaxCompletionTokens(request.provider, request.model)) {
            put("max_completion_tokens", request.maxTokens.coerceAtLeast(1))
        } else {
            put("max_tokens", request.maxTokens.coerceAtLeast(1))
        }
    }

    /** One provider-neutral turn → one OpenAI message object, or `null` when it cannot be expressed. */
    fun messageObject(turn: LlmTurn, json: Json = LlmJson): JsonObject? = when (turn.role) {
        Role.SYSTEM -> buildJsonObject {
            put("role", "system")
            put("content", turn.text)
        }

        Role.USER -> buildJsonObject {
            put("role", "user")
            put("content", turn.text)
        }

        Role.ASSISTANT -> buildJsonObject {
            put("role", "assistant")
            put("content", turn.text)
            if (turn.toolCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    for (call in turn.toolCalls) {
                        add(
                            buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.argumentsJson.ifBlank { "{}" })
                                }
                            }
                        )
                    }
                }
            }
        }

        Role.TOOL -> {
            val callId = turn.toolCallId
            if (callId.isNullOrBlank()) {
                null
            } else {
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", callId)
                    put("content", turn.text)
                }
            }
        }
    }

    internal fun schemaOf(schemaJson: String, json: Json = LlmJson): JsonObject =
        runCatching { json.parseToJsonElement(schemaJson).asObject() }.getOrNull()
            ?: buildJsonObject { put("type", "object") }

    /** OpenAI's reasoning models reject `max_tokens` and non-default sampling parameters. */
    internal fun isReasoningOnlyModel(provider: ProviderConfig, model: String): Boolean {
        if (provider.kind != ProviderKind.OPENAI) return false
        val id = model.lowercase().trim()
        return id.startsWith("gpt-5") ||
            id.startsWith("o1") ||
            id.startsWith("o3") ||
            id.startsWith("o4")
    }

    internal fun usesMaxCompletionTokens(provider: ProviderConfig, model: String): Boolean =
        isReasoningOnlyModel(provider, model)

    /** Handy for tests and diagnostics: JSON array body for a list of turns. */
    internal fun messagesArray(request: LlmRequest, json: Json = LlmJson): JsonArray =
        buildJsonArray { request.messages.forEach { messageObject(it, json)?.let(::add) } }
}
