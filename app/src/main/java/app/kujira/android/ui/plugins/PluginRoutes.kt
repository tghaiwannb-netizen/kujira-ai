package app.kujira.android.ui.plugins

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kujira.android.data.model.PluginConfig
import app.kujira.android.di.AppContainer
import app.kujira.android.net.mcp.McpSecrets
import app.kujira.android.net.mcp.ToolPolicies
import app.kujira.android.net.sentinelx.SentinelXPreset
import app.kujira.android.net.sentinelx.SentinelXService
import app.kujira.android.ui.sentinelx.SentinelXScreen
import kotlinx.coroutines.launch

/*
 * Plugin + SentinelX routes: glue between the parameter-driven plugin screens
 * and the MCP manager / settings stores.
 */

@Composable
fun PluginsRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPlugin: (String) -> Unit,
    onOpenSentinelX: () -> Unit
) {
    val plugins by container.settingsStore.plugins.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showImport by remember { mutableStateOf(false) }

    PluginListScreen(
        plugins = plugins,
        onBack = onBack,
        onAdd = { onOpenPlugin("new") },
        onImport = { showImport = true },
        onOpen = { plugin ->
            if (SentinelXPreset.isSentinelX(plugin)) onOpenSentinelX() else onOpenPlugin(plugin.id)
        },
        onToggleEnabled = { plugin, enabled ->
            container.settingsStore.upsertPlugin(plugin.copy(enabled = enabled))
            if (enabled) {
                scope.launch { runCatching { container.mcpManager.ensureConnected(plugin) } }
            } else {
                container.mcpManager.disconnect(plugin.id)
            }
        }
    )

    if (showImport) {
        ImportPluginsDialog(
            onDismiss = { showImport = false },
            onImport = { imported ->
                imported.forEach { container.settingsStore.upsertPlugin(it) }
                showImport = false
                scope.launch { container.mcpManager.connectEnabled() }
            }
        )
    }
}

@Composable
fun PluginEditRoute(
    container: AppContainer,
    pluginId: String?,
    onBack: () -> Unit
) {
    val plugins by container.settingsStore.plugins.collectAsStateWithLifecycle()
    val existing = remember(pluginId, plugins) {
        pluginId?.let { id -> plugins.firstOrNull { it.id == id } }
    }
    val initialToken = remember(existing?.id) {
        existing?.let { McpSecrets.bearerToken(container.secureStore, it.id) }
    }
    PluginEditScreen(
        initial = existing,
        onBack = onBack,
        initialBearerToken = initialToken,
        onSave = { config, bearerToken ->
            container.settingsStore.upsertPlugin(config)
            if (!bearerToken.isNullOrBlank()) {
                McpSecrets.putBearerToken(container.secureStore, config.id, bearerToken)
            }
            onBack()
        }
    )
}

