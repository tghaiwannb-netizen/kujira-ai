package app.kujira.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.Usage
import app.kujira.android.domain.ContextLevel
import app.kujira.android.domain.ContextUsage
import app.kujira.android.domain.formatCost
import app.kujira.android.domain.formatTokenCount
import app.kujira.android.ui.components.KujiraBottomSheet
import app.kujira.android.ui.components.KujiraButton
import app.kujira.android.ui.components.KujiraButtonStyle

/**
 * Context budget breakdown, conversation usage and the non-destructive
 * compaction controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSheet(
    usage: ContextUsage,
    messages: List<Message>,
    modelLabel: String,
    summary: String? = null,
    compacting: Boolean,
    onDismiss: () -> Unit,
    onCompact: () -> Unit,
    onClearSummary: () -> Unit,
) {
    var summaryExpanded by remember { mutableStateOf(false) }
    val conversationUsage = remember(messages) {
        messages.mapNotNull { it.usage }.fold(Usage()) { acc, item -> acc + item }
    }
    val ratio = (usage.ratio ?: 0f).coerceIn(0f, 1f)
    val barColor = when (usage.level) {
        ContextLevel.NORMAL -> MaterialTheme.colorScheme.tertiary
        ContextLevel.WARN -> MaterialTheme.colorScheme.primary
        ContextLevel.CRITICAL -> MaterialTheme.colorScheme.error
    }

    KujiraBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "上下文",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = modelLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (usage.windowTokens != null) {
                                "已用 ${formatTokenCount(usage.usedTokens)} / " +
                                    formatTokenCount(usage.windowTokens)
                            } else {
                                "已用约 ${formatTokenCount(usage.usedTokens)} tokens"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        usage.ratio?.let {
                            Text(
                                text = "${(it * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = barColor,
                            )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(ratio)
                            .clip(RoundedCornerShape(4.dp))
                            .background(barColor),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        usage.windowTokens == null ->
                            "模型目录没有提供上下文窗口；同步模型后可以显示准确百分比。"

                        usage.level == ContextLevel.CRITICAL ->
                            "接近上限。继续发送会自动压缩较早的消息。"

                        usage.level == ContextLevel.WARN ->
                            "上下文偏满，建议压缩后再继续长对话。"

                        else -> "上下文充足。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        ContextStatRow("系统提示词", formatTokenCount(usage.systemTokens))
        ContextStatRow("历史摘要", formatTokenCount(usage.summaryTokens))
        ContextStatRow("对话消息", formatTokenCount(usage.messageTokens))
        usage.remainingTokens?.let { remaining ->
            ContextStatRow("剩余空间", formatTokenCount(remaining))
        }

        if (conversationUsage.totalTokens > 0) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(12.dp))
            Text(
                text = "本会话累计",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            ContextStatRow(
                "Tokens",
                "${formatTokenCount(conversationUsage.promptTokens)} 输入 · " +
                    "${formatTokenCount(conversationUsage.completionTokens)} 输出"
            )
            conversationUsage.cacheReadTokens?.takeIf { it > 0 }?.let {
                ContextStatRow("缓存命中", formatTokenCount(it))
            }
            conversationUsage.costUsd?.let {
                ContextStatRow("实际费用", formatCost(it))
            }
            conversationUsage.allowanceUsd?.takeIf { it > 0 }?.let {
                ContextStatRow("Go 额度", formatCost(it))
            }
        }

        if (usage.compacted) {
            Spacer(Modifier.height(12.dp))
            Surface(
                onClick = { if (!summary.isNullOrBlank()) summaryExpanded = !summaryExpanded },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "已启用上下文摘要。原始消息仍保留在会话里，只是后续请求不再重复发送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!summary.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (summaryExpanded) Int.MAX_VALUE else 5,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        KujiraButton(
            text = when {
                compacting -> "正在压缩上下文…"
                usage.compacted -> "继续压缩较早消息"
                else -> "压缩上下文"
            },
            onClick = onCompact,
            enabled = !compacting,
            style = KujiraButtonStyle.TONAL,
            modifier = Modifier.fillMaxWidth(),
        )
        if (usage.compacted) {
            Spacer(Modifier.height(6.dp))
            KujiraButton(
                text = "清除摘要并恢复完整上下文",
                onClick = onClearSummary,
                enabled = !compacting,
                style = KujiraButtonStyle.TEXT,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = "压缩会调用当前模型生成摘要，消耗少量 tokens；不会删除任何消息。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        }
    }
}

@Composable
private fun ContextStatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
