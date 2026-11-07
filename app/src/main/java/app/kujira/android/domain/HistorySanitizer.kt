package app.kujira.android.domain

import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.Role

/**
 * Makes a persisted transcript safe to replay to a provider.
 *
 * Why this exists: when a generation is stopped mid-way, the streaming message
 * is saved as it was — tool calls and tool results sit *inside the same
 * assistant message*, and a call that was running when the user tapped stop has
 * no result at all. Replayed as-is, OpenAI-compatible and Anthropic APIs reject
 * the request ("tool_calls must be followed by tool messages…"), so every later
 * message in that conversation fails — which looks exactly like "sending does
 * nothing".
 *
 * What it does:
 *  1. splits assistant messages that carry their own [ContentBlock.ToolResult]s
 *     into an assistant message (text + calls) followed by a TOOL message;
 *  2. drops tool calls that never got a result;
 *  3. drops tool results whose call is gone.
 */
fun sanitizeHistory(history: List<Message>): List<Message> {
    // The UI mirrors TOOL results onto the assistant turn for tool cards. The
    // same result also exists as a separate TOOL turn, so do not expand it into
    // a duplicate TOOL message here.
    val existingResultIds = history
        .filter { it.role == Role.TOOL }
        .flatMap { it.blocks.filterIsInstance<ContentBlock.ToolResult>() }
        .mapTo(HashSet()) { it.callId }

    val expanded = buildList {
        for (message in history) {
            val inlineResults = message.blocks.filterIsInstance<ContentBlock.ToolResult>()
            if (message.role == Role.ASSISTANT && inlineResults.isNotEmpty()) {
                val rest = message.blocks.filterNot { it is ContentBlock.ToolResult }
                add(message.copy(blocks = rest))
                val missing = inlineResults.filterNot { it.callId in existingResultIds }
                if (missing.isNotEmpty()) {
                    add(message.copy(role = Role.TOOL, blocks = missing))
                }
            } else {
                add(message)
            }
        }
    }

    val answeredCallIds = expanded
        .filter { it.role == Role.TOOL }
        .flatMap { it.blocks.filterIsInstance<ContentBlock.ToolResult>() }
        .map { it.callId }
        .toSet()
    val requestedCallIds = expanded
        .filter { it.role == Role.ASSISTANT }
        .flatMap { it.blocks.filterIsInstance<ContentBlock.ToolCall>() }
        .map { it.id }
        .toSet()

    return expanded.mapNotNull { message ->
        when (message.role) {
            Role.ASSISTANT -> {
                val blocks = message.blocks.filter { block ->
                    block !is ContentBlock.ToolCall || block.id in answeredCallIds
                }
                if (blocks.isEmpty()) null else message.copy(blocks = blocks)
            }

            Role.TOOL -> {
                val blocks = message.blocks.filter { block ->
                    block !is ContentBlock.ToolResult || block.callId in requestedCallIds
                }
                if (blocks.isEmpty()) null else message.copy(blocks = blocks)
            }

            else -> message
        }
    }
}
