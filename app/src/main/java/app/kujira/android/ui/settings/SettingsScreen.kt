package app.kujira.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kujira.android.data.model.AppSettings
import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.PluginStatus
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ThemeMode
import app.kujira.android.ui.components.KujiraTopBar
import app.kujira.android.ui.theme.KujiraTheme

/**
 * Root settings page. A quiet list of entry rows; each sub-page owns its own
 * screen (appearance / models / plugins / data / about) and is passed in as a
 * navigation callback.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    modifier: Modifier = Modifier,
    providers: List<ProviderConfig> = emptyList(),
    plugins: List<PluginConfig> = emptyList(),
    appVersion: String = "1.3.3",
    onBack: (() -> Unit)? = null,
    onShowReasoningChange: (Boolean) -> Unit = {},
    onNavigateAppearance: () -> Unit = {},
    onNavigateModels: () -> Unit = {},
    onNavigateUsage: () -> Unit = {},
    onNavigatePlugins: () -> Unit = {},
    onNavigateAudit: () -> Unit = {},
    onNavigateData: () -> Unit = {},
    onNavigateAbout: () -> Unit = {},
) {
    val activeModel = resolveActiveModelLabel(settings, providers)
    val connectedPlugins = plugins.count { it.status == PluginStatus.CONNECTED && it.enabled }
    val toolCount = plugins.sumOf { it.enabledToolNames.size }

    Column(modifier = modifier.fillMaxSize()) {
        KujiraTopBar(title = "设置", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            SettingsSection(title = "通用") {
                SettingsRow(
                    title = "外观",
                    subtitle = "主题：${themeModeLabel(settings.themeMode)} · 字号 ${fontScaleLabel(settings.fontScale)}",
                    icon = Icons.Outlined.Palette,
                    onClick = onNavigateAppearance,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = "显示思考过程",
                    subtitle = "展示模型的 reasoning 内容",
                    icon = Icons.Outlined.Lightbulb,
                    checked = settings.showReasoning,
                    onCheckedChange = onShowReasoningChange,
                )
            }

            SettingsSection(title = "模型") {
                SettingsRow(
                    title = "默认模型",
                    subtitle = activeModel,
                    icon = Icons.Outlined.Memory,
                    onClick = onNavigateModels,
                )
                SettingsDivider()
                SettingsRow(
                    title = "生成参数",
                    subtitle = "temperature ${formatTemperature(settings.temperature)} · 最多 ${settings.maxTokens} tokens",
                    icon = Icons.Outlined.Tune,
                    onClick = onNavigateModels,
                )
                SettingsDivider()
                SettingsRow(
                    title = "用量与成本",
                    subtitle = "Token、缓存、费用统计",
                    icon = Icons.Outlined.BarChart,
                    onClick = onNavigateUsage,
                )
            }

            SettingsSection(title = "插件") {
                SettingsRow(
                    title = "插件管理",
                    subtitle = if (plugins.isEmpty()) {
                        "还没有添加 MCP 插件"
                    } else {
                        "$connectedPlugins/${plugins.size} 个插件已连接 · $toolCount 个工具"
                    },
                    icon = Icons.Outlined.Extension,
                    onClick = onNavigatePlugins,
                )
                if (settings.sentinelHostLabel != null) {
                    SettingsDivider()
                    SettingsValueRow(
                        title = "当前 SentinelX 主机",
                        value = settings.sentinelHostLabel,
                        onClick = onNavigatePlugins,
                    )
                }
                SettingsDivider()
                SettingsRow(
                    title = "工具审计日志",
                    subtitle = "查看 MCP 调用、失败与耗时",
                    icon = Icons.Outlined.History,
                    onClick = onNavigateAudit,
                )
            }

            SettingsSection(title = "数据") {
                SettingsRow(
                    title = "数据与存储",
                    subtitle = "导出、导入、清空本地数据",
                    icon = Icons.Outlined.Storage,
                    onClick = onNavigateData,
                )
            }

            SettingsSection(title = "关于") {
                SettingsRow(
                    title = "关于 Kujira",
                    subtitle = "版本 $appVersion",
                    icon = Icons.Outlined.Info,
                    onClick = onNavigateAbout,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Kujira · v$appVersion",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun fontScaleLabel(scale: Float): String = when {
    scale < 0.92f -> "小"
    scale < 1.08f -> "中"
    scale < 1.22f -> "大"
    else -> "特大"
}

internal fun formatTemperature(value: Double): String {
    val rounded = (value * 10).toInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) "${rounded.toInt()}" else rounded.toString()
}

@Preview(name = "Settings dark", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun SettingsDarkPreview() {
    KujiraTheme(darkTheme = true) {
        SettingsScreen(
            settings = previewSettings,
            providers = previewProviders,
            plugins = previewPlugins,
            appVersion = PREVIEW_APP_VERSION,
            onBack = {},
        )
    }
}

@Preview(name = "Settings light", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun SettingsLightPreview() {
    KujiraTheme(darkTheme = false) {
        SettingsScreen(
            settings = previewSettings.copy(themeMode = ThemeMode.LIGHT),
            providers = previewProviders,
            plugins = previewPlugins.take(2),
            appVersion = PREVIEW_APP_VERSION,
            onBack = {},
        )
    }
}
