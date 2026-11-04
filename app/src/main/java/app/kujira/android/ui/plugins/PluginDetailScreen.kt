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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kujira.android.data.model.McpAuthMode
import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.ToolDescriptor
import app.kujira.android.data.model.ToolPolicy
import app.kujira.android.net.mcp.ToolPolicies
import app.kujira.android.net.mcp.ToolSchema
import app.kujira.android.net.sentinelx.SentinelXPreset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plugin detail: connection state, server info, tool list with per-tool policy.
 *
 * `busy` disables the connection actions while a connect/refresh is running.
 * Tool policy mutations go back through the callbacks so the caller can route
 * them into `ToolPolicies.withPolicy` / `ToolPolicies.enable` / `disable`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailScreen(
    plugin: PluginConfig,
    tools: List<ToolDescriptor>,
    busy: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
    onRefreshTools: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onSetToolPolicy: (ToolDescriptor, ToolPolicy) -> Unit,
    onEnableTools: (List<String>) -> Unit,
    onDisableTools: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    authSummary: String? = null,
    statusMessage: String? = null,
    statusIsError: Boolean = true,
    onDelete: (() -> Unit)? = null,
    onOpenSentinelX: (() -> Unit)? = null
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val enabledCount = ToolPolicies.enabledCount(plugin, tools)
    val groups = if (plugin.isSentinelX) {
        SentinelXPreset.groupTools(tools)
    } else if (tools.isEmpty()) {
        emptyList()
    } else {
        listOf(SentinelXPreset.GroupedTools(SentinelXPreset.OTHER_GROUP.copy(title = "工具", emoji = "🧰"), tools))
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("插件详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑")
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
            item { HeaderCard(plugin, authSummary, onToggleEnabled) }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onTestConnection,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("测试连接") }
                        OutlinedButton(
                            onClick = onRefreshTools,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (busy) "刷新中…" else "刷新工具")
                        }
                    }
                    if (plugin.authMode == McpAuthMode.OAUTH) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onLogin, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text(if (authSummary == null) "登录 / 授权" else "重新授权")
                            }
                            TextButton(onClick = onLogout, enabled = !busy) { Text("退出登录") }
                        }
                    }
                    if (onOpenSentinelX != null) {
                        OutlinedButton(onClick = onOpenSentinelX, modifier = Modifier.fillMaxWidth()) {
                            Text("打开 SentinelX 页面")
                        }
                    }
                    statusMessage?.let { MessageBanner(it, isError = statusIsError) }
                    val explained = explainPluginError(plugin, plugin.lastError)
                    if (explained != null && plugin.status != app.kujira.android.data.model.PluginStatus.CONNECTED) {
                        MessageBanner(explained)
                    }
                }
            }

            item {
                SectionHeader(
                    title = "工具",
                    subtitle = if (tools.isEmpty()) {
                        "还没有发现工具"
                    } else {
                        "已启用 $enabledCount / ${tools.size} 个 · 只启用需要的工具可以节省上下文"
                    },
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { onEnableTools(tools.map { it.name }) },
                                enabled = tools.isNotEmpty() && !busy
                            ) { Text("全部启用") }
                            TextButton(
                                onClick = { onDisableTools(tools.map { it.name }) },
                                enabled = tools.isNotEmpty() && !busy
                            ) { Text("全部禁用") }
                        }
                    }
                )
            }

            if (tools.isEmpty()) {
                item {
                    EmptyHint(
                        emoji = "🧰",
                        title = "还没有工具",
                        message = "点上方「刷新工具」连接服务器并拉取工具列表；如果失败，先「测试连接」看看错误。"
                    )
                }
            }

            groups.forEach { grouped ->
                item(key = "group:${grouped.group.key}") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${grouped.group.emoji} ${grouped.group.title}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = grouped.tools.size.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { onEnableTools(grouped.tools.map { it.name }) },
                            enabled = !busy
                        ) { Text("全开", style = MaterialTheme.typography.labelMedium) }
                        TextButton(
                            onClick = { onDisableTools(grouped.tools.map { it.name }) },
                            enabled = !busy
                        ) { Text("全关", style = MaterialTheme.typography.labelMedium) }
                    }
                }
                items(grouped.tools, key = { "tool:${it.name}" }) { tool ->
                    ToolRow(
                        plugin = plugin,
                        tool = tool,
                        onSetPolicy = { policy -> onSetToolPolicy(tool, policy) }
                    )
                }
            }

            item {
                if (onDelete != null && !plugin.builtin) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("删除插件", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showDeleteConfirm && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除插件？") },
            text = { Text("将移除「${plugin.name}」及其工具配置，本地审计记录会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun HeaderCard(
    plugin: PluginConfig,
    authSummary: String?,
    onToggleEnabled: (Boolean) -> Unit
) {
    Card(
        shape = PluginCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiBadge(plugin.emoji, size = 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = plugin.name,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (plugin.builtin) {
                            Spacer(Modifier.width(6.dp))
                            Tag("内置")
                        }
                    }
                    Text(
                        text = plugin.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = plugin.enabled, onCheckedChange = onToggleEnabled)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(plugin.status)
                plugin.protocolVersion?.takeIf { it.isNotBlank() }?.let { Tag("MCP $it") }
            }
            Spacer(Modifier.height(8.dp))
            plugin.serverName?.takeIf { it.isNotBlank() }?.let { name ->
                val version = plugin.serverVersion?.takeIf { it.isNotBlank() }
                InfoRow("服务器", if (version == null) name else "$name $version")
            }
            plugin.lastConnectedAt?.let { InfoRow("上次连接", formatTimestamp(it)) }
            authSummary?.let { InfoRow("授权", it) }
        }
    }
}

@Composable
private fun ToolRow(
    plugin: PluginConfig,
    tool: ToolDescriptor,
    onSetPolicy: (ToolPolicy) -> Unit
) {
    var expanded by remember(tool.name) { mutableStateOf(false) }
    val policy = ToolPolicies.effective(plugin, tool)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = tool.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Medium
                    )
                    if (tool.description.isNotBlank()) {
                        Text(
                            text = tool.description.lineSequence().firstOrNull().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = ToolSchema.summary(tool.inputSchemaJson),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            PolicyChips(selected = policy, onSelect = onSetPolicy)
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (tool.description.isNotBlank()) {
                    Text(
                        text = tool.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text("参数 schema", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                MonoText(ToolSchema.pretty(tool.inputSchemaJson))
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
