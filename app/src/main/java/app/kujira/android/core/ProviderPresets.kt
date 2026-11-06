package app.kujira.android.core

import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind

/**
 * One entry in the provider catalogue.
 *
 * Presets are the Android equivalent of the provider templates used by
 * RikkaHub / Chatbox / Cherry Studio: base URL, auth entry point, model
 * catalogue URL and a small verified model list so a fresh install can chat
 * without visiting five different docs pages first.
 *
 * The model list is intentionally curated rather than exhaustive — the
 * provider editor can pull the live `/models` catalogue at any time.
 */
data class ProviderPreset(
    val id: String,
    val kind: ProviderKind,
    val name: String,
    val baseUrl: String,
    val models: List<ModelInfo> = emptyList(),
    val defaultModelId: String? = null,
    val description: String = "",
    val docsUrl: String? = null,
    val apiKeyUrl: String? = null,
    val modelsUrl: String? = null,
    val enabledByDefault: Boolean = false,
) {
    fun toProvider(): ProviderConfig = ProviderConfig(
        id = id,
        kind = kind,
        name = name,
        baseUrl = baseUrl,
        models = models,
        defaultModelId = defaultModelId ?: models.firstOrNull()?.id,
        enabled = enabledByDefault,
        builtin = true,
        description = description,
        docsUrl = docsUrl,
        apiKeyUrl = apiKeyUrl,
        modelsUrl = modelsUrl
    )
}

/**
 * Built-in provider catalogue.
 *
 * The first four are the original Kujira providers and stay enabled on a fresh
 * install. OpenCode Zen / Go are added once for existing installs too (disabled
 * until the user supplies a key). The remaining presets are available from the
 * "添加 Provider" sheet without cluttering the model picker.
 */
object ProviderPresets {

    private const val OPENCODE_MODELS_URL = "https://opencode.ai/inference/v1/models"
    private const val OPENCODE_CONSOLE_URL = "https://opencode.ai/console"

    private fun model(
        id: String,
        name: String,
        context: Int? = null,
        output: Int? = null,
        tools: Boolean = true,
        vision: Boolean = false,
        reasoning: Boolean = false,
        temperature: Boolean = true,
    ) = ModelInfo(
        id = id,
        displayName = name,
        contextWindow = context,
        supportsTools = tools,
        supportsVision = vision,
        supportsReasoning = reasoning,
        supportsTemperature = temperature,
        maxOutputTokens = output
    )

    // ------------------------------------------------------------------
    // OpenCode (the primary integration)
    // ------------------------------------------------------------------

    /**
     * Curated OpenCode Zen list. Values mirror models.dev on 2026-11-06:
     * context/output limits, tool use, vision and whether the model accepts
     * `temperature`. The live catalogue is one tap away in the editor.
     */
    private val openCodeZenModels = listOf(
        model("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", 1_000_000, 384_000, vision = true, reasoning = true),
        model("deepseek-v4-pro", "DeepSeek V4 Pro", 1_000_000, 384_000, reasoning = true),
        model("claude-sonnet-4-5", "Claude Sonnet 4.5", 1_000_000, 64_000, vision = true, reasoning = true),
        model("claude-opus-4-5", "Claude Opus 4.5", 200_000, 64_000, vision = true, reasoning = true),
        model("gpt-5.4", "GPT-5.4", 1_050_000, 128_000, vision = true, reasoning = true),
        model("gemini-3-pro", "Gemini 3 Pro", 1_048_576, 65_536, vision = true, reasoning = true),
        model("kimi-k3", "Kimi K3", 1_048_576, 131_072, vision = true, reasoning = true, temperature = false),
        model("kimi-k2.7-code", "Kimi K2.7 Code", 262_144, 262_144, vision = true, reasoning = true, temperature = false),
        model("qwen3.8-max", "Qwen3.8 Max", 262_144, 131_072, vision = true, reasoning = true),
        model("qwen3-coder", "Qwen3 Coder", 262_144, 65_536),
        model("glm-5.3", "GLM-5.3", 1_000_000, 131_072, reasoning = true),
        model("minimax-m3", "MiniMax-M3", 512_000, 128_000, vision = true, reasoning = true),
        model("grok-4.7", "Grok 4.7", 500_000, 500_000, vision = true, reasoning = true),
        model("big-pickle", "Big Pickle", 200_000, 32_000, reasoning = true),
    )

