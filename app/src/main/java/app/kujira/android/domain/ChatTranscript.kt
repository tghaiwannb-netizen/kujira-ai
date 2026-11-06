package app.kujira.android.domain

import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.Role

/**
 * UI projection of a persisted transcript.
 *
 * Tool results are stored in separate TOOL turns because that is the shape
 * OpenAI / Anthropic histories need. The chat UI renders them inside the
 * preceding assistant turn's tool card, keyed by call id. Older databases may
 * have the result only in the TOOL turn; this folds it into the assistant turn
 * without removing the TOOL turn (which the next model request still needs).
 */
fun mergeToolResults(messages: List<Message>): List<Message> {
    val merged = messages.toMutableList()
    var lastAssistantIndex = -1
    for (index in merged.indices) {
        val message = merged[index]
        if (message.role == Role.ASSISTANT) {
            lastAssistantIndex = index
            continue
        }
        if (message.role != Role.TOOL || lastAssistantIndex < 0) continue
        val results = message.blocks.filterIsInstance<ContentBlock.ToolResult>()
        if (results.isEmpty()) continue
        val assistant = merged[lastAssistantIndex]
        val existingIds = assistant.blocks
            .filterIsInstance<ContentBlock.ToolResult>()
            .mapTo(HashSet()) { it.callId }
        val missing = results.filterNot { it.callId in existingIds }
        if (missing.isNotEmpty()) {
            merged[lastAssistantIndex] = assistant.copy(blocks = assistant.blocks + missing)
        }
    }
    return merged
}
