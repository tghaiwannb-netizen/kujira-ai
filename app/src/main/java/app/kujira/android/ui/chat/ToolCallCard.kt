package app.kujira.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.ToolDescriptor
import app.kujira.android.ui.chat.markdown.MarkdownText
import app.kujira.android.ui.components.KujiraButton
import app.kujira.android.ui.components.KujiraButtonStyle
import app.kujira.android.ui.components.KujiraPreviewBox
import app.kujira.android.ui.components.rememberClipboardCopy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Visual state of one tool invocation as rendered in the transcript. */
enum class ToolCallState { RUNNING, SUCCESS, ERROR, AWAITING_CONFIRMATION }

/** Mirrors the default danger policy used by [ToolDescriptor.isDangerous]. */
fun isDangerousTool(name: String): Boolean {
    val normalized = name.lowercase()
    return ToolDescriptor.DANGEROUS_TOKENS.any { normalized.contains(it) }
}

/** Derives the card state from a tool result when the caller has nothing better. */
fun defaultToolCallState(result: ContentBlock.ToolResult?): ToolCallState = when {
    result == null -> ToolCallState.RUNNING
    result.isError -> ToolCallState.ERROR
    else -> ToolCallState.SUCCESS
}

private val prettyJson = Json {
    prettyPrint = true
    isLenient = true
}

private fun prettyJsonOrNull(raw: String): String? = try {
    prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw))
} catch (_: Exception) {
    null
}

private fun formatDuration(durationMs: Long): String =
    if (durationMs < 1000) "${durationMs}ms" else String.format("%.1fs", durationMs / 1000.0)

/**
 * One tool invocation inside an assistant turn:
 * header (status + tool name + MCP badge + danger badge), collapsible body with
 * arguments and result, plus an inline approve/reject row for `ask`-policy
 * tools.
 */
