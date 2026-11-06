package app.kujira.android.ui.settings

import app.kujira.android.data.model.AppSettings
import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.PluginConfig
import app.kujira.android.data.model.PluginStatus
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.data.model.ThemeMode

/*
 * Fake settings data for @Preview only. Runtime screens receive real data
 * through parameters.
 */

internal const val PREVIEW_APP_VERSION = "1.2.2"

internal val previewProviders: List<ProviderConfig> = listOf(
    ProviderConfig(
        id = "provider-deepseek",
        kind = ProviderKind.DEEPSEEK,
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        models = listOf(
            ModelInfo(id = "deepseek-chat", displayName = "DeepSeek V3.2", contextWindow = 128_000),
            ModelInfo(id = "deepseek-reasoner", displayName = "DeepSeek R1", contextWindow = 64_000),
        ),
        defaultModelId = "deepseek-chat",
        enabled = true,
        builtin = true,
    ),
    ProviderConfig(
        id = "provider-anthropic",
        kind = ProviderKind.ANTHROPIC,
        name = "Anthropic",
        baseUrl = "https://api.anthropic.com",
        models = listOf(
            ModelInfo(id = "claude-sonnet-4-5", displayName = "Claude Sonnet 4.5", contextWindow = 200_000),
        ),
        defaultModelId = "claude-sonnet-4-5",
        enabled = true,
        builtin = true,
    ),
    ProviderConfig(
        id = "provider-openai",
        kind = ProviderKind.OPENAI,
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        models = listOf(ModelInfo(id = "gpt-5.1", displayName = "GPT-5.1")),
        defaultModelId = "gpt-5.1",
        enabled = false,
        builtin = true,
    ),
)

internal val previewSettings: AppSettings = AppSettings(
    themeMode = ThemeMode.DARK,
    fontScale = 1.0f,
    temperature = 0.7,
    maxTokens = 4096,
    topP = 1.0,
    systemPrompt = "你叫 Kujira，是一只聪明但有点懒的鲸鱼。回答要直接、简洁、有信息量。",
    activeProviderId = "provider-deepseek",
    activeModelId = "deepseek-chat",
    pluginsEnabled = true,
    toolLoopLimit = 12,
    showReasoning = true,
    sentinelHostId = "srv-01",
    sentinelHostLabel = "prod-srv-01",
)

internal val previewPlugins: List<PluginConfig> = listOf(
    PluginConfig(
        id = "plugin-sentinelx",
        name = "SentinelX",
        emoji = "🐋",
        url = "https://mcp.sentinelx.app/mcp/mcp",
        preset = "sentinelx",
        builtin = true,
        enabled = true,
        status = PluginStatus.CONNECTED,
        serverName = "sentinelx",
        serverVersion = "0.19.0",
        protocolVersion = "2025-06-18",
        enabledToolNames = List(95) { "tool-$it" },
    ),
    PluginConfig(
        id = "plugin-cloudflare",
        name = "Cloudflare",
        emoji = "☁️",
        url = "https://mcp.cloudflare.example/mcp",
        enabled = true,
        status = PluginStatus.CONNECTED,
        enabledToolNames = List(12) { "tool-$it" },
    ),
    PluginConfig(
        id = "plugin-local",
        name = "本地文件",
        emoji = "📁",
        url = "http://192.168.1.10:8765/mcp",
        enabled = false,
        status = PluginStatus.ERROR,
        lastError = "connection refused",
        enabledToolNames = emptyList(),
    ),
)

/** "DeepSeek · DeepSeek V3.2" style label for the active provider/model. */
internal fun resolveActiveModelLabel(
    settings: AppSettings,
    providers: List<ProviderConfig>,
): String {
    val provider = providers.firstOrNull { it.id == settings.activeProviderId }
        ?: providers.firstOrNull { it.enabled }
        ?: return "未选择模型"
    val model = provider.models.firstOrNull { it.id == settings.activeModelId }
        ?: provider.defaultModel()
        ?: return provider.name
    return "${provider.name} · ${model.displayName}"
}

internal fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.DARK -> "深色"
    ThemeMode.LIGHT -> "浅色"
}
