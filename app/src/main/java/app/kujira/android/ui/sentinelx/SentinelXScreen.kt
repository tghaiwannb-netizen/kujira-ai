package app.kujira.android.ui.sentinelx

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kujira.android.data.model.McpAuthMode
import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.PluginStatus
import app.kujira.android.net.sentinelx.SentinelXPreset
import app.kujira.android.ui.plugins.EmojiBadge
import app.kujira.android.ui.plugins.InfoRow
import app.kujira.android.ui.plugins.MessageBanner
import app.kujira.android.ui.plugins.SectionHeader
import app.kujira.android.ui.plugins.StatusChip
import app.kujira.android.ui.plugins.Tag
import app.kujira.android.ui.plugins.explainPluginError

/**
 * SentinelX home: login / re-authorize, host picker, capability overview and
 * one-tap prompts. Stateless — every piece of data and every action is passed
 * in, so the Lead can own the ViewModel/store side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentinelXScreen(
    plugin: PluginConfig?,
    hosts: List<SentinelXPreset.HostEntry>,
    selectedHostId: String?,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onRefresh: () -> Unit,
    onSelectHost: (SentinelXPreset.HostEntry?) -> Unit,
    onQuickPrompt: (String) -> Unit,
    onManageTools: () -> Unit,
    modifier: Modifier = Modifier,
    planSummary: String? = null,
    capabilities: SentinelXPreset.CapabilitiesSummary? = null,
    enabledToolCount: Int = 0,
    totalToolCount: Int = 0,
    busy: Boolean = false,
    error: String? = null,
    authorized: Boolean = false,
    tokenExpiresAt: Long? = null,
    statusMessage: String? = null
) {
    val selectedHost = hosts.firstOrNull { it.id == selectedHostId }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("SentinelX") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SentinelHeroCard(plugin, authorized, busy, tokenExpiresAt, onLogin, onLogout) }

            if (error != null) {
                item {
                    explainPluginError(plugin ?: samplePlugin(), error)?.let { explained ->
                        MessageBanner(explained)
                    }
                }
            }
            if (statusMessage != null) {
                item { MessageBanner(statusMessage, isError = false) }
            }

            item {
                HostCard(
                    hosts = hosts,
                    selectedHost = selectedHost,
                    planSummary = planSummary,
                    busy = busy,
                    onSelectHost = onSelectHost
                )
            }

            item { CapabilityCard(capabilities) }

            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("工具", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = if (totalToolCount == 0) {
                                    "连接后可以管理暴露给模型的工具"
                                } else {
                                    "已启用 $enabledToolCount / $totalToolCount 个工具"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = onManageTools) { Text("管理") }
                    }
                }
            }

            item {
                SectionHeader(
                    title = "快捷指令",
                    subtitle = "点一下填进输入框，让模型用 SentinelX 工具去做"
                )
            }

            val rows = SentinelXPreset.QUICK_PROMPTS.chunked(2)
            items(rows.size) { rowIndex ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows[rowIndex].forEach { prompt ->
                        QuickPromptCard(
                            prompt = prompt,
                            onClick = { onQuickPrompt(prompt.prompt) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rows[rowIndex].size == 1) Spacer(Modifier.weight(1f))
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "所有操作都受服务器上 /etc/sentinelx/config.yaml 的白名单约束；危险工具默认每次确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SentinelHeroCard(
    plugin: PluginConfig?,
    authorized: Boolean,
    busy: Boolean,
    tokenExpiresAt: Long?,
    onLogin: () -> Unit,
    onLogout: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiBadge(SentinelXPreset.EMOJI, size = 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = plugin?.name ?: SentinelXPreset.DISPLAY_NAME,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "用 AI 安全地操作你已注册的服务器",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusChip(plugin?.status ?: PluginStatus.UNKNOWN)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag("MCP 云端")
                if (authorized) Tag("已登录", color = MaterialTheme.colorScheme.tertiary)
                if (tokenExpiresAt != null) Tag("自动续期")
            }
            Spacer(Modifier.height(14.dp))
            if (!authorized) {
                Button(onClick = onLogin, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "正在打开浏览器…" else "登录 SentinelX")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "会打开浏览器用 Keycloak 授权，回调到本机 127.0.0.1:20573。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onLogin, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("重新授权")
                    }
                    TextButton(onClick = onLogout, enabled = !busy) { Text("退出登录") }
                }
            }
        }
    }
}

@Composable
private fun HostCard(
    hosts: List<SentinelXPreset.HostEntry>,
    selectedHost: SentinelXPreset.HostEntry?,
    planSummary: String?,
    busy: Boolean,
    onSelectHost: (SentinelXPreset.HostEntry?) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader(title = "当前主机", subtitle = planSummary)
            Spacer(Modifier.height(8.dp))
            if (hosts.isEmpty()) {
                Text(
                    text = "还没有发现主机。到 sentinelx.app 注册服务器并运行 SentinelX agent，然后回来刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Box {
                    OutlinedButton(
                        onClick = { menuOpen = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = selectedHost?.displayName ?: "选择当前主机",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        hosts.forEach { host ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(host.displayName, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = host.hostname ?: host.id,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    menuOpen = false
                                    onSelectHost(host)
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "共 ${hosts.size} 台主机；之后所有工具调用会自动带上当前主机的 host_id。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CapabilityCard(capabilities: SentinelXPreset.CapabilitiesSummary?) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader(
                title = "能力总览",
                subtitle = capabilities?.let { "来自 sentinel_capabilities summary" }
            )
            Spacer(Modifier.height(8.dp))
            if (capabilities == null) {
                Text(
                    text = "登录并连接后会显示主机的操作能力、命令白名单和文件权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            InfoRow("主机", capabilities.hostDisplayName)
            capabilities.version?.let { InfoRow("Agent", it) }
            if (capabilities.kernel != null || capabilities.arch != null) {
                InfoRow(
                    "系统",
                    listOfNotNull(capabilities.kernel, capabilities.arch).joinToString(" · ")
                )
            }
            if (capabilities.execTimeoutDefault != null || capabilities.execTimeoutMax != null) {
                InfoRow(
                    "命令超时",
                    "${capabilities.execTimeoutDefault ?: "?"}s（最大 ${capabilities.execTimeoutMax ?: "?"}s）"
                )
            }
            if (capabilities.policySummary.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("策略", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                CapabilityPolicyChips(capabilities.policySummary)
            }
            if (capabilities.opsSupported.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "支持 ${capabilities.opsSupported.size} 项操作",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = capabilities.opsSupported.joinToString(" · ") { SentinelXPreset.opLabel(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CapabilityPolicyChips(policies: Map<String, Int>) {
    val entries = policies.entries.toList()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (key, value) ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(
                                text = policyLabel(key),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(value.toString(), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QuickPromptCard(
    prompt: SentinelXPreset.QuickPrompt,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp)
        ) {
            Text(prompt.emoji, fontSize = 22.sp)
            Spacer(Modifier.height(8.dp))
            Text(prompt.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                text = prompt.prompt,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun policyLabel(key: String): String = when (key) {
    "allowed_commands" -> "允许命令"
    "services" -> "服务"
    "locations" -> "上传位置"
    "playbooks" -> "操作手册"
    "file_ops_paths" -> "文件路径"
    "writable_paths" -> "可写路径"
    "trusted_fetch_hosts" -> "可信下载源"
    else -> key
}

/** Placeholder used only to route an error string through the SentinelX translator. */
private fun samplePlugin(): PluginConfig = PluginConfig(
    id = SentinelXPreset.PLUGIN_ID,
    name = SentinelXPreset.DISPLAY_NAME,
    emoji = SentinelXPreset.EMOJI,
    url = SentinelXPreset.HUB_URL,
    authMode = McpAuthMode.OAUTH,
    builtin = true,
    preset = SentinelXPreset.PRESET
)
