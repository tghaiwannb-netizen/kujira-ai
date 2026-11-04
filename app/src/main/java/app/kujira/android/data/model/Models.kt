package app.kujira.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/*
 * Shared domain model. This file is the contract between the data, network,
 * MCP and UI layers — change it deliberately and update every consumer.
 */

@Serializable
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

@Serializable
enum class MessageStatus { PENDING, STREAMING, COMPLETE, STOPPED, ERROR }

/** A single structured piece of an assistant turn. */
@Serializable
sealed interface ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock

    /** The model asked to run a tool. */
    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String,
        val pluginId: String,
        val pluginName: String
    ) : ContentBlock

    /** Result of executing a tool, appended back to the transcript. */
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val callId: String,
        val toolName: String,
        val pluginId: String,
        val output: String,
        val isError: Boolean = false,
        val durationMs: Long = 0L
    ) : ContentBlock

    /** Provider-specific reasoning/thinking stream (DeepSeek R1, Claude thinking). */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val text: String) : ContentBlock
}

@Serializable
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: Role,
    val blocks: List<ContentBlock> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val providerId: String? = null,
    val modelId: String? = null,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val error: String? = null,
    val usage: Usage? = null
) {
    /** Concatenated visible text across blocks. */
    val text: String
        get() = blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }

    val reasoning: String
        get() = blocks.filterIsInstance<ContentBlock.Reasoning>().joinToString("") { it.text }
}

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val providerId: String? = null,
    val modelId: String? = null,
    val systemPrompt: String? = null,
    val archived: Boolean = false
)

// ---------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------

@Serializable
enum class ProviderKind {
    OPENAI, ANTHROPIC, DEEPSEEK, OPENROUTER, CUSTOM
}

@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String = id,
    val contextWindow: Int? = null,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false
)

@Serializable
data class ProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val kind: ProviderKind,
    val name: String,
    val baseUrl: String,
    /** Reference into [app.kujira.android.data.prefs.SecureStore], never the key itself. */
    val secretKey: String = "provider:$id",
    val models: List<ModelInfo> = emptyList(),
    val defaultModelId: String? = null,
    val enabled: Boolean = true,
    val builtin: Boolean = false
) {
    fun defaultModel(): ModelInfo? =
        models.firstOrNull { it.id == defaultModelId } ?: models.firstOrNull()
}

// ---------------------------------------------------------------------------
// MCP plugins
// ---------------------------------------------------------------------------

@Serializable
enum class McpAuthMode { NONE, BEARER, OAUTH }

@Serializable
enum class PluginStatus { UNKNOWN, CONNECTING, CONNECTED, ERROR, AUTH_REQUIRED }

/** How a discovered tool may be used by the model. */
@Serializable
enum class ToolPolicy { AUTO, ASK, DISABLED }

@Serializable
data class PluginConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emoji: String = "🔌",
    val url: String,
    val authMode: McpAuthMode = McpAuthMode.NONE,
    val enabled: Boolean = true,
    val builtin: Boolean = false,
    /** Non-null marks a built-in preset, e.g. "sentinelx". */
    val preset: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Tool name -> policy. Missing entries fall back to [defaultPolicyFor]. */
    val toolPolicies: Map<String, ToolPolicy> = emptyMap(),
    val status: PluginStatus = PluginStatus.UNKNOWN,
    val serverName: String? = null,
    val serverVersion: String? = null,
    val protocolVersion: String? = null,
    val lastError: String? = null,
    val lastConnectedAt: Long? = null,
    /** Which tools were enabled at the last known discovery, for context budgeting. */
    val enabledToolNames: List<String> = emptyList()
) {
    fun policyFor(toolName: String, dangerous: Boolean): ToolPolicy =
        toolPolicies[toolName] ?: if (dangerous) ToolPolicy.ASK else ToolPolicy.AUTO

    val isSentinelX: Boolean get() = preset == "sentinelx"
}

/** Runtime tool description discovered from a server; not persisted. */
data class ToolDescriptor(
    val pluginId: String,
    val pluginName: String,
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val serverName: String? = null
) {
    /** Heuristic danger classification used for default confirmation policy. */
    val isDangerous: Boolean
        get() {
            val n = name.lowercase()
            return DANGEROUS_TOKENS.any { n.contains(it) }
        }

    companion object {
        val DANGEROUS_TOKENS = listOf(
            "exec", "delete", "delete", "remove", "restart", "stop", "destroy",
            "edit", "write", "upload", "chmod", "chown", "move", "copy",
            "power_off", "destroy", "send_message", "send_email",
            "create_record", "update_record", "delete_record"
        )
    }
}

/** One invocation of a tool, recorded for the audit log. */
data class ToolAudit(
    val id: Long = 0L,
    val conversationId: String,
    val pluginId: String,
    val pluginName: String,
    val toolName: String,
    val argumentsJson: String,
    val resultPreview: String,
    val isError: Boolean,
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis()
)

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@Serializable
enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val fontScale: Float = 1.0f,
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val topP: Double = 1.0,
    val systemPrompt: String = "",
    val activeProviderId: String? = null,
    val activeModelId: String? = null,
    val lastConversationId: String? = null,
    val pluginsEnabled: Boolean = true,
    val toolLoopLimit: Int = 12,
    val showReasoning: Boolean = true,
    /** Selected SentinelX host id, injected into tool calls that accept it. */
    val sentinelHostId: String? = null,
    val sentinelHostLabel: String? = null
)