    private val openCodeGoModels = listOf(
        model("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", 1_000_000, 384_000, vision = true, reasoning = true),
        model("deepseek-v4-pro", "DeepSeek V4 Pro", 1_000_000, 384_000, reasoning = true),
        model("glm-5.3", "GLM-5.3", 1_000_000, 131_072, reasoning = true),
        model("glm-5.3-flash", "GLM-5.3-Flash", 1_000_000, 131_072, vision = true, reasoning = true),
        model("glm-5.2", "GLM-5.2", 1_000_000, 131_072, reasoning = true),
        model("kimi-k3", "Kimi K3", 1_048_576, 131_072, vision = true, reasoning = true, temperature = false),
        model("kimi-k3.1-pro", "Kimi K3.1 Pro", 1_048_576, 131_072, vision = true, reasoning = true, temperature = false),
        model("kimi-k2.7-code", "Kimi K2.7 Code", 262_144, 262_144, vision = true, reasoning = true, temperature = false),
        model("qwen3.8-max", "Qwen3.8 Max", 1_000_000, 131_072, vision = true, reasoning = true),
        model("qwen3.8-flash", "Qwen3.8 Flash", 1_000_000, 131_072, vision = true, reasoning = true),
        model("qwen3.7-plus", "Qwen3.7 Plus", 1_000_000, 65_536, vision = true, reasoning = true),
        model("minimax-m3", "MiniMax-M3", 1_000_000, 131_072, vision = true, reasoning = true),
        model("minimax-m3.1-flash", "MiniMax M3.1 Flash", 1_000_000, 512_000, vision = true, reasoning = true),
        model("grok-4.7", "Grok 4.7", 500_000, 500_000, vision = true, reasoning = true),
        model("gpt-5.6-luna", "GPT-5.6 Luna", 1_050_000, 128_000, vision = true, reasoning = true, temperature = false),
        model("gpt-6-luna", "GPT-6 Luna", 1_050_000, 128_000, vision = true, reasoning = true, temperature = false),
        model("claude-haiku-5-5", "Claude Haiku 5.5", 1_000_000, 128_000, vision = true, reasoning = true, temperature = false),
        model("gemini-4-argon", "Gemini 4 Argon", 1_048_576, 65_536, vision = true, reasoning = true, temperature = false),
        model("hy4", "Hy4", 1_048_576, 65_536, vision = true, reasoning = true),
        model("longcat-2.0", "LongCat-2.0", 1_000_000, 131_072, reasoning = true),
        model("mimo-v2.6-flash", "MiMo-V2.6-Flash", 1_048_576, 131_072, vision = true, reasoning = true),
        model("step-5-preview", "Step 5 Preview", 1_000_000, 65_536, vision = true, reasoning = true),
    )

    val openCodeZen = ProviderPreset(
        id = "opencode-zen",
        kind = ProviderKind.OPENCODE_ZEN,
        name = "OpenCode Zen",
        baseUrl = "https://opencode.ai/inference/openai/v1",
        models = openCodeZenModels,
        defaultModelId = "deepseek-v4.1-flash",
        description = "OpenCode 官方按量网关，一个 Key 使用 GPT / Claude / Gemini 与开源模型；免费档仅限 OpenCode 客户端",
        docsUrl = "https://opencode.ai/v2/docs/console/models/",
        apiKeyUrl = OPENCODE_CONSOLE_URL,
        modelsUrl = OPENCODE_MODELS_URL,
    )

    val openCodeGo = ProviderPreset(
        id = "opencode-go",
        kind = ProviderKind.OPENCODE_GO,
        name = "OpenCode Go",
        baseUrl = "https://opencode.ai/inference/go/openai/v1",
        models = openCodeGoModels,
        defaultModelId = "deepseek-v4.1-flash",
        description = "OpenCode Go 订阅制网关，专注 DeepSeek / Kimi / GLM / Qwen 等开源模型",
        docsUrl = "https://opencode.ai/v2/docs/console/go/",
        apiKeyUrl = OPENCODE_CONSOLE_URL,
        modelsUrl = OPENCODE_MODELS_URL,
    )

    // ------------------------------------------------------------------
    // Original Kujira providers
    // ------------------------------------------------------------------

