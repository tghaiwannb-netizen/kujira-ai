package app.kujira.android.core

import android.content.Context
import app.kujira.android.data.model.AppSettings
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.PluginConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Persistence for app settings, providers and plugins.
 *
 * Deliberately SharedPreferences-backed with JSON blobs: the data is small,
 * changes are infrequent, and this keeps the storage layer dependency-free.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _providers = MutableStateFlow(loadProviders())
    val providers: StateFlow<List<ProviderConfig>> = _providers.asStateFlow()

    private val _plugins = MutableStateFlow(loadPlugins())
    val plugins: StateFlow<List<PluginConfig>> = _plugins.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // -- settings ------------------------------------------------------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(AppSettings.serializer(), next)).apply()
    }

    private fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return defaultSettings()
        return runCatching { json.decodeFromString(AppSettings.serializer(), raw) }.getOrElse { defaultSettings() }
    }

    // -- providers -----------------------------------------------------------

    fun saveProviders(list: List<ProviderConfig>) {
        _providers.value = list
        val seeded = prefs.getStringSet(KEY_SEEDED_PROVIDERS, emptySet()).orEmpty().toMutableSet()
        seeded += list.filter { it.builtin }.map { it.id }
        prefs.edit()
            .putString(KEY_PROVIDERS, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ProviderConfig.serializer()), list))
            .putStringSet(KEY_SEEDED_PROVIDERS, seeded)
            .apply()
    }

    fun upsertProvider(provider: ProviderConfig) {
        val next = _providers.value.toMutableList()
        val index = next.indexOfFirst { it.id == provider.id }
        if (index >= 0) next[index] = provider else next.add(provider)
        saveProviders(next)
    }

    fun deleteProvider(id: String) = saveProviders(_providers.value.filterNot { it.id == id })

    private fun loadProviders(): List<ProviderConfig> {
        val raw = prefs.getString(KEY_PROVIDERS, null)
        if (raw == null) {
            val defaults = defaultProviders()
            prefs.edit()
                .putString(
                    KEY_PROVIDERS,
                    json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(ProviderConfig.serializer()),
                        defaults
                    )
                )
                .putStringSet(KEY_SEEDED_PROVIDERS, ProviderPresets.core.map { it.id }.toSet())
                .apply()
            return defaults
        }

        val stored = runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ProviderConfig.serializer()), raw)
        }.getOrElse { defaultProviders() }

        // Upgrade path: add newly introduced built-ins exactly once. Once a
        // preset id is in the seeded set, deleting the provider stays deleted.
        val seeded = prefs.getStringSet(KEY_SEEDED_PROVIDERS, null)?.toMutableSet()
            ?: stored.filter { it.builtin }.map { it.id }.toMutableSet()
        val existingIds = stored.map { it.id }.toSet()
        val additions = ProviderPresets.core
            .filter { it.id !in existingIds && it.id !in seeded }
            .map { it.toProvider() }
        val merged = stored + additions

        val nextSeeded = seeded.apply { ProviderPresets.core.forEach { add(it.id) } }
        if (additions.isNotEmpty() || prefs.getStringSet(KEY_SEEDED_PROVIDERS, null) == null) {
            prefs.edit()
                .putString(
                    KEY_PROVIDERS,
                    json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(ProviderConfig.serializer()),
                        merged
                    )
                )
                .putStringSet(KEY_SEEDED_PROVIDERS, nextSeeded)
                .apply()
        }
        return merged
    }

    // -- plugins -------------------------------------------------------------

    fun savePlugins(list: List<PluginConfig>) {
        _plugins.value = list
        prefs.edit()
            .putString(KEY_PLUGINS, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PluginConfig.serializer()), list))
            .apply()
    }

    fun upsertPlugin(plugin: PluginConfig) {
        val next = _plugins.value.toMutableList()
        val index = next.indexOfFirst { it.id == plugin.id }
        if (index >= 0) next[index] = plugin else next.add(plugin)
        savePlugins(next)
        if (plugin.builtin && plugin.preset != null) {
            prefs.edit().putString(KEY_PRESET_SEEDED, "1").apply()
        }
    }

    fun deletePlugin(id: String) = savePlugins(_plugins.value.filterNot { it.id == id })

    private fun loadPlugins(): List<PluginConfig> {
        val raw = prefs.getString(KEY_PLUGINS, null) ?: return builtinPlugins()
        val stored = runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PluginConfig.serializer()), raw)
        }.getOrElse { builtinPlugins() }
        // Always ensure built-in presets exist.
        val merged = stored.toMutableList()
        for (builtin in builtinPlugins()) {
            if (merged.none { it.preset == builtin.preset }) merged.add(0, builtin)
        }
        return merged
    }

    // -- defaults ------------------------------------------------------------

    fun defaultSettings(): AppSettings {
        val providers = _providers.value.ifEmpty { defaultProviders() }
        val first = providers.firstOrNull { it.enabled }
        return AppSettings(
            activeProviderId = first?.id,
            activeModelId = first?.defaultModel()?.id
        )
    }

    fun defaultProviders(): List<ProviderConfig> = ProviderPresets.core.map { it.toProvider() }

    fun builtinPlugins(): List<PluginConfig> = listOf(
        PluginConfig(
            id = "sentinelx",
            name = "SentinelX",
            emoji = "🛰️",
            url = "https://mcp.sentinelx.app/mcp/mcp",
            authMode = app.kujira.android.data.model.McpAuthMode.OAUTH,
            builtin = true,
            preset = "sentinelx",
            // Safe read-only tools enabled by default; the rest are opt-in
            // because exposing 95 tools would blow up the context window.
            enabledToolNames = listOf(
                "sentinel_list_hosts",
                "sentinel_state",
                "sentinel_capabilities",
                "sentinel_ping",
                "sentinel_exec",
                "sentinel_read",
                "sentinel_list",
                "sentinel_service_status",
                "sentinel_git_diff",
                "sentinel_project_snapshot",
                "cloudflare_dns_list_zones",
                "cloudflare_dns_list_records"
            )
        )
    )

    private companion object {
        const val PREFS = "kujira_settings_v1"
        const val KEY_SETTINGS = "settings_json"
        const val KEY_PROVIDERS = "providers_json"
        const val KEY_PLUGINS = "plugins_json"
        const val KEY_PRESET_SEEDED = "preset_seeded"
        const val KEY_SEEDED_PROVIDERS = "seeded_provider_presets"
    }
}
