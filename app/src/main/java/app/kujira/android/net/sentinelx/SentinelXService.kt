package app.kujira.android.net.sentinelx

import android.content.Context
import app.kujira.android.core.SettingsStore
import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.ToolDescriptor
import app.kujira.android.net.mcp.McpAuthRequiredException
import app.kujira.android.net.mcp.McpException
import app.kujira.android.net.mcp.McpManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SentinelX-specific operations on top of [McpManager].
 *
 * Keeps the host selection (`sentinelHostId` / `sentinelHostLabel` in
 * [AppSettings]) and the capability/host introspection calls in one place, so
 * the SentinelX screen stays a dumb, state-in / callbacks-out composable.
 */
class SentinelXService(
    private val manager: McpManager,
    private val settings: SettingsStore
) {

    data class Overview(
        val plugin: PluginConfig,
        val hosts: SentinelXPreset.HostsSnapshot,
        val capabilities: SentinelXPreset.CapabilitiesSummary?,
        val tools: List<ToolDescriptor>,
        val selectedHost: SentinelXPreset.HostEntry?
    )

    // ------------------------------------------------------------------
    // plugin / account
    // ------------------------------------------------------------------

    fun plugin(): PluginConfig? =
        settings.plugins.value.firstOrNull { SentinelXPreset.isSentinelX(it) }

    fun pluginOrThrow(): PluginConfig = plugin()
        ?: throw McpException("未找到内置的 SentinelX 插件")

    fun isAuthorized(): Boolean = plugin()?.let(manager::isAuthorized) ?: false

    fun tokenExpiresAt(): Long? = plugin()?.let(manager::tokenExpiresAt)

    suspend fun login(context: Context, plugin: PluginConfig = pluginOrThrow()): List<ToolDescriptor> =
        manager.login(context, plugin)

    fun logout(plugin: PluginConfig = pluginOrThrow()) = manager.logout(plugin)

    // ------------------------------------------------------------------
    // hosts
    // ------------------------------------------------------------------

    fun selectedHostId(): String? = settings.settings.value.sentinelHostId

    fun selectedHostLabel(): String? = settings.settings.value.sentinelHostLabel

    fun selectHost(host: SentinelXPreset.HostEntry?) {
        settings.updateSettings {
            it.copy(
                sentinelHostId = host?.id,
                sentinelHostLabel = host?.displayName
            )
        }
    }

    suspend fun hosts(plugin: PluginConfig = pluginOrThrow()): SentinelXPreset.HostsSnapshot {
        val result = manager.callTool(plugin, "sentinel_list_hosts", "{}")
        return SentinelXPreset.parseHostsSnapshot(result.output, result.structuredJson)
    }

    // ------------------------------------------------------------------
    // capabilities
    // ------------------------------------------------------------------

    suspend fun capabilities(
        plugin: PluginConfig = pluginOrThrow(),
        hostId: String? = selectedHostId()
    ): SentinelXPreset.CapabilitiesSummary? {
        val arguments = buildJsonObject {
            if (!hostId.isNullOrBlank()) put("host_id", hostId)
            put("detail", "summary")
        }
        val result = manager.callTool(plugin, "sentinel_capabilities", arguments.toString())
        return SentinelXPreset.parseCapabilities(result.output, result.structuredJson)
    }

    /**
     * One round-trip bundle for the SentinelX page: connect, list hosts, keep the
     * selected host valid (auto-select the first connected host), then read the
     * capability summary.
     */
    suspend fun loadOverview(plugin: PluginConfig = pluginOrThrow()): Overview {
        val tools = manager.ensureConnected(plugin)
        val hosts = hosts(plugin)

        val currentId = selectedHostId()
        val selected = hosts.hosts.firstOrNull { it.id == currentId }
            ?: hosts.connectedHosts.firstOrNull()
            ?: hosts.hosts.firstOrNull()
        if (selected != null && selected.id != currentId) selectHost(selected)

        if (selected == null) {
            return Overview(plugin, hosts, capabilities = null, tools = tools, selectedHost = null)
        }

        val capabilities = try {
            capabilities(plugin, selected.id)
        } catch (auth: McpAuthRequiredException) {
            throw auth
        } catch (failure: McpException) {
            null
        }
        return Overview(plugin, hosts, capabilities, tools, selected)
    }

    /** Plain-Chinese explanation for a hub error, or null when unknown. */
    fun explainError(message: String?): String? = SentinelXPreset.explainError(message)
}
