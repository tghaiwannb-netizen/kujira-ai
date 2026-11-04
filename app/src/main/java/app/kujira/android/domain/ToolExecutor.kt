package app.kujira.android.domain

import app.kujira.android.data.model.ToolDescriptor

/*
 * Boundary between the chat engine and whatever executes tools.
 *
 * The MCP layer implements this; the chat engine only ever sees this interface,
 * which keeps the tool-call loop unit-testable with a fake.
 */
interface ToolExecutor {
    /** Tools currently available to the model, across all enabled plugins. */
    fun availableTools(): List<ToolDescriptor>

    /** Executes one tool call. Implementations must never throw; errors are results. */
    suspend fun execute(tool: ToolDescriptor, argumentsJson: String): ToolExecResult
}

data class ToolExecResult(
    val output: String,
    val isError: Boolean = false,
    val durationMs: Long = 0L
)
