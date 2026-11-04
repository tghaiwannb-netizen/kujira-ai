package app.kujira.android.domain

import app.kujira.android.core.SecureStore
import app.kujira.android.core.SettingsStore
import app.kujira.android.data.model.AppSettings
import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.data.model.Role
import app.kujira.android.net.llm.LlmEvent
import app.kujira.android.net.llm.LlmRequest
import app.kujira.android.net.llm.LlmStreamer
import app.kujira.android.net.llm.LlmTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Result of the "test connection" action shown in provider settings.
 *
 * [message] is always a human-readable Chinese sentence ready to display.
 */
data class ProviderTestResult(
    val ok: Boolean,
    val message: String,
    val latencyMs: Long = 0L,
    val httpCode: Int? = null,
    val sampleText: String = ""
)

/**
 * Provider CRUD plus secure API-key storage and connectivity probes.
 *
 * Provider metadata lives in [SettingsStore] (SharedPreferences JSON); API keys
 * live in [SecureStore] (Android Keystore AES/GCM) under each provider's
 * `secretKey` reference, so plaintext keys never touch the settings file.
 */
class ProviderRepository(
    private val settingsStore: SettingsStore,
    private val secureStore: SecureStore,
    private val streamer: LlmStreamer,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    val providers: StateFlow<List<ProviderConfig>> = settingsStore.providers
    val settings: StateFlow<AppSettings> = settingsStore.settings

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    fun get(providerId: String): ProviderConfig? =
        providers.value.firstOrNull { it.id == providerId }

    fun activeProvider(): ProviderConfig? = settings.value.activeProviderId?.let(::get)

    fun activeModelId(): String? =
        settings.value.activeModelId ?: activeProvider()?.defaultModel()?.id

    /** Plaintext key for the given provider, or `null` when not configured. */
    fun apiKey(providerId: String): String? {
        val provider = get(providerId) ?: return null
        return apiKeyOf(provider)
    }

    fun hasApiKey(providerId: String): Boolean = apiKey(providerId) != null

    // ------------------------------------------------------------------
    // writes
    // ------------------------------------------------------------------

    /** Inserts or updates a provider after normalizing its fields. */
    fun save(provider: ProviderConfig) {
        val models = provider.models.distinctBy { it.id }
        val defaultModelId = provider.defaultModelId
            ?.takeIf { id -> models.any { it.id == id } }
            ?: models.firstOrNull()?.id
        val normalized = provider.copy(
            name = provider.name.trim().ifBlank { provider.kind.name },
            baseUrl = provider.baseUrl.trim().trimEnd('/'),
            models = models,
            defaultModelId = defaultModelId,
            secretKey = provider.secretKey.ifBlank { "provider:${provider.id}" }
        )
        settingsStore.upsertProvider(normalized)
        if (settings.value.activeProviderId == null) {
            setActive(normalized.id, normalized.defaultModel()?.id)
        }
    }

    /** Removes a provider and forgets its stored API key. */
    fun delete(providerId: String) {
        val provider = get(providerId) ?: return
        settingsStore.deleteProvider(providerId)
        secureStore.remove(provider.secretKey)
        if (settings.value.activeProviderId == providerId) {
            val next = providers.value.firstOrNull { it.enabled }
            setActive(next?.id, next?.defaultModel()?.id)
        }
    }

    /** Stores (or clears, when [apiKey] is blank) the encrypted API key. */
    fun saveApiKey(providerId: String, apiKey: String?) {
        val provider = get(providerId) ?: return
        val trimmed = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed == null) secureStore.remove(provider.secretKey)
        else secureStore.put(provider.secretKey, trimmed)
    }

    fun setActive(providerId: String?, modelId: String?) {
        settingsStore.updateSettings {
            it.copy(activeProviderId = providerId, activeModelId = modelId)
        }
    }

    /**
     * Repairs the active provider/model selection after app start: picks the
     * first enabled provider when the saved selection points at nothing.
     */
    fun ensureActiveSelection() {
        val current = settings.value
        val provider = current.activeProviderId?.let(::get)?.takeIf { it.enabled }
        if (provider == null) {
            val next = providers.value.firstOrNull { it.enabled }
            setActive(next?.id, next?.defaultModel()?.id)
            return
        }
        val model = current.activeModelId?.takeIf { id -> provider.models.any { it.id == id } }
            ?: provider.defaultModel()?.id
        if (model != current.activeModelId || provider.id != current.activeProviderId) {
            setActive(provider.id, model)
        }
    }

    // ------------------------------------------------------------------
    // helpers for the settings UI
    // ------------------------------------------------------------------

    /** Parses the `id|显示名` multi-line model list used in provider settings. */
    fun parseModels(text: String): List<ModelInfo> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split('|', limit = 2)
            val id = parts[0].trim()
            val display = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: id
            ModelInfo(id = id, displayName = display)
        }
        .filter { it.id.isNotEmpty() }
        .distinctBy { it.id }
        .toList()

    /** Replaces a provider's model list from raw multi-line settings text. */
    fun updateModels(providerId: String, text: String) {
        val provider = get(providerId) ?: return
        val models = parseModels(text)
        save(
            provider.copy(
                models = models,
                defaultModelId = provider.defaultModelId?.takeIf { id -> models.any { it.id == id } }
                    ?: models.firstOrNull()?.id
            )
        )
    }

    fun createCustomProvider(
        name: String,
        baseUrl: String,
        modelsText: String,
        id: String = UUID.randomUUID().toString(),
    ): ProviderConfig {
        val models = parseModels(modelsText)
        return ProviderConfig(
            id = id,
            kind = ProviderKind.CUSTOM,
            name = name.trim().ifBlank { "自定义 Provider" },
            baseUrl = baseUrl.trim(),
            models = models,
            defaultModelId = models.firstOrNull()?.id
        )
    }

    // ------------------------------------------------------------------
    // connectivity test
    // ------------------------------------------------------------------

    /**
     * Sends a tiny streaming completion to verify URL, key and model.
     *
     * Uses the real streaming path (SSE + auth headers), so a green result
     * means the chat itself will connect. Costs a handful of tokens.
     */
    suspend fun testConnection(
        provider: ProviderConfig,
        modelId: String? = null,
    ): ProviderTestResult {
        val model = modelId?.takeIf { it.isNotBlank() } ?: provider.defaultModel()?.id
            ?: return ProviderTestResult(false, "尚未配置模型，请先填写模型 ID。")
        val key = apiKeyOf(provider)
        if (key.isNullOrBlank() && provider.builtin) {
            return ProviderTestResult(false, "尚未配置 API Key，请先填写后再测试。")
        }

        val request = LlmRequest(
            provider = provider,
            apiKey = key.orEmpty(),
            model = model,
            systemPrompt = "You are a connectivity probe. Reply with exactly: OK",
            messages = listOf(LlmTurn(Role.USER, "ping")),
            tools = emptyList(),
            temperature = 0.0,
            topP = 1.0,
            maxTokens = TEST_MAX_TOKENS
        )

        val startedAt = clock()
        val result = withTimeoutOrNull(TEST_TIMEOUT_MS) {
            try {
                var failure: LlmEvent.Failed? = null
                val sample = StringBuilder()
                streamer.stream(request).collect { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> sample.append(event.text)
                        is LlmEvent.ReasoningDelta -> Unit
                        is LlmEvent.Finished -> Unit
                        is LlmEvent.Failed -> failure = event
                    }
                }
                val elapsed = clock() - startedAt
                val failed = failure
                if (failed != null) {
                    ProviderTestResult(
                        ok = false,
                        message = failed.message,
                        latencyMs = elapsed,
                        httpCode = failed.httpCode
                    )
                } else {
                    ProviderTestResult(
                        ok = true,
                        message = "连接成功，模型响应正常（${elapsed}ms）。",
                        latencyMs = elapsed,
                        sampleText = sample.toString().trim().take(120)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProviderTestResult(
                    ok = false,
                    message = "连接测试失败：${e.message ?: e.javaClass.simpleName}",
                    latencyMs = clock() - startedAt
                )
            }
        }

        return result ?: ProviderTestResult(
            ok = false,
            message = "连接测试超时（${TEST_TIMEOUT_MS / 1000} 秒），请检查网络或 Base URL。",
            latencyMs = clock() - startedAt
        )
    }

    private fun apiKeyOf(provider: ProviderConfig): String? =
        secureStore.get(provider.secretKey)?.takeIf { it.isNotBlank() }

    private companion object {
        const val TEST_TIMEOUT_MS = 30_000L
        const val TEST_MAX_TOKENS = 24
    }
}
