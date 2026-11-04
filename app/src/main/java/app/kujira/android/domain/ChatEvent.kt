package app.kujira.android.domain

import app.kujira.android.data.model.Message
import app.kujira.android.data.model.Role
import app.kujira.android.data.model.Usage

/**
 * Events streamed by [ChatEngine] while it produces one assistant answer.
 *
 * A successful run emits any number of [TextDelta]/[ReasoningDelta]/
 * [ToolStarted]/[ToolFinished] events and then exactly one terminal event:
 * [Completed] or [Failed]. If the collector cancels, no terminal event is
 * emitted; the network stream is cancelled and the caller keeps whatever it
 * accumulated so far.
 */
sealed interface ChatEvent {

    /** Visible answer text, incremental. */
    data class TextDelta(val text: String) : ChatEvent

    /** Provider reasoning/thinking text (DeepSeek R1, Claude thinking). */
    data class ReasoningDelta(val text: String) : ChatEvent

    /** A tool call is about to run. */
    data class ToolStarted(
        val callId: String,
        val toolName: String,
        val pluginId: String,
        val pluginName: String,
        val argumentsJson: String
    ) : ChatEvent

    /** A tool call finished (successfully or not). */
    data class ToolFinished(
        val callId: String,
        val toolName: String,
        val output: String,
        val isError: Boolean,
        val durationMs: Long
    ) : ChatEvent

    /**
     * Terminal success.
     *
     * [messages] are the assistant/tool messages produced by this run, in order,
     * ready to be persisted: one assistant message per model round (the
     * intermediate ones carry tool-call blocks) plus one TOOL message with the
     * tool-result blocks. [usage] is summed across rounds.
     */
    data class Completed(
        val messages: List<Message>,
        val usage: Usage?,
        val rounds: Int,
        val toolLimitReached: Boolean = false
    ) : ChatEvent {
        /** The final assistant message, i.e. the answer shown to the user. */
        val finalMessage: Message? get() = messages.lastOrNull { it.role == Role.ASSISTANT }
    }

    /**
     * Terminal failure. [partialText] is what was streamed before the failure,
     * [partialMessages] are the assistant/tool messages already produced by
     * earlier tool rounds of this run (persist them so the tool history survives).
     */
    data class Failed(
        val message: String,
        val httpCode: Int? = null,
        val retryable: Boolean = false,
        val partialText: String = "",
        val partialMessages: List<Message> = emptyList()
    ) : ChatEvent
}
