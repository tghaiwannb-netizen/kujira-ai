package app.kujira.android.net.mcp

import android.content.Context
import android.util.Log
import app.kujira.android.core.SecureStore
import app.kujira.android.core.SettingsStore
import app.kujira.android.data.model.McpAuthMode
import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.PluginStatus
import app.kujira.android.data.model.ToolDescriptor
import app.kujira.android.net.sentinelx.SentinelXPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Connection manager for every MCP plugin.
 *
 * Owns the live [McpHttpClient] instances, keeps the discovered tool lists in a
 * [StateFlow] for the UI, and mirrors connection state back into
 * [SettingsStore] (`PluginConfig.status` / serverInfo / lastError) so it
 * survives process death.
 *
 * Wiring (Application scope):
 * ```
 * val manager = McpManager(settingsStore, secureStore)
 * // first paint: launch { manager.connectEnabled() }
 * ```
 */
class McpManager(
    private val settings: SettingsStore,
    private val secureStore: SecureStore,
    private val oauth: OAuthService = OAuthService(),
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val logger: (String) -> Unit = { message -> Log.d(TAG, message) },
    /** Resolves the OAuth server config for a plugin; only SentinelX ships one. */
    private val oauthConfigProvider: (PluginConfig) -> OAuthConfig? = { plugin ->
        if (SentinelXPreset.isSentinelX(plugin)) SentinelXPreset.oauthConfig(plugin.id) else null
    }
) {

    private val clients = mutableMapOf<String, McpHttpClient>()
    private val connectMutex = Mutex()

    private val _toolsByPlugin = MutableStateFlow<Map<String, List<ToolDescriptor>>>(emptyMap())

    /** Tools discovered per plugin id, for every currently connected plugin. */
    val toolsByPlugin: StateFlow<Map<String, List<ToolDescriptor>>> = _toolsByPlugin.asStateFlow()

    private val _busyPlugins = MutableStateFlow<Set<String>>(emptySet())

    /** Plugin ids with an in-flight connect / refresh. */
    val busyPlugins: StateFlow<Set<String>> = _busyPlugins.asStateFlow()

    // ------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------

    fun plugin(pluginId: String): PluginConfig? =
        settings.plugins.value.firstOrNull { it.id == pluginId }

    fun isConnected(pluginId: String): Boolean = clients.containsKey(pluginId)

    /** True when credentials exist (Bearer token or a refreshable OAuth token). */
    fun isAuthorized(plugin: PluginConfig): Boolean = when (plugin.authMode) {
        McpAuthMode.NONE -> true
        McpAuthMode.BEARER -> !McpSecrets.bearerToken(secureStore, plugin.id).isNullOrBlank()
        McpAuthMode.OAUTH -> oauthConfigProvider(plugin)?.let { oauth.isAuthorized(secureStore, it) } ?: false
    }

    /** Access-token expiry (epoch millis) for OAuth plugins, when known. */
    fun tokenExpiresAt(plugin: PluginConfig): Long? =
        oauthConfigProvider(plugin)?.let { oauth.expiresAt(secureStore, it) }

    /** Every connected plugin's tool list (cached). */
    fun connectedTools(): Map<String, List<ToolDescriptor>> =
        _toolsByPlugin.value.filterKeys { clients.containsKey(it) }

    // ------------------------------------------------------------------
    // connect / disconnect
    // ------------------------------------------------------------------

    /**
     * Ensures [plugin] has a live session and returns its tools. Re-uses the
     * existing session unless [force] is set.
     */
    suspend fun ensureConnected(plugin: PluginConfig, force: Boolean = false): List<ToolDescriptor> {
        if (!force) cachedTools(plugin.id)?.let { return it }
        return connectMutex.withLock {
            if (!force) cachedTools(plugin.id)?.let { return@withLock it }
            connectLocked(plugin)
        }
    }

    /** Connects every enabled plugin, skipping (and recording) failures. */
    suspend fun connectEnabled(): Map<String, List<ToolDescriptor>> {
        val result = mutableMapOf<String, List<ToolDescriptor>>()
        for (plugin in settings.plugins.value.filter { it.enabled }) {
            try {
                result[plugin.id] = ensureConnected(plugin)
            } catch (auth: McpAuthRequiredException) {
                logger("插件 ${plugin.name} 需要授权：${auth.message}")
            } catch (failure: Exception) {
                logger("插件 ${plugin.name} 连接失败：${failure.message}")
            }
        }
        return result
    }

    /**
     * Fresh handshake without registering the session; used by the
     * "test connection" button. Never mutates the persisted plugin state.
     */
    suspend fun testConnection(plugin: PluginConfig): McpConnection {
        val client = createClient(plugin)
        return try {
            val connection = client.connect()
            client.listTools()
            connection
        } finally {
            runCatching { client.close() }
        }
    }

    /** Re-lists tools on the live session (or reconnects when there is none). */
    suspend fun refreshTools(plugin: PluginConfig): List<ToolDescriptor> = connectMutex.withLock {
        val client = clients[plugin.id] ?: return@withLock connectLocked(plugin)
        val tools = try {
            client.listTools()
        } catch (auth: McpAuthRequiredException) {
            markStatus(plugin.id, PluginStatus.AUTH_REQUIRED, auth.message)
            disconnect(plugin.id)
            throw auth
        } catch (failure: McpException) {
            markStatus(plugin.id, PluginStatus.ERROR, failure.message)
            throw failure
        }
        _toolsByPlugin.value = _toolsByPlugin.value + (plugin.id to tools)
        markConnected(plugin.id, client, existingConnection(plugin, client), tools)
        tools
    }

    /** Runs the browser OAuth flow for [plugin], then connects. */
    suspend fun login(context: Context, plugin: PluginConfig): List<ToolDescriptor> {
        val config = oauthConfigProvider(plugin)
            ?: throw McpException("插件「${plugin.name}」没有配置 OAuth 授权服务器")
        markStatus(plugin.id, PluginStatus.CONNECTING, null)
        try {
            oauth.authorize(context, secureStore, config)
        } catch (auth: McpAuthRequiredException) {
            markStatus(plugin.id, PluginStatus.AUTH_REQUIRED, auth.message)
            throw auth
        } catch (failure: Exception) {
            markStatus(plugin.id, PluginStatus.ERROR, failure.message)
            throw failure
        }
        disconnect(plugin.id)
        return ensureConnected(plugin, force = true)
    }

    /** Drops stored credentials and the live session. */
    fun logout(plugin: PluginConfig) {
        val config = oauthConfigProvider(plugin)
        if (config != null) oauth.clear(secureStore, config)
        McpSecrets.putBearerToken(secureStore, plugin.id, null)
        disconnect(plugin.id)
        markStatus(plugin.id, PluginStatus.UNKNOWN, null)
    }

    fun disconnect(pluginId: String) {
        clients.remove(pluginId)?.let { client -> runCatching { client.close() } }
        _toolsByPlugin.value = _toolsByPlugin.value - pluginId
    }

    fun disconnectAll() {
        clients.keys.toList().forEach(::disconnect)
    }

    // ------------------------------------------------------------------
    // tool calls
    // ------------------------------------------------------------------

    /**
     * Executes one tool. Throws [McpAuthRequiredException] when the plugin needs
     * re-authorization and [McpException] for transport/protocol failures; the
     * [app.kujira.android.domain.McpToolExecutor] converts those into results.
     */
    suspend fun callTool(plugin: PluginConfig, toolName: String, argumentsJson: String): McpToolResult =
        callTool(plugin, toolName, argumentsJson, allowRetry = true)

    private suspend fun callTool(
        plugin: PluginConfig,
        toolName: String,
        argumentsJson: String,
        allowRetry: Boolean
    ): McpToolResult {
        var client = clients[plugin.id]
        if (client == null) {
            ensureConnected(plugin)
            client = clients[plugin.id]
        }
        if (client == null) throw McpException("插件「${plugin.name}」未连接")

        return try {
            client.callTool(toolName, argumentsJson)
        } catch (auth: McpAuthRequiredException) {
            markStatus(plugin.id, PluginStatus.AUTH_REQUIRED, auth.message)
            disconnect(plugin.id)
            throw auth
        } catch (failure: McpException) {
            // A dead session (server restarted, 404) is worth one silent reconnect.
            val recoverable = allowRetry && failure.message?.contains("HTTP 404") == true
            if (recoverable) {
                logger("MCP 会话失效，重连后重试 ${plugin.name}/$toolName")
                disconnect(plugin.id)
                ensureConnected(plugin, force = true)
                return callTool(plugin, toolName, argumentsJson, allowRetry = false)
            }
            markStatus(plugin.id, PluginStatus.ERROR, failure.message)
            throw failure
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private fun cachedTools(pluginId: String): List<ToolDescriptor>? =
        if (clients.containsKey(pluginId)) _toolsByPlugin.value[pluginId] else null

    private suspend fun connectLocked(plugin: PluginConfig): List<ToolDescriptor> {
        disconnect(plugin.id)
        markStatus(plugin.id, PluginStatus.CONNECTING, null)
        _busyPlugins.value = _busyPlugins.value + plugin.id

        val client = try {
            createClient(plugin)
        } catch (auth: McpAuthRequiredException) {
            _busyPlugins.value = _busyPlugins.value - plugin.id
            markStatus(plugin.id, PluginStatus.AUTH_REQUIRED, auth.message)
            throw auth
        } catch (failure: Exception) {
            _busyPlugins.value = _busyPlugins.value - plugin.id
            markStatus(plugin.id, PluginStatus.ERROR, failure.message)
            throw failure
        }

        try {
            val connection = client.connect()
            val tools = client.listTools()
            clients[plugin.id] = client
            _toolsByPlugin.value = _toolsByPlugin.value + (plugin.id to tools)
            markConnected(plugin.id, client, connection, tools)
            return tools
        } catch (failure: Throwable) {
            runCatching { client.close() }
            val authRequired = failure is McpAuthRequiredException
            markStatus(
                plugin.id,
                if (authRequired) PluginStatus.AUTH_REQUIRED else PluginStatus.ERROR,
                failure.message ?: failure.javaClass.simpleName
            )
            throw failure
        } finally {
            _busyPlugins.value = _busyPlugins.value - plugin.id
        }
    }

    private fun createClient(plugin: PluginConfig): McpHttpClient = McpHttpClient(
        endpoint = plugin.url,
        pluginId = plugin.id,
        pluginName = plugin.name,
        auth = authProviderFor(plugin),
        extraHeaders = plugin.headers,
        okHttpClient = okHttpClient,
        logger = logger
    )

    private fun authProviderFor(plugin: PluginConfig): McpAuthTokenProvider? = when (plugin.authMode) {
        McpAuthMode.NONE -> null
        McpAuthMode.BEARER -> {
            val token = McpSecrets.bearerToken(secureStore, plugin.id)
                ?: throw McpAuthRequiredException("插件「${plugin.name}」需要 Bearer Token，请在插件设置中填写")
            object : McpAuthTokenProvider {
                override suspend fun accessToken(): String = token
                override suspend fun refreshAccessToken(): String =
                    throw McpAuthRequiredException("Bearer Token 已失效，请到插件设置中更新")
            }
        }
        McpAuthMode.OAUTH -> {
            val config = oauthConfigProvider(plugin)
                ?: throw McpException("插件「${plugin.name}」的 OAuth 授权服务器未配置")
            if (!oauth.isAuthorized(secureStore, config)) {
                throw McpAuthRequiredException("请先登录 ${plugin.name}")
            }
            oauth.provider(secureStore, config)
        }
    }

    private fun markConnected(
        pluginId: String,
        client: McpHttpClient,
        connection: McpConnection?,
        tools: List<ToolDescriptor>
    ) {
        updatePlugin(pluginId) { current ->
            current.copy(
                status = PluginStatus.CONNECTED,
                serverName = connection?.serverName ?: current.serverName,
                serverVersion = connection?.serverVersion ?: current.serverVersion,
                protocolVersion = connection?.protocolVersion ?: current.protocolVersion,
                lastError = null,
                lastConnectedAt = System.currentTimeMillis(),
                enabledToolNames = if (current.enabledToolNames.isEmpty() && current.isSentinelX) {
                    SentinelXPreset.DEFAULT_ENABLED_TOOLS
                } else {
                    current.enabledToolNames
                }
            )
        }
        logger("插件 ${pluginId} 已连接：${client.endpointUrl}（${tools.size} 个工具）")
    }

    private fun existingConnection(plugin: PluginConfig, client: McpHttpClient): McpConnection = McpConnection(
        protocolVersion = plugin.protocolVersion ?: "",
        serverName = plugin.serverName,
        serverVersion = plugin.serverVersion,
        instructions = null,
        sessionId = client.sessionId
    )

    private fun markStatus(pluginId: String, status: PluginStatus, error: String?) {
        updatePlugin(pluginId) { current ->
            current.copy(status = status, lastError = error ?: if (status == PluginStatus.CONNECTED) null else current.lastError)
        }
    }

    private fun updatePlugin(pluginId: String, transform: (PluginConfig) -> PluginConfig) {
        val current = plugin(pluginId) ?: return
        runCatching { settings.upsertPlugin(transform(current)) }
            .onFailure { logger("保存插件状态失败：${it.message}") }
    }

    companion object {
        private const val TAG = "KujiraMcp"
    }
}
