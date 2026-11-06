package app.kujira.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.MessageStatus
import app.kujira.android.data.model.Role
import app.kujira.android.data.model.Usage
import app.kujira.android.domain.formatCost
import app.kujira.android.domain.formatTokenCount
import app.kujira.android.ui.chat.markdown.MarkdownText
import app.kujira.android.ui.components.KujiraPreviewBox
import app.kujira.android.ui.components.LoadingDots
import app.kujira.android.ui.components.rememberClipboardCopy

/**
 * One message in the transcript.
 *
 * - user: right aligned bubble, 82% max width, surfaceContainerHigh, 22dp
 *   corners (6dp on the bottom-right).
 * - assistant: full-bleed, no bubble, thin ember accent line on the left.
 *
 * Long-press opens the action menu. Copy always works against the platform
 * clipboard; the optional callbacks are for the Lead's ViewModel (regenerate,
 * edit, delete, retry, tool approval).
 */
@Composable
fun MessageItem(
    message: Message,
    modifier: Modifier = Modifier,
    animateIn: Boolean = false,
    isStreaming: Boolean = message.status == MessageStatus.STREAMING,
    showReasoning: Boolean = true,
    codeFontEnabled: Boolean = true,
    awaitingToolCallId: String? = null,
    onCopy: ((Message) -> Unit)? = null,
    onRegenerate: ((Message) -> Unit)? = null,
    onEdit: ((Message) -> Unit)? = null,
    onDelete: ((Message) -> Unit)? = null,
    onRetry: ((Message) -> Unit)? = null,
    onToolDecision: ((ContentBlock.ToolCall, Boolean) -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        when (message.role) {
            Role.USER -> UserMessage(
                message = message,
                codeFontEnabled = codeFontEnabled,
                onCopy = onCopy,
                onEdit = onEdit,
                onDelete = onDelete,
            )

            Role.ASSISTANT -> AssistantMessage(
                message = message,
                isStreaming = isStreaming,
                showReasoning = showReasoning,
                codeFontEnabled = codeFontEnabled,
                awaitingToolCallId = awaitingToolCallId,
                onCopy = onCopy,
                onRegenerate = onRegenerate,
                onDelete = onDelete,
                onRetry = onRetry,
                onToolDecision = onToolDecision,
            )

            Role.SYSTEM -> Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Role.TOOL -> Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier.fillMaxWidth(),
            )
        }
    }

    if (!animateIn) {
        content()
    } else {
        val density = LocalDensity.current
        val visibleState = remember(message.id) {
            MutableTransitionState(false).apply { targetState = true }
        }
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(animationSpec = tween(180)) +
                slideInVertically(animationSpec = tween(180)) {
                    with(density) { 12.dp.roundToPx() }
                },
            exit = ExitTransition.None,
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessage(
    message: Message,
    codeFontEnabled: Boolean,
    onCopy: ((Message) -> Unit)?,
    onEdit: ((Message) -> Unit)?,
    onDelete: ((Message) -> Unit)?,
) {
    val clipboardCopy = rememberClipboardCopy()
    val interactionSource = remember { MutableInteractionSource() }
    var menuExpanded by rememberSaveable(message.id) { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bubbleMaxWidth = maxWidth * 0.82f
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopEnd,
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .widthIn(max = bubbleMaxWidth)
                        .clip(
                            RoundedCornerShape(
                                topStart = 22.dp,
                                topEnd = 22.dp,
                                bottomEnd = 6.dp,
                                bottomStart = 22.dp,
                            )
                        )
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {},
                            onLongClickLabel = "消息操作",
                            onLongClick = { menuExpanded = true },
                        )
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                ) {
                    MarkdownText(
                        markdown = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        codeFontEnabled = codeFontEnabled,
                    )
                }
            }
            MessageActionsMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                canEdit = true,
                canRegenerate = false,
                onCopy = {
                    clipboardCopy(message.text)
                    onCopy?.invoke(message)
                },
                onEdit = { onEdit?.invoke(message) },
                onDelete = { onDelete?.invoke(message) },
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    message: Message,
    isStreaming: Boolean,
    showReasoning: Boolean,
    codeFontEnabled: Boolean,
    awaitingToolCallId: String?,
    onCopy: ((Message) -> Unit)?,
    onRegenerate: ((Message) -> Unit)?,
    onDelete: ((Message) -> Unit)?,
    onRetry: ((Message) -> Unit)?,
    onToolDecision: ((ContentBlock.ToolCall, Boolean) -> Unit)?,
) {
    val clipboardCopy = rememberClipboardCopy()
    val interactionSource = remember { MutableInteractionSource() }
    var menuExpanded by rememberSaveable(message.id) { mutableStateOf(false) }

    val toolResults = remember(message.blocks) {
        message.blocks.filterIsInstance<ContentBlock.ToolResult>().associateBy { it.callId }
    }
    val lastTextIndex = remember(message.blocks) {
        message.blocks.indexOfLast { it is ContentBlock.Text }
    }
    val lastIndex = message.blocks.lastIndex
    val hasVisibleText = remember(message.blocks) {
        message.blocks.any { it is ContentBlock.Text && it.text.isNotBlank() }
    }
    val hasToolCalls = remember(message.blocks) {
        message.blocks.any { it is ContentBlock.ToolCall }
    }
    val reasoning = message.reasoning

    val accentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val barWidth = 2.dp.toPx()
                    drawRoundRect(
                        color = accentColor,
                        topLeft = Offset(0f, 0f),
                        size = Size(barWidth, size.height),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                    )
                }
                .padding(start = 13.dp)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                    onLongClickLabel = "消息操作",
                    onLongClick = { menuExpanded = true },
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
                if (showReasoning && reasoning.isNotBlank()) {
                    ReasoningPanel(
                        text = reasoning,
                        isStreaming = isStreaming && !hasVisibleText,
                    )
                }

                message.blocks.forEachIndexed { index, block ->
                    when (block) {
                        is ContentBlock.Text -> {
                            if (block.text.isNotBlank() || isStreaming) {
                                MarkdownText(
                                    markdown = block.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    isStreaming = isStreaming &&
                                        index == lastTextIndex &&
                                        index == lastIndex,
                                    codeFontEnabled = codeFontEnabled,
                                )
                            }
                        }

                        is ContentBlock.ToolCall -> {
                            val result = toolResults[block.id]
                            val state = when {
                                awaitingToolCallId == block.id -> ToolCallState.AWAITING_CONFIRMATION
                                result != null -> defaultToolCallState(result)
                                else -> ToolCallState.RUNNING
                            }
                            ToolCallCard(
                                call = block,
                                result = result,
                                state = state,
                                onApprove = { onToolDecision?.invoke(block, true) },
                                onReject = { onToolDecision?.invoke(block, false) },
                            )
                        }

                        is ContentBlock.ToolResult, is ContentBlock.Reasoning -> Unit
                    }
                }

                if (isStreaming && !hasVisibleText && reasoning.isBlank() && !hasToolCalls) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LoadingDots()
                        Text(
                            text = "正在思考…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (!isStreaming && message.usage != null && message.usage.totalTokens > 0) {
                    MessageUsageFooter(message.usage)
                }

                if (message.status == MessageStatus.STOPPED) {
                    Text(
                        text = "已停止生成",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (message.error != null || message.status == MessageStatus.ERROR) {
                    MessageErrorBar(
                        message = message,
                        onRetry = onRetry,
                    )
                }
        }
        MessageActionsMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            canEdit = false,
            canRegenerate = onRegenerate != null,
            onCopy = {
                clipboardCopy(message.text)
                onCopy?.invoke(message)
            },
            onRegenerate = { onRegenerate?.invoke(message) },
            onDelete = { onDelete?.invoke(message) },
        )
    }
}

