package app.kujira.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kujira.android.domain.UsageBucket
import app.kujira.android.domain.UsageReport
import app.kujira.android.domain.formatCost
import app.kujira.android.domain.formatTokenCount
import app.kujira.android.ui.theme.KujiraTheme

/**
 * Token / cache / cost dashboard, backed by persisted per-message usage.
 * Actual provider-reported cost wins; estimates are marked with "≈".
 */
@Composable
fun UsageSettingsScreen(
    report: UsageReport,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    providerName: (String?) -> String = { it ?: "未知 Provider" },
    modelName: (String?, String?) -> String = { _, model -> model ?: "未知模型" },
    loading: Boolean = false,
) {
    val hasData = report.allTime.calls > 0

    SettingsPage(
        title = "用量与成本",
        subtitle = if (hasData) "Token、缓存与费用统计" else "还没有可统计的用量",
        onBack = onBack,
        modifier = modifier,
    ) {
        if (loading) {
            Text(
                text = "正在统计本地用量…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        } else if (!hasData) {
            SettingsSection(title = "空") {
                SettingsRow(
                    title = "还没有用量记录",
                    subtitle = "发送几条消息后，这里会按 Provider 和模型汇总 tokens 与费用。",
                    icon = Icons.Outlined.BarChart,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                UsageSummaryCard(
                    title = "本月",
                    bucket = report.thisMonth,
                    modifier = Modifier.weight(1f),
                )
                UsageSummaryCard(
                    title = "全部",
                    bucket = report.allTime,
                    modifier = Modifier.weight(1f),
                )
            }

            SettingsSection(title = "缓存与推理") {
                SettingsValueRow(
                    title = "缓存读取",
                    value = formatTokenCount(report.allTime.cacheReadTokens),
                    icon = Icons.Outlined.Storage,
                )
                SettingsDivider()
                SettingsValueRow(
                    title = "缓存写入",
                    value = formatTokenCount(report.allTime.cacheWriteTokens),
                    icon = Icons.Outlined.Storage,
                )
                SettingsDivider()
                SettingsValueRow(
                    title = "推理 tokens",
                    value = formatTokenCount(report.allTime.reasoningTokens),
                    icon = Icons.Outlined.Bolt,
                )
                report.allTime.cacheHitRate?.let { rate ->
                    SettingsDivider()
                    SettingsValueRow(
                        title = "缓存命中率（估算）",
                        value = "${(rate * 100).toInt()}%",
                        icon = Icons.Outlined.Storage,
                    )
                }
                if (report.allTime.allowanceUsd > 0) {
                    SettingsDivider()
                    SettingsValueRow(
                        title = "OpenCode Go 额度消耗",
                        value = formatCost(report.allTime.allowanceUsd),
                        icon = Icons.Outlined.Bolt,
                    )
                }
            }

            SettingsSection(title = "按 Provider") {
                if (report.byProvider.isEmpty()) {
                    SettingsRow(title = "暂无数据", subtitle = null)
                } else {
                    report.byProvider.forEachIndexed { index, bucket ->
                        if (index > 0) SettingsDivider()
                        UsageBucketRow(
                            title = providerName(bucket.providerId),
                            bucket = bucket,
                        )
                    }
                }
            }

            SettingsSection(title = "按模型") {
                if (report.byModel.isEmpty()) {
                    SettingsRow(title = "暂无数据", subtitle = null)
                } else {
                    report.byModel.take(12).forEachIndexed { index, bucket ->
                        if (index > 0) SettingsDivider()
                        UsageBucketRow(
                            title = modelName(bucket.providerId, bucket.modelId),
                            bucket = bucket,
                            subtitle = providerName(bucket.providerId),
                        )
                    }
                    if (report.byModel.size > 12) {
                        SettingsDivider()
                        SettingsRow(
                            title = "还有 ${report.byModel.size - 12} 个模型",
                            subtitle = "导出数据后可用 JSON 查看完整明细",
                        )
                    }
                }
            }

            Text(
                text = "费用优先使用 Provider 返回的实际值（OpenCode 会直接返回 cost）；" +
                    "没有实际值时，按同步到的模型价格估算，带 ≈ 标记。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun UsageSummaryCard(
    title: String,
    bucket: UsageBucket,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = formatTokenCount(bucket.totalTokens),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "tokens · ${bucket.calls} 次调用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = costLabel(bucket),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (bucket.hasEstimate) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "≈",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageBucketRow(
    title: String,
    bucket: UsageBucket,
    subtitle: String? = null,
) {
    SettingsRow(
        title = title,
        subtitle = buildString {
            append("${formatTokenCount(bucket.totalTokens)} tokens")
            append(" · ${bucket.calls} 次")
            subtitle?.let { append(" · $it") }
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = costLabel(bucket),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (bucket.hasEstimate) {
                    Text(
                        text = "估算",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        },
    )
}

private fun costLabel(bucket: UsageBucket): String =
    if (bucket.hasCost) formatCost(bucket.costUsd) else "未定价"

@Preview(name = "Usage dark", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun UsageDarkPreview() {
    KujiraTheme(darkTheme = true) {
        UsageSettingsScreen(
            report = fakeReport(),
            onBack = {},
            providerName = { id -> if (id == "opencode-go") "OpenCode Go" else "DeepSeek" },
            modelName = { _, id -> id ?: "unknown" },
        )
    }
}

private fun fakeReport(): UsageReport {
    val all = UsageBucket(
        calls = 42,
        promptTokens = 1_250_000,
        completionTokens = 180_000,
        totalTokens = 1_430_000,
        cacheReadTokens = 820_000,
        reasoningTokens = 42_000,
        actualCostUsd = 3.21,
        estimatedCostUsd = 0.42,
        estimatedCalls = 3,
        pricedCalls = 42,
    )
    return UsageReport(
        allTime = all,
        thisMonth = all.copy(calls = 12, totalTokens = 420_000, actualCostUsd = 1.12, estimatedCostUsd = 0.1),
        byProvider = listOf(
            all.copy(providerId = "opencode-go"),
            all.copy(providerId = "deepseek", totalTokens = 120_000, actualCostUsd = 0.3),
        ),
        byModel = listOf(
            all.copy(providerId = "opencode-go", modelId = "deepseek-v4.1-flash"),
            all.copy(providerId = "opencode-go", modelId = "kimi-k3", totalTokens = 300_000),
        ),
    )
}
