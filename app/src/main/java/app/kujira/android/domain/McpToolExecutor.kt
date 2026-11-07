package app.kujira.android.domain

import app.kujira.android.core.SettingsStore
import app.kujira.android.data.db.AppDatabase
import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.ToolAudit
import app.kujira.android.data.model.ToolDescriptor
import app.kujira.android.data.model.ToolPolicy
import app.kujira.android.net.mcp.McpAuthRequiredException
import app.kujira.android.net.mcp.McpJson
import app.kujira.android.net.mcp.McpManager
import app.kujira.android.net.mcp.ToolPolicies
import app.kujira.android.net.mcp.ToolSchema
import app.kujira.android.net.sentinelx.SentinelXPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [ToolExecutor] implementation backed by the live MCP sessions.
 *
 * Responsibilities:
 *  - expose only tools whose effective policy is not DISABLED;
 *  - inject the selected SentinelX `host_id` into tools whose schema accepts it
 *    (the model rarely knows the host id, and the user picked one in the UI);
 *  - translate MCP failures into human-Chinese results (never throw);
 *  - record every call in the audit table.
 *
 * Cancellation is the one thing that is *not* translated: stopping a
 * generation must abort the tool call, so [CancellationException] always
 * propagates.
 */
class McpToolExecutor(
    private val manager: McpManager,
    private val settings: SettingsStore,
    private val database: AppDatabase? = null,
    private val conversationIdProvider: () -> String? = { settings.settings.value.lastConversationId },
    private val hostIdProvider: () -> String? = { settings.settings.value.sentinelHostId }
) : ToolExecutor {

    override fun availableTools(): List<ToolDescriptor> {
        val state = settings.settings.value
        if (!state.pluginsEnabled) return emptyList()
        val toolsByPlugin = manager.connectedTools()
        return settings.plugins.value
            .filter { it.enabled }
            .flatMap { plugin ->
                val tools = toolsByPlugin[plugin.id] ?: return@flatMap emptyList()
                ToolPolicies.available(plugin, tools)
            }
    }

    /** Policy the chat layer should apply before calling [execute]. */
    fun policyFor(tool: ToolDescriptor): ToolPolicy {
        val plugin = settings.plugins.value.firstOrNull { it.id == tool.pluginId }
            ?: return ToolPolicy.DISABLED
        return ToolPolicies.effective(plugin, tool)
    }

    override suspend fun execute(tool: ToolDescriptor, argumentsJson: String): ToolExecResult {
        val started = System.currentTimeMillis()
        val state = settings.settings.value
        val plugin = settings.plugins.value.firstOrNull { it.id == tool.pluginId }

        if (plugin == null) {
            return finish(
                tool, argumentsJson, started,
                output = "插件不存在：${tool.pluginName}（${tool.pluginId}）",
                isError = true
            )
        }
        if (!state.pluginsEnabled) {
            return finish(tool, argumentsJson, started, "插件功能已在设置中关闭", isError = true)
        }
        if (!plugin.enabled) {
            return finish(tool, argumentsJson, started, "插件「${plugin.name}」已停用", isError = true)
        }
        if (ToolPolicies.effective(plugin, tool) == ToolPolicy.DISABLED) {
            return finish(tool, argumentsJson, started, "工具已被禁用：${tool.name}", isError = true)
        }

        val effectiveArguments = injectHostId(plugin, tool, argumentsJson)
        val result = try {
            val mcpResult = manager.callTool(plugin, tool.name, effectiveArguments)
            ToolExecResult(
                output = mcpResult.output,
                isError = mcpResult.isError,
                durationMs = System.currentTimeMillis() - started
            )
        } catch (cancelled: CancellationException) {
            // Never turn "the user pressed stop" into a tool result.
            throw cancelled
        } catch (auth: McpAuthRequiredException) {
            ToolExecResult(
                output = humanize(plugin, auth.message ?: "需要重新授权"),
                isError = true,
                durationMs = System.currentTimeMillis() - started
            )
        } catch (failure: Exception) {
            ToolExecResult(
                output = humanize(plugin, failure.message ?: failure.javaClass.simpleName),
                isError = true,
                durationMs = System.currentTimeMillis() - started
            )
        }

        recordAudit(tool, effectiveArguments, result, started)
        return result
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Adds the user's selected SentinelX host to tools that declare a
     * `host_id` parameter, unless the model already passed one.
     */
    internal fun injectHostId(plugin: PluginConfig, tool: ToolDescriptor, argumentsJson: String): String {
        if (!plugin.isSentinelX) return argumentsJson
        if (!ToolSchema.hasParameter(tool.inputSchemaJson, ToolSchema.HOST_ID)) return argumentsJson
        val hostId = hostIdProvider()?.takeIf { it.isNotBlank() } ?: return argumentsJson

        val trimmed = argumentsJson.trim().ifEmpty { "{}" }
        val parsed = McpJson.parse(trimmed) as? JsonObject ?: return argumentsJson
        val existing = parsed[ToolSchema.HOST_ID]
        if (existing != null && existing !is JsonNull) return argumentsJson

        val merged = buildJsonObject {
            parsed.forEach { (key, value) -> put(key, value) }
            put(ToolSchema.HOST_ID, hostId)
        }
        return merged.toString()
    }

    private fun humanize(plugin: PluginConfig, message: String): String {
        if (!plugin.isSentinelX) return message
        return SentinelXPreset.explainError(message) ?: message
    }

    private suspend fun finish(
        tool: ToolDescriptor,
        argumentsJson: String,
        started: Long,
        output: String,
        isError: Boolean
    ): ToolExecResult {
        val result = ToolExecResult(
            output = output,
            isError = isError,
            durationMs = System.currentTimeMillis() - started
        )
        recordAudit(tool, argumentsJson, result, started)
        return result
    }

    private suspend fun recordAudit(
        tool: ToolDescriptor,
        argumentsJson: String,
        result: ToolExecResult,
        startedAt: Long
    ) {
        val db = database ?: return
        // With several conversations generating at once "the last opened one"
        // is the wrong answer: the chat loop tags its coroutine instead.
        val conversationId = currentCoroutineContext()[ConversationContext]?.conversationId
            ?: conversationIdProvider()
            ?: ""
        runCatching {
            db.recordAudit(
                ToolAudit(
                    conversationId = conversationId,
                    pluginId = tool.pluginId,
                    pluginName = tool.pluginName,
                    toolName = tool.name,
                    argumentsJson = summarize(argumentsJson, ARGUMENTS_PREVIEW),
                    resultPreview = summarize(result.output, RESULT_PREVIEW),
                    isError = result.isError,
                    durationMs = result.durationMs,
                    createdAt = startedAt
                )
            )
        }
    }

    private fun summarize(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit) + "…（共 ${text.length} 字符）"

    private companion object {
        const val ARGUMENTS_PREVIEW = 600
        const val RESULT_PREVIEW = 1_000
    }
}