@Composable
private fun MessageUsageFooter(usage: Usage) {
    val parts = buildList {
        if (usage.promptTokens > 0) add("输入 ${formatTokenCount(usage.promptTokens)}")
        if (usage.completionTokens > 0) add("输出 ${formatTokenCount(usage.completionTokens)}")
        usage.cacheReadTokens?.takeIf { it > 0 }?.let { add("缓存 ${formatTokenCount(it)}") }
        usage.reasoningTokens?.takeIf { it > 0 }?.let { add("思考 ${formatTokenCount(it)}") }
        val goBilling = usage.billingSource == "go" || usage.billingSource == "go-plus"
        usage.costUsd?.takeIf { !(goBilling && it == 0.0) }?.let { add(formatCost(it)) }
        billingLabel(usage.billingSource)?.let(::add)
        usage.allowanceUsd?.takeIf { it > 0 }?.let { add("额度 ${formatCost(it)}") }
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        modifier = Modifier.padding(top = 2.dp),
    )
}

private fun billingLabel(source: String?): String? = when (source) {
    null, "" -> null
    "free" -> "免费"
    "balance" -> "余额"
    "go" -> "Go"
    "go-plus" -> "Go Plus"
    "byok" -> "BYOK"
    else -> source
}

@Composable
private fun ReasoningPanel(
    text: String,
    isStreaming: Boolean,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "reasoning-chevron",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isStreaming) "思考中…" else "思考过程",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (isStreaming) {
                LoadingDots(
                    modifier = Modifier.padding(end = 8.dp),
                    dotSize = 4.dp,
                    spacing = 3.dp,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                modifier = Modifier
                    .size(16.dp)
                    .rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = Spring.StiffnessMediumLow,
                )
            ) + fadeIn(),
            exit = shrinkVertically(
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = Spring.StiffnessMediumLow,
                )
            ) + fadeOut(),
        ) {
            MarkdownText(
                markdown = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun MessageErrorBar(
    message: Message,
    onRetry: ((Message) -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(16.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.error ?: "生成失败，请重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (onRetry != null) {
                    TextButton(
                        onClick = { onRetry.invoke(message) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(
                            text = "重试",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    canEdit: Boolean,
    canRegenerate: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onDelete: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        DropdownMenuItem(
            text = { Text("复制", style = MaterialTheme.typography.labelLarge) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = {
                onDismiss()
                onCopy()
            },
        )
        if (canRegenerate) {
            DropdownMenuItem(
                text = { Text("重新生成", style = MaterialTheme.typography.labelLarge) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    onDismiss()
                    onRegenerate()
                },
            )
        }
        if (canEdit) {
            DropdownMenuItem(
                text = { Text("编辑", style = MaterialTheme.typography.labelLarge) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    onDismiss()
                    onEdit()
                },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        DropdownMenuItem(
            text = {
                Text(
                    text = "删除",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = {
                onDismiss()
                onDelete()
            },
        )
    }
}

private val previewUser = previewUserMessage(
    "帮我看看服务器状态，顺便把 nginx 日志里最近的报错找出来",
)

private val previewAssistantReply = previewAssistantMessage(
    blocks = listOf(
        ContentBlock.Reasoning("先调用 sentinel_state 看整体状态，再决定要不要拉日志。"),
        ContentBlock.Text("好的，我先看一下整体状态。"),
        previewToolCall(
            name = "sentinel_state",
            argumentsJson = "{\"host_id\":\"srv-01\",\"detail\":\"summary\"}",
            id = "preview-call-state",
        ),
        previewToolResult(
            callId = "preview-call-state",
            toolName = "sentinel_state",
            output = "{\"ok\":true,\"cpu_pct\":12,\"services_running\":24}",
        ),
        ContentBlock.Text(previewAssistantMarkdown),
    ),
)

private val previewStreamingReply = previewAssistantMessage(
    blocks = listOf(
        ContentBlock.Text("正在检查日志，稍等……\n\n- 已读取 nginx 日志\n- 正在过滤 **5xx**"),
    ),
    id = "preview-streaming",
    status = MessageStatus.STREAMING,
)

private val previewErrorReply = previewAssistantMessage(
    blocks = listOf(ContentBlock.Text("")),
    id = "preview-error",
    status = MessageStatus.ERROR,
    error = "429 Too Many Requests：请求太频繁了，等几秒再试。",
)

@Preview(name = "Messages dark", showBackground = true, widthDp = 400)
@Composable
private fun MessagesDarkPreview() {
    KujiraPreviewBox(dark = true) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            MessageItem(message = previewUser, animateIn = false)
            MessageItem(message = previewAssistantReply)
            MessageItem(message = previewStreamingReply, isStreaming = true)
            MessageItem(message = previewErrorReply, onRetry = {})
        }
    }
}

@Preview(name = "Messages light", showBackground = true, widthDp = 400)
@Composable
private fun MessagesLightPreview() {
    KujiraPreviewBox(dark = false) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            MessageItem(message = previewUser)
            MessageItem(
                message = previewAssistantMessage(
                    blocks = listOf(ContentBlock.Text(previewAssistantMarkdown)),
                    id = "light-reply",
                ),
            )
        }
    }
}
