package app.kujira.android.ui.chat

import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.MessageStatus
import app.kujira.android.data.model.Role

/*
 * Fake transcript data for @Preview only. The real screens get their data
 * injected by the Lead's ViewModels; nothing here is used at runtime.
 */

internal const val PREVIEW_CONVERSATION_ID = "conv-preview"

internal fun previewUserMessage(
    text: String,
    id: String = "preview-user-1",
): Message = Message(
    id = id,
    conversationId = PREVIEW_CONVERSATION_ID,
    role = Role.USER,
    blocks = listOf(ContentBlock.Text(text)),
    createdAt = 1_760_000_000_000L,
)

internal fun previewAssistantMessage(
    blocks: List<ContentBlock>,
    id: String = "preview-assistant-1",
    status: MessageStatus = MessageStatus.COMPLETE,
    error: String? = null,
): Message = Message(
    id = id,
    conversationId = PREVIEW_CONVERSATION_ID,
    role = Role.ASSISTANT,
    blocks = blocks,
    createdAt = 1_760_000_001_000L,
    status = status,
    error = error,
)

internal fun previewToolCall(
    name: String,
    argumentsJson: String,
    pluginName: String = "SentinelX",
    id: String = "call-$name",
): ContentBlock.ToolCall = ContentBlock.ToolCall(
    id = id,
    name = name,
    argumentsJson = argumentsJson,
    pluginId = "sentinelx",
    pluginName = pluginName,
)

internal fun previewToolResult(
    callId: String,
    toolName: String,
    output: String,
    isError: Boolean = false,
    durationMs: Long = 820L,
    pluginId: String = "sentinelx",
): ContentBlock.ToolResult = ContentBlock.ToolResult(
    callId = callId,
    toolName = toolName,
    pluginId = pluginId,
    output = output,
    isError = isError,
    durationMs = durationMs,
)

internal val previewAssistantMarkdown = """
    ## 状态正常

    - CPU 使用率 **12%**
    - 内存已用 **68%**
    - nginx 运行中，没有发现 5xx

    > 最近一条错误日志来自 3 天前，已经自动 rotate。
""".trimIndent()

internal val previewMessages: List<Message> = listOf(
    previewUserMessage("帮我看看服务器状态，顺便把 nginx 日志里最近的报错找出来"),
    previewAssistantMessage(
        blocks = listOf(
            ContentBlock.Reasoning("先调用 sentinel_state 看整体状态，再决定要不要拉日志。"),
            ContentBlock.Text("好的，我先看一下整体状态。"),
            previewToolCall(
                name = "sentinel_state",
                argumentsJson = "{\"host_id\":\"srv-01\",\"detail\":\"summary\"}",
                id = "call-state",
            ),
            previewToolResult(
                callId = "call-state",
                toolName = "sentinel_state",
                output = "{\"ok\":true,\"cpu_pct\":12,\"memory_used_pct\":68,\"services_running\":24}",
            ),
            ContentBlock.Text(previewAssistantMarkdown),
        ),
    ),
)