    val deepSeek = ProviderPreset(
        id = "deepseek",
        kind = ProviderKind.DEEPSEEK,
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        models = listOf(
            model("deepseek-chat", "DeepSeek Chat", 128_000),
            model("deepseek-reasoner", "DeepSeek Reasoner", 128_000, reasoning = true, temperature = false),
        ),
        defaultModelId = "deepseek-chat",
        description = "DeepSeek 官方 API",
        docsUrl = "https://api-docs.deepseek.com/",
        apiKeyUrl = "https://platform.deepseek.com/api_keys",
        modelsUrl = "https://api.deepseek.com/models",
        enabledByDefault = true,
    )

    val openAi = ProviderPreset(
        id = "openai",
        kind = ProviderKind.OPENAI,
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        models = listOf(
            model("gpt-5.4", "GPT-5.4", 1_050_000, 128_000, vision = true, reasoning = true),
            model("gpt-5.4-mini", "GPT-5.4 mini", 400_000, 128_000, vision = true, reasoning = true),
            model("gpt-4.1", "GPT-4.1", 1_047_576, 32_768, vision = true),
        ),
        defaultModelId = "gpt-5.4",
        description = "OpenAI 官方 API",
        docsUrl = "https://platform.openai.com/docs/models",
        apiKeyUrl = "https://platform.openai.com/api-keys",
        enabledByDefault = true,
    )

    val anthropic = ProviderPreset(
        id = "anthropic",
        kind = ProviderKind.ANTHROPIC,
        name = "Anthropic",
        baseUrl = "https://api.anthropic.com/v1",
        models = listOf(
            model("claude-sonnet-5-5", "Claude Sonnet 5.5", 200_000, 64_000, vision = true, reasoning = true),
            model("claude-opus-5-5", "Claude Opus 5.5", 200_000, 64_000, vision = true, reasoning = true),
            model("claude-haiku-4-5", "Claude Haiku 4.5", 200_000, 64_000, vision = true, reasoning = true),
        ),
        defaultModelId = "claude-sonnet-5-5",
        description = "Anthropic 官方 Messages API",
        docsUrl = "https://platform.claude.com/docs/en/about-claude/models/overview",
        apiKeyUrl = "https://platform.claude.com/settings/keys",
        enabledByDefault = true,
    )

    val openRouter = ProviderPreset(
        id = "openrouter",
        kind = ProviderKind.OPENROUTER,
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        models = listOf(
            model("anthropic/claude-sonnet-4.6", "Claude Sonnet 4.6", 1_000_000, vision = true, reasoning = true),
            model("deepseek/deepseek-chat", "DeepSeek Chat", 128_000),
            model("google/gemini-2.5-pro", "Gemini 2.5 Pro", 1_048_576, vision = true, reasoning = true),
        ),
        defaultModelId = "anthropic/claude-sonnet-4.6",
        description = "一个 Key 聚合数百个模型",
        docsUrl = "https://openrouter.ai/docs",
        apiKeyUrl = "https://openrouter.ai/keys",
        modelsUrl = "https://openrouter.ai/api/v1/models",
        enabledByDefault = true,
    )

    /** Providers that always exist after the first launch. */
    val core: List<ProviderPreset> = listOf(
        deepSeek,
        openAi,
        anthropic,
        openRouter,
        openCodeZen,
        openCodeGo,
    )

    // ------------------------------------------------------------------
    // Optional presets shown in the add-provider sheet
    // ------------------------------------------------------------------

    val google = ProviderPreset(
        id = "google",
        kind = ProviderKind.OPENAI,
        name = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        models = listOf(
            model("gemini-3.8-flash", "Gemini 3.8 Flash", 1_048_576, 65_536, vision = true, reasoning = true),
            model("gemini-3.7-flash", "Gemini 3.7 Flash", 1_048_576, 65_536, vision = true, reasoning = true),
            model("gemini-3.1-pro-preview", "Gemini 3.1 Pro", 1_048_576, 65_536, vision = true, reasoning = true),
        ),
        defaultModelId = "gemini-3.8-flash",
        description = "Gemini 官方 OpenAI 兼容端点",
        docsUrl = "https://ai.google.dev/gemini-api/docs/openai",
        apiKeyUrl = "https://aistudio.google.com/apikey",
        modelsUrl = "https://generativelanguage.googleapis.com/v1beta/openai/models",
    )

