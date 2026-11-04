package app.kujira.android.core

import android.content.Context
import app.kujira.android.data.model.AppSettings
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.data.model.ModelInfo
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
        prefs.edit()
            .putString(KEY_PROVIDERS, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ProviderConfig.serializer()), list))
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
        val raw = prefs.getString(KEY_PROVIDERS, null) ?: return defaultProviders()
        return runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ProviderConfig.serializer()), raw)
        }.getOrElse { defaultProviders() }
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

    fun defaultProviders(): List<ProviderConfig> = listOf(
        ProviderConfig(
            id = "deepseek",
            kind = ProviderKind.DEEPSEEK,
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            builtin = true,
            models = listOf(
                ModelInfo("deepseek-chat", "DeepSeek Chat"),
                ModelInfo("deepseek-reasoner", "DeepSeek Reasoner")
            ),
            defaultModelId = "deepseek-chat"
        ),
        ProviderConfig(
            id = "openai",
            kind = ProviderKind.OPENAI,
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            builtin = true,
            models = listOf(
                ModelInfo("gpt-5.1", "GPT-5.1", supportsVision = true),
                ModelInfo("gpt-5.1-mini", "GPT-5.1 mini", supportsVision = true),
                ModelInfo("gpt-4.1", "GPT-4.1", supportsVision = true)
            ),
            defaultModelId = "gpt-5.1"
        ),
        ProviderConfig(
            id = "anthropic",
            kind = ProviderKind.ANTHROPIC,
            name = "Anthropic",
            baseUrl = "https://api.anthropic.com/v1",
            builtin = true,
            models = listOf(
                ModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6", supportsVision = true),
                ModelInfo("claude-opus-4-6", "Claude Opus 4.6", supportsVision = true),
                ModelInfo("claude-haiku-4-5", "Claude Haiku 4.5", supportsVision = true)
            ),
            defaultModelId = "claude-sonnet-4-6"
        ),
        ProviderConfig(
            id = "openrouter",
            kind = ProviderKind.OPENROUTER,
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            builtin = true,
            models = listOf(
                ModelInfo("anthropic/claude-sonnet-4.6", "Claude Sonnet 4.6"),
                ModelInfo("deepseek/deepseek-chat", "DeepSeek Chat"),
                ModelInfo("google/gemini-2.5-pro", "Gemini 2.5 Pro")
            ),
            defaultModelId = "anthropic/claude-sonnet-4.6"
        )
    )

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
    }
}
