package app.kujira.android.net.llm

import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.Role
import app.kujira.android.data.model.Usage
import kotlinx.coroutines.flow.Flow

/*
 * Provider-agnostic streaming contract. Implementations translate a request
 * into the provider wire format, open an SSE stream and emit [LlmEvent]s.
 */

interface LlmClient {
    /**
     * Starts a streaming completion.
     *
     * The returned flow emits [LlmEvent.TextDelta]/[LlmEvent.ReasoningDelta]
     * incrementally, then exactly one terminal event: either
     * [LlmEvent.Finished] (possibly carrying tool calls) or [LlmEvent.Failed].
     * Cancelling collection must abort the HTTP stream.
     */
    fun stream(request: LlmRequest): Flow<LlmEvent>
}

data class LlmRequest(
    val provider: ProviderConfig,
    val apiKey: String,
    val model: String,
    val systemPrompt: String?,
    val messages: List<LlmTurn>,
    val tools: List<LlmToolSpec> = emptyList(),
    val temperature: Double = 0.7,
    val topP: Double = 1.0,
    val maxTokens: Int = 4096
)

/** One turn in provider-neutral form. */
data class LlmTurn(
    val role: Role,
    val text: String = "",
    val reasoning: String? = null,
    /** Assistant turns: tool calls requested by the model. */
    val toolCalls: List<LlmToolCall> = emptyList(),
    /** Tool-result turns: which call this is answering. */
    val toolCallId: String? = null,
    val toolName: String? = null
)

data class LlmToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class LlmToolSpec(
    val name: String,
    val description: String,
    val inputSchemaJson: String
)

sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent
    data class ReasoningDelta(val text: String) : LlmEvent

    /** Terminal success event. [toolCalls] is empty for a plain text answer. */
    data class Finished(
        val toolCalls: List<LlmToolCall> = emptyList(),
        val usage: Usage? = null,
        val finishReason: String? = null
    ) : LlmEvent

    data class Failed(
        val message: String,
        val httpCode: Int? = null,
        val retryable: Boolean = false
    ) : LlmEvent
}