@Composable
fun ToolCallCard(
    call: ContentBlock.ToolCall,
    modifier: Modifier = Modifier,
    result: ContentBlock.ToolResult? = null,
    state: ToolCallState = defaultToolCallState(result),
    dangerous: Boolean = isDangerousTool(call.name),
    initiallyExpanded: Boolean = false,
    onCopyArguments: ((String) -> Unit)? = null,
    onCopyResult: ((String) -> Unit)? = null,
    onApprove: (() -> Unit)? = null,
    onReject: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable(call.id) {
        mutableStateOf(initiallyExpanded || state == ToolCallState.AWAITING_CONFIRMATION)
    }
    val clipboardCopy = rememberClipboardCopy()
    val arguments = remember(call.argumentsJson) {
        prettyJsonOrNull(call.argumentsJson) ?: call.argumentsJson
    }
    val resultOutput = result?.output.orEmpty()
    val resultJson = remember(resultOutput) {
        if (resultOutput.startsWith("{") || resultOutput.startsWith("[")) {
            prettyJsonOrNull(resultOutput)
        } else {
            null
        }
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "tool-chevron",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolStatusIcon(state = state)
                Spacer(Modifier.width(9.dp))
                Text(
                    text = call.name,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (dangerous) {
                    ToolBadge(
                        text = "危险",
                        contentColor = MaterialTheme.colorScheme.error,
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                ToolBadge(
                    text = call.pluginName.ifBlank { "MCP" },
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier
                        .size(18.dp)
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
                Column(modifier = Modifier.animateContentSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionLabel(
                            text = "参数",
                            onCopy = {
                                clipboardCopy(arguments)
                                onCopyArguments?.invoke(arguments)
                            },
                        )
                        JsonSurface(text = arguments)

                        if (state == ToolCallState.AWAITING_CONFIRMATION) {
                            Text(
                                text = "该工具被标记为需要确认，执行前请检查参数。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            ) {
                                KujiraButton(
                                    text = "拒绝",
                                    onClick = { onReject?.invoke() },
                                    style = KujiraButtonStyle.TONAL,
                                )
                                KujiraButton(
                                    text = "允许执行",
                                    onClick = { onApprove?.invoke() },
                                    style = KujiraButtonStyle.PRIMARY,
                                )
                            }
                        }

                        if (result != null) {
                            SectionLabel(
                                text = if (state == ToolCallState.ERROR) "错误" else "结果",
                                onCopy = {
                                    clipboardCopy(result.output)
                                    onCopyResult?.invoke(result.output)
                                },
                            )
                            when {
                                resultJson != null -> JsonSurface(
                                    text = resultJson,
                                    contentColor = if (state == ToolCallState.ERROR) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )

                                state == ToolCallState.ERROR -> Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
                                        .padding(10.dp),
                                ) {
                                    Text(
                                        text = result.output,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }

                                else -> Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 280.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    MarkdownText(
                                        markdown = result.output,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            if (result.durationMs > 0) {
                                Text(
                                    text = "耗时 ${formatDuration(result.durationMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (state == ToolCallState.RUNNING) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "正在执行…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolStatusIcon(state: ToolCallState) {
    when (state) {
        ToolCallState.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(15.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 1.6.dp,
        )

        ToolCallState.SUCCESS -> Icon(
            imageVector = Icons.Outlined.CheckCircleOutline,
            contentDescription = "成功",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.tertiary,
        )

        ToolCallState.ERROR -> Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = "失败",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.error,
        )

        ToolCallState.AWAITING_CONFIRMATION -> Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = "等待确认",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ToolBadge(
    text: String,
    contentColor: Color,
    containerColor: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionLabel(text: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onCopy)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "复制",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "复制",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun JsonSurface(
    text: String,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Box(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
                color = contentColor,
                softWrap = false,
            )
        }
    }
}

@Preview(name = "ToolCall dark", showBackground = true, widthDp = 400)
@Composable
private fun ToolCallDarkPreview() {
    KujiraPreviewBox(dark = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolCallCard(
                call = previewToolCall(
                    name = "sentinel_state",
                    argumentsJson = "{\"host_id\":\"srv-01\",\"detail\":\"summary\"}",
                ),
            )
            ToolCallCard(
                call = previewToolCall(
                    name = "sentinel_exec",
                    argumentsJson = "{\"host_id\":\"srv-01\",\"command\":\"df -h\"}",
                ),
                result = ContentBlock.ToolResult(
                    callId = "sentinel_exec",
                    toolName = "sentinel_exec",
                    pluginId = "sentinelx",
                    output = "Filesystem      Size  Used Avail Use%\n/dev/disk1s1   460Gi 312Gi 148Gi  68%",
                    durationMs = 1240,
                ),
                initiallyExpanded = true,
            )
            ToolCallCard(
                call = previewToolCall(
                    name = "cloudflare_dns_delete_record",
                    argumentsJson = "{\"zone_id\":\"abc\",\"record_id\":\"def\"}",
                    pluginName = "Cloudflare",
                ),
                result = ContentBlock.ToolResult(
                    callId = "cloudflare_dns_delete_record",
                    toolName = "cloudflare_dns_delete_record",
                    pluginId = "cloudflare",
                    output = "permission_denied: token cannot delete DNS records",
                    isError = true,
                    durationMs = 320,
                ),
                initiallyExpanded = true,
            )
            ToolCallCard(
                call = previewToolCall(
                    name = "sentinel_restart",
                    argumentsJson = "{\"service\":\"nginx\"}",
                ),
                state = ToolCallState.AWAITING_CONFIRMATION,
            )
        }
    }
}

@Preview(name = "ToolCall light", showBackground = true, widthDp = 400)
@Composable
private fun ToolCallLightPreview() {
    KujiraPreviewBox(dark = false) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolCallCard(
                call = previewToolCall(
                    name = "sentinel_list_hosts",
                    argumentsJson = "{}",
                ),
                state = ToolCallState.RUNNING,
            )
            ToolCallCard(
                call = previewToolCall(
                    name = "sentinel_state",
                    argumentsJson = "{\"host_id\":\"srv-01\"}",
                ),
                result = ContentBlock.ToolResult(
                    callId = "sentinel_state",
                    toolName = "sentinel_state",
                    pluginId = "sentinelx",
                    output = "{\"ok\":true,\"cpu_pct\":12}",
                    durationMs = 820,
                ),
                initiallyExpanded = true,
            )
        }
    }
}
