package app.kujira.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.PluginStatus
import app.kujira.android.ui.components.EmptyState
import app.kujira.android.ui.components.KujiraButton
import app.kujira.android.ui.components.KujiraButtonStyle
import app.kujira.android.ui.theme.KujiraTheme

/** MCP plugin list: status, enable switch, tool counts, add entry point. */
@Composable
fun PluginsSettingsScreen(
    plugins: List<PluginConfig>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPluginEnabledChange: ((PluginConfig, Boolean) -> Unit)? = null,
    onPluginClick: ((PluginConfig) -> Unit)? = null,
    onAddPlugin: (() -> Unit)? = null,
) {
    val connected = plugins.count { it.status == PluginStatus.CONNECTED && it.enabled }
    val toolCount = plugins.sumOf { it.enabledToolNames.size }

    SettingsPage(
        title = "插件",
        subtitle = "$connected/${plugins.size} 已连接 · $toolCount 个工具",
        onBack = onBack,
        modifier = modifier,
    ) {
        if (onAddPlugin != null) {
            KujiraButton(
                text = "添加插件",
                onClick = onAddPlugin,
                style = KujiraButtonStyle.TONAL,
                leadingIcon = Icons.Outlined.Add,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (plugins.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Extension,
                title = "还没有插件",
                subtitle = "添加一个 MCP server，让 Kujira 能调用外部工具",
            )
        } else {
            SettingsSection(title = "已安装") {
                plugins.forEachIndexed { index, plugin ->
                    if (index > 0) SettingsDivider(indent = false)
                    PluginRow(
                        plugin = plugin,
                        onEnabledChange = if (onPluginEnabledChange != null) {
                            { enabled -> onPluginEnabledChange(plugin, enabled) }
                        } else {
                            null
                        },
                        onClick = onPluginClick?.let { click -> { click(plugin) } },
                    )
                }
            }
        }

        SettingsSection(title = "说明") {
            SettingsRow(
                title = "工具执行策略",
                subtitle = "危险工具（exec / delete / restart 等）默认需要手动确认",
                icon = Icons.Outlined.Shield,
            )
        }
    }
}

@Composable
private fun PluginRow(
    plugin: PluginConfig,
    onEnabledChange: ((Boolean) -> Unit)?,
    onClick: (() -> Unit)?,
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = plugin.emoji.ifBlank { "🔌" },
                    fontSize = 18.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (plugin.builtin) {
                        Spacer(Modifier.width(6.dp))
                        PluginBadge(text = "内置")
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor(plugin.status)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = statusLabel(plugin.status) +
                            if (plugin.status == PluginStatus.CONNECTED) {
                                " · ${plugin.enabledToolNames.size} 个工具"
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onEnabledChange != null) {
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = plugin.enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PluginBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun statusColor(status: PluginStatus): Color = when (status) {
    PluginStatus.CONNECTED -> MaterialTheme.colorScheme.tertiary
    PluginStatus.CONNECTING -> MaterialTheme.colorScheme.primary
    PluginStatus.AUTH_REQUIRED -> MaterialTheme.colorScheme.primary
    PluginStatus.ERROR -> MaterialTheme.colorScheme.error
    PluginStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
}

private fun statusLabel(status: PluginStatus): String = when (status) {
    PluginStatus.CONNECTED -> "已连接"
    PluginStatus.CONNECTING -> "连接中"
    PluginStatus.AUTH_REQUIRED -> "需要授权"
    PluginStatus.ERROR -> "连接出错"
    PluginStatus.UNKNOWN -> "未连接"
}

@Preview(name = "Plugins dark", showBackground = true, widthDp = 400, heightDp = 800)
@Composable
private fun PluginsDarkPreview() {
    KujiraTheme(darkTheme = true) {
        PluginsSettingsScreen(
            plugins = previewPlugins,
            onBack = {},
            onAddPlugin = {},
        )
    }
}

@Preview(name = "Plugins light", showBackground = true, widthDp = 400, heightDp = 800)
@Composable
private fun PluginsLightPreview() {
    KujiraTheme(darkTheme = false) {
        PluginsSettingsScreen(
            plugins = previewPlugins.take(1),
            onBack = {},
            onAddPlugin = {},
        )
    }
}
