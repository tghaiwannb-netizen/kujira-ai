package app.kujira.android.domain

import app.kujira.android.data.model.ToolDescriptor
import app.kujira.android.data.model.ToolPolicy

/**
 * Asks the user whether a tool call may run. Implemented by the UI layer;
 * blocks the chat loop until the user answers.
 */
fun interface ToolConfirmer {
    suspend fun confirm(tool: ToolDescriptor, argumentsJson: String): Boolean

    companion object {
        val AllowAll = ToolConfirmer { _, _ -> true } // Explicit test-only opt-in.
        val DenyAll = ToolConfirmer { _, _ -> false } // Default until UI attaches.
    }
}

/**
 * Decorator that enforces per-tool policies in front of [McpToolExecutor]:
 * AUTO tools run straight through, ASK tools block on [confirmer], DISABLED
 * tools never reach the model (filtered in [availableTools]) and are refused
 * defensively if they somehow do.
 */
class ConfirmingToolExecutor(
    private val delegate: ToolExecutor,
    private val policyOf: (ToolDescriptor) -> ToolPolicy,
    var confirmer: ToolConfirmer = ToolConfirmer.DenyAll
) : ToolExecutor {

    override fun availableTools(): List<ToolDescriptor> = delegate.availableTools()

    override suspend fun execute(tool: ToolDescriptor, argumentsJson: String): ToolExecResult =
        when (policyOf(tool)) {
            ToolPolicy.DISABLED -> ToolExecResult(
                output = "工具 ${tool.name} 已被禁用，未执行。",
                isError = true
            )

            ToolPolicy.AUTO -> delegate.execute(tool, argumentsJson)

            ToolPolicy.ASK ->
                if (confirmer.confirm(tool, argumentsJson)) {
                    delegate.execute(tool, argumentsJson)
                } else {
                    ToolExecResult(
                        output = "用户拒绝执行该工具调用（${tool.name}）。请换一种方式或询问用户。",
                        isError = true
                    )
                }
        }
}
