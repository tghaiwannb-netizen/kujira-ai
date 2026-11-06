package app.kujira.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kujira.android.data.model.ToolAudit
import app.kujira.android.ui.components.KujiraChip
import app.kujira.android.ui.components.KujiraConfirmDialog
import app.kujira.android.ui.components.KujiraIconButton
import app.kujira.android.ui.components.KujiraTopBar
import app.kujira.android.ui.theme.KujiraTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AuditFilter { ALL, FAILED }

/**
 * Local tool-call audit trail. The database already recorded every MCP call;
 * this is the missing read-only view with failure filtering and expandable
 * arguments/results.
 */
@Composable
fun AuditLogScreen(
    audits: List<ToolAudit>,
    onBack: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    var filter by remember { mutableStateOf(AuditFilter.ALL) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val filtered = remember(audits, filter) {
        if (filter == AuditFilter.FAILED) audits.filter { it.isError } else audits
    }
    val errors = audits.count { it.isError }
    val avgDuration = if (audits.isEmpty()) 0 else audits.sumOf { it.durationMs } / audits.size

    Column(modifier = modifier.fillMaxSize()) {
        KujiraTopBar(
            title = "工具审计",
            subtitle = "${audits.size} 次调用 · $errors 次失败",
            onBack = onBack,
            actions = {
                if (audits.isNotEmpty()) {
                    KujiraIconButton(
                        icon = Icons.Outlined.DeleteSweep,
                        contentDescription = "清空审计日志",
                        onClick = { showClearConfirm = true },
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KujiraChip(
                    label = "全部 ${audits.size}",
                    selected = filter == AuditFilter.ALL,
                    onClick = { filter = AuditFilter.ALL },
                )
                KujiraChip(
                    label = "失败 $errors",
                    selected = filter == AuditFilter.FAILED,
                    onClick = { filter = AuditFilter.FAILED },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (loading) {
                    "正在读取本地审计记录…"
                } else {
                    "平均耗时 ${avgDuration}ms · 参数与结果只保存在本机"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
        }

        if (filtered.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (audits.isEmpty()) "还没有工具调用记录" else "没有失败的调用",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (audits.isEmpty()) {
                        "在聊天里让模型调用一次 MCP 工具后，这里会出现审计记录。"
                    } else {
                        "切换到「全部」查看成功调用。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.id }) { audit ->
                    AuditCard(
                        audit = audit,
                        expanded = expandedId == audit.id,
                        onClick = {
                            expandedId = if (expandedId == audit.id) null else audit.id
                        },
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        KujiraConfirmDialog(
            title = "清空工具审计？",
            message = "所有工具调用记录都会被删除，且无法恢复。会话和消息不受影响。",
            confirmText = "清空",
            destructive = true,
            onConfirm = {
                showClearConfirm = false
                onClear()
            },
            onDismiss = { showClearConfirm = false },
        )
    }
}

@Composable
private fun AuditCard(
    audit: ToolAudit,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val time = remember(audit.createdAt) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(audit.createdAt))
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (audit.isError) {
                        Icons.Outlined.ErrorOutline
                    } else {
                        Icons.Outlined.CheckCircleOutline
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (audit.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = audit.toolName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${audit.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${audit.pluginName} · $time",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = audit.argumentsJson,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (audit.isError) "错误结果" else "执行结果",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (audit.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = audit.resultPreview.ifBlank { "（空）" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "会话 ${audit.conversationId.take(8)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Preview(name = "Audit dark", showBackground = true, widthDp = 400, heightDp = 820)
@Composable
private fun AuditDarkPreview() {
    KujiraTheme(darkTheme = true) {
        AuditLogScreen(
            audits = listOf(
                ToolAudit(
                    id = 1,
                    conversationId = "e8c0f3a6-9f1e-4aab-9a52-1234567890ab",
                    pluginId = "sentinelx",
                    pluginName = "SentinelX",
                    toolName = "sentinel_exec",
                    argumentsJson = """{"host_id":"prod-01","command":"df -h"}""",
                    resultPreview = "/dev/vda1  40G  12G  26G  32% /",
                    isError = false,
                    durationMs = 320,
                ),
                ToolAudit(
                    id = 2,
                    conversationId = "e8c0f3a6-9f1e-4aab-9a52-1234567890ab",
                    pluginId = "sentinelx",
                    pluginName = "SentinelX",
                    toolName = "sentinel_restart",
                    argumentsJson = """{"host_id":"prod-01","service":"nginx"}""",
                    resultPreview = "permission_denied: token cannot restart nginx",
                    isError = true,
                    durationMs = 128,
                ),
            ),
            onBack = {},
            onClear = {},
        )
    }
}
