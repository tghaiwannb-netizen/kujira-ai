package app.kujira.android.ui.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kujira.android.data.model.PluginConfig

/**
 * Plugin list — the MCP server roster.
 *
 * Stateless: `plugins` is the current `SettingsStore.plugins` value, all
 * mutations go back through the callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginListScreen(
    plugins: List<PluginConfig>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onOpen: (PluginConfig) -> Unit,
    onToggleEnabled: (PluginConfig, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("插件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onImport) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "粘贴导入")
                    }
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Filled.Add, contentDescription = "添加插件")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "MCP 服务器",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "插件是 Streamable HTTP 上的 MCP server，连接后它提供的工具会出现在对话里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (plugins.isEmpty()) {
                item {
                    EmptyHint(
                        emoji = "🔌",
                        title = "还没有插件",
                        message = "点右上角「+」手动添加，或粘贴 Claude Desktop 的 mcpServers JSON 导入。"
                    )
                }
            }
            items(plugins, key = { it.id }) { plugin ->
                PluginRow(
                    plugin = plugin,
                    onClick = { onOpen(plugin) },
                    onToggleEnabled = { enabled -> onToggleEnabled(plugin, enabled) }
                )
            }
            item {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "提示：工具默认按危险程度划分「自动 / 确认 / 禁用」，可以在插件详情里逐个调整。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PluginRow(
    plugin: PluginConfig,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    Card(
        shape = PluginCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EmojiBadge(plugin.emoji)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (plugin.builtin) {
                        Spacer(Modifier.width(6.dp))
                        Tag("内置")
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = plugin.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusChip(plugin.status)
                    if (plugin.enabledToolNames.isNotEmpty()) {
                        Text(
                            text = "${plugin.enabledToolNames.size} 个工具启用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                val explained = explainPluginError(plugin, plugin.lastError)
                if (explained != null && plugin.status != app.kujira.android.data.model.PluginStatus.CONNECTED) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = explained,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = plugin.enabled, onCheckedChange = onToggleEnabled)
        }
    }
}