@Composable
fun PluginDetailRoute(
    container: AppContainer,
    pluginId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenSentinelX: () -> Unit
) {
    val plugins by container.settingsStore.plugins.collectAsStateWithLifecycle()
    val toolsByPlugin by container.mcpManager.toolsByPlugin.collectAsStateWithLifecycle()
    val busyPlugins by container.mcpManager.busyPlugins.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val plugin = plugins.firstOrNull { it.id == pluginId }
    val tools = toolsByPlugin[pluginId].orEmpty()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    if (plugin == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    LaunchRefresh(plugin, container)

    PluginDetailScreen(
        plugin = plugin,
        tools = tools,
        busy = busyPlugins.contains(pluginId),
        onBack = onBack,
        onEdit = onEdit,
        onToggleEnabled = { enabled ->
            container.settingsStore.upsertPlugin(plugin.copy(enabled = enabled))
            if (enabled) {
                scope.launch { runCatching { container.mcpManager.ensureConnected(plugin) } }
            } else {
                container.mcpManager.disconnect(plugin.id)
            }
        },
        onTestConnection = {
            scope.launch {
                runCatching { container.mcpManager.testConnection(plugin) }
                    .onSuccess { connection ->
                        statusIsError = false
                        statusMessage = "连接成功：${connection.serverName ?: plugin.name} ${connection.serverVersion.orEmpty()}"
                    }
                    .onFailure { failure ->
                        statusIsError = true
                        statusMessage = failure.message ?: "连接失败"
                    }
            }
        },
        onRefreshTools = {
            scope.launch {
                runCatching { container.mcpManager.refreshTools(plugin) }
                    .onSuccess { statusIsError = false; statusMessage = "已刷新 ${it.size} 个工具" }
                    .onFailure { statusIsError = true; statusMessage = it.message ?: "刷新失败" }
            }
        },
        onLogin = {
            scope.launch {
                runCatching { container.mcpManager.login(context, plugin) }
                    .onSuccess { statusIsError = false; statusMessage = "授权成功" }
                    .onFailure { statusIsError = true; statusMessage = it.message ?: "授权失败" }
            }
        },
        onLogout = { container.mcpManager.logout(plugin) },
        onSetToolPolicy = { tool, policy ->
            container.settingsStore.upsertPlugin(ToolPolicies.withPolicy(plugin, tool, policy))
        },
        onEnableTools = { names ->
            container.settingsStore.upsertPlugin(ToolPolicies.enable(plugin, tools, names.toSet()))
        },
        onDisableTools = { names ->
            container.settingsStore.upsertPlugin(ToolPolicies.disable(plugin, names.toSet()))
        },
        authSummary = if (container.mcpManager.isAuthorized(plugin)) "已授权" else "未授权",
        statusMessage = statusMessage,
        statusIsError = statusIsError,
        onDelete = if (plugin.builtin) null else {
            {
                container.settingsStore.deletePlugin(plugin.id)
                Toast.makeText(context, "已删除插件", Toast.LENGTH_SHORT).show()
                onBack()
            }
        },
        onOpenSentinelX = if (SentinelXPreset.isSentinelX(plugin)) onOpenSentinelX else null
    )
}

@Composable
private fun LaunchRefresh(plugin: PluginConfig?, container: AppContainer) {
    LaunchedEffect(plugin?.id) {
        val target = plugin ?: return@LaunchedEffect
        if (!target.enabled) return@LaunchedEffect
        runCatching { container.mcpManager.ensureConnected(target) }
    }
}

@Composable
fun SentinelXRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onManageTools: (String) -> Unit,
    onQuickPrompt: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle()
    val plugins by container.settingsStore.plugins.collectAsStateWithLifecycle()
    val service = container.sentinelXService

    var overview by remember { mutableStateOf<SentinelXService.Overview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            busy = true
            error = null
            try {
                overview = service.loadOverview()
            } catch (auth: app.kujira.android.net.mcp.McpAuthRequiredException) {
                // Not logged in yet — the screen expresses that via authorized=false.
            } catch (failure: Exception) {
                error = failure.message
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (service.isAuthorized()) refresh()
    }

    val plugin = plugins.firstOrNull { SentinelXPreset.isSentinelX(it) }
    val tools = overview?.tools.orEmpty()

    SentinelXScreen(
        plugin = plugin ?: service.plugin(),
        hosts = overview?.hosts?.hosts.orEmpty(),
        selectedHostId = settings.sentinelHostId,
        planSummary = overview?.hosts?.planSummary,
        capabilities = overview?.capabilities,
        enabledToolCount = plugin?.let { ToolPolicies.enabledCount(it, tools) } ?: 0,
        totalToolCount = tools.size,
        authorized = service.isAuthorized(),
        tokenExpiresAt = service.tokenExpiresAt(),
        busy = busy,
        error = error,
        statusMessage = status,
        onBack = onBack,
        onLogin = {
            scope.launch {
                busy = true
                error = null
                try {
                    service.login(context)
                    refresh()
                    status = "SentinelX 已连接"
                } catch (failure: Exception) {
                    error = failure.message ?: "登录失败"
                } finally {
                    busy = false
                }
            }
        },
        onLogout = {
            runCatching { service.logout() }
            overview = null
            status = null
        },
        onRefresh = { refresh() },
        onSelectHost = { host -> service.selectHost(host) },
        onQuickPrompt = onQuickPrompt,
        onManageTools = {
            plugin?.let { onManageTools(it.id) }
        }
    )
}