    val xAi = ProviderPreset(
        id = "xai",
        kind = ProviderKind.OPENAI,
        name = "xAI Grok",
        baseUrl = "https://api.x.ai/v1",
        models = listOf(
            model("grok-4.7", "Grok 4.7", 500_000, 500_000, vision = true, reasoning = true),
            model("grok-4.5", "Grok 4.5", 500_000, 500_000, vision = true, reasoning = true),
        ),
        defaultModelId = "grok-4.7",
        description = "xAI 官方 API",
        docsUrl = "https://docs.x.ai/docs/models",
        apiKeyUrl = "https://console.x.ai/",
        modelsUrl = "https://api.x.ai/v1/models",
    )

    val groq = ProviderPreset(
        id = "groq",
        kind = ProviderKind.OPENAI,
        name = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        models = listOf(
            model("openai/gpt-oss-120b", "GPT-OSS 120B", 131_072, tools = true, reasoning = true),
            model("llama-3.3-70b-versatile", "Llama 3.3 70B", 128_000),
            model("qwen/qwen3-32b", "Qwen3 32B", 131_072, reasoning = true),
        ),
        defaultModelId = "openai/gpt-oss-120b",
        description = "Groq 超低延迟推理",
        docsUrl = "https://console.groq.com/docs/models",
        apiKeyUrl = "https://console.groq.com/keys",
        modelsUrl = "https://api.groq.com/openai/v1/models",
    )

    val mistral = ProviderPreset(
        id = "mistral",
        kind = ProviderKind.OPENAI,
        name = "Mistral",
        baseUrl = "https://api.mistral.ai/v1",
        models = listOf(
            model("mistral-large-latest", "Mistral Large", 262_144),
            model("codestral-latest", "Codestral", 256_000),
            model("devstral-latest", "Devstral", 262_144),
        ),
        defaultModelId = "mistral-large-latest",
        description = "Mistral 官方 API，代码与通用模型",
        docsUrl = "https://docs.mistral.ai/getting-started/models/",
        apiKeyUrl = "https://console.mistral.ai/api-keys/",
        modelsUrl = "https://api.mistral.ai/v1/models",
    )

    val moonshot = ProviderPreset(
        id = "moonshot",
        kind = ProviderKind.OPENAI,
        name = "Moonshot / Kimi",
        baseUrl = "https://api.moonshot.ai/v1",
        models = listOf(
            model("kimi-k3", "Kimi K3", 1_048_576, 131_072, vision = true, reasoning = true, temperature = false),
            model("kimi-k3.1-pro", "Kimi K3.1 Pro", 1_048_576, 131_072, vision = true, reasoning = true, temperature = false),
            model("kimi-k2.7-code", "Kimi K2.7 Code", 262_144, 262_144, vision = true, reasoning = true, temperature = false),
        ),
        defaultModelId = "kimi-k3",
        description = "Kimi 官方 API",
        docsUrl = "https://platform.moonshot.ai/docs/api/chat",
        apiKeyUrl = "https://platform.moonshot.ai/console/api-keys",
        modelsUrl = "https://api.moonshot.ai/v1/models",
    )

    val zhipu = ProviderPreset(
        id = "zhipu",
        kind = ProviderKind.OPENAI,
        name = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        models = listOf(
            model("glm-5.3", "GLM-5.3", 1_000_000, 131_072, reasoning = true),
            model("glm-5.3-flash", "GLM-5.3-Flash", 1_000_000, 131_072, vision = true, reasoning = true),
            model("glm-5.2", "GLM-5.2", 1_000_000, 131_072, reasoning = true),
        ),
        defaultModelId = "glm-5.3",
        description = "智谱 AI 官方 API",
        docsUrl = "https://docs.bigmodel.cn/cn/guide/start/pricing",
        apiKeyUrl = "https://open.bigmodel.cn/usercenter/apikeys",
        modelsUrl = "https://open.bigmodel.cn/api/paas/v4/models",
    )

    val picker: List<ProviderPreset> = listOf(
        openCodeZen,
        openCodeGo,
        deepSeek,
        openAi,
        anthropic,
        openRouter,
        google,
        xAi,
        groq,
        mistral,
        moonshot,
        zhipu,
    )

    fun byId(id: String): ProviderPreset? = picker.firstOrNull { it.id == id }
}
