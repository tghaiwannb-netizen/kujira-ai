package app.kujira.android.domain

import app.kujira.android.core.ProviderPreset
import app.kujira.android.core.SecureStore
import app.kujira.android.core.SettingsStore
import app.kujira.android.data.model.AppSettings
import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.data.model.Role
import app.kujira.android.data.model.isOpenCode
import app.kujira.android.net.llm.LlmEvent
import app.kujira.android.net.llm.LlmRequest
import app.kujira.android.net.llm.LlmStreamer
import app.kujira.android.net.llm.LlmTurn
import app.kujira.android.net.llm.ModelCatalogClient
import app.kujira.android.net.llm.ModelCatalogException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Result of pulling a provider's live model catalogue. [models] is already
 * merged with the user's current list so the editor can display it directly.
 */
data class ModelSyncResult(
    val ok: Boolean,
    val message: String,
    val models: List<ModelInfo> = emptyList(),
    val added: Int = 0,
    val total: Int = 0,
    val sourceUrl: String? = null,
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
    private val modelCatalog: ModelCatalogClient? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    val providers: StateFlow<List<ProviderConfig>> = settingsStore.providers
    val settings: StateFlow<AppSettings> = settingsStore.settings

    /**
     * Increments whenever an API key changes. Provider metadata lives in a
     * StateFlow but keys live in [SecureStore]; the provider list collects this
     * counter to refresh its "已配置 / 未配置" badges.
     */
    private val _keyRevision = MutableStateFlow(0)
    val keyRevision: StateFlow<Int> = _keyRevision.asStateFlow()

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
            secretKey = provider.secretKey.ifBlank { "provider:${provider.id}" },
            description = provider.description?.trim()?.takeIf { it.isNotEmpty() },
            docsUrl = provider.docsUrl?.trim()?.takeIf { it.isNotEmpty() },
            apiKeyUrl = provider.apiKeyUrl?.trim()?.takeIf { it.isNotEmpty() },
            modelsUrl = provider.modelsUrl?.trim()?.takeIf { it.isNotEmpty() }
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
        _keyRevision.value += 1
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
        _keyRevision.value += 1
    }

    /**
     * Toggles a provider without losing its models/key. Disabling the active
     * provider moves the selection to the next enabled one, mirroring the
     * model picker's rule that only enabled providers are selectable.
     */
    fun setEnabled(providerId: String, enabled: Boolean) {
        val provider = get(providerId) ?: return
        if (provider.enabled == enabled) return
        save(provider.copy(enabled = enabled))
        when {
            enabled && settings.value.activeProviderId == null ->
                setActive(provider.id, provider.defaultModel()?.id)

            !enabled && settings.value.activeProviderId == providerId -> {
                val next = providers.value.firstOrNull { it.enabled && it.id != providerId }
                setActive(next?.id, next?.defaultModel()?.id)
            }
        }
    }

    /**
     * Adds a catalogue preset under its stable id. If the user already has it
     * (or re-adds one they previously deleted), the existing row is returned.
     */
    fun addPreset(preset: ProviderPreset): ProviderConfig {
        get(preset.id)?.let { return it }
        // Explicitly adding a preset signals intent to use it; enable it even
        // though the same preset may ship disabled on a fresh install.
        val provider = preset.toProvider().copy(enabled = true)
        settingsStore.upsertProvider(provider)
        if (settings.value.activeProviderId == null) {
            setActive(provider.id, provider.defaultModel()?.id)
        }
        return provider
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
        val active = current.activeProviderId?.let(::get)?.takeIf { it.enabled }
        val keyed = providers.value.filter { it.enabled && hasApiKey(it.id) }

        // Self-heal the common "all models unusable" state: the saved provider
        // is enabled but has no key (often the default DeepSeek), while another
        // configured provider is ready. Prefer the one that can actually chat.
        val provider = when {
            active == null -> keyed.firstOrNull() ?: providers.value.firstOrNull { it.enabled }
            !hasApiKey(active.id) && keyed.isNotEmpty() -> keyed.first()
            else -> active
        }
        if (provider == null) {
            setActive(null, null)
            return
        }
        val model = if (provider.id == current.activeProviderId) {
            current.activeModelId?.takeIf { id -> provider.models.any { it.id == id } }
                ?: provider.defaultModel()?.id
        } else {
            provider.defaultModel()?.id
        }
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

    /**
     * Parses `id|显示名` lines but keeps capabilities / context limits from
     * [metadata] for every id it recognises. This is what preserves a synced
     * catalogue when the user edits the text list.
     */
    fun parseModelsWithMetadata(text: String, metadata: List<ModelInfo>): List<ModelInfo> {
        val byId = metadata.associateBy { it.id }
        return parseModels(text).map { parsed ->
            val known = byId[parsed.id] ?: return@map parsed
            known.copy(
                displayName = parsed.displayName.takeIf { it != parsed.id } ?: known.displayName
            )
        }
    }

    /** Replaces a provider's model list from raw multi-line settings text. */
    fun updateModels(providerId: String, text: String) {
        val provider = get(providerId) ?: return
        val models = parseModelsWithMetadata(text, provider.models)
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
    // model catalogue sync
    // ------------------------------------------------------------------

    /**
     * Fetches `/models`, merges it into [provider]'s current list and returns
     * the result without persisting. The editor keeps it in memory so the user
     * can review the ids before tapping 保存.
     */
    suspend fun fetchModels(
        provider: ProviderConfig,
        apiKeyOverride: String? = null,
    ): ModelSyncResult {
        val client = modelCatalog
            ?: return ModelSyncResult(false, "模型同步客户端未初始化。")
        val key = apiKeyOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: apiKeyOf(provider)
            ?: ""
        if (key.isBlank() && provider.builtin) {
            return ModelSyncResult(false, "请先填写 API Key，再从服务端同步模型。")
        }
        return try {
            val remote = client.fetch(provider, key)
            val merged = ModelCatalogClient.mergeModels(provider.models, remote.models)
            val existingIds = provider.models.mapTo(HashSet()) { it.id }
            val added = merged.count { it.id !in existingIds }
            val addedText = if (added > 0) "，新增 $added 个" else ""
            ModelSyncResult(
                ok = true,
                message = "已从服务端同步 ${remote.models.size} 个模型$addedText（${remote.elapsedMs}ms）。",
                models = merged,
                added = added,
                total = merged.size,
                sourceUrl = remote.sourceUrl
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelCatalogException) {
            val message = if (provider.kind.isOpenCode && e.httpCode == 403) {
                "OpenCode 返回 403：请确认 Key 属于 OpenCode Console，" +
                    "Go 需要对应订阅，免费档模型仅限 OpenCode 客户端。"
            } else {
                e.message ?: "同步模型失败"
            }
            ModelSyncResult(false, message, sourceUrl = null)
        } catch (e: Exception) {
            ModelSyncResult(false, "同步模型失败：${e.message ?: e.javaClass.simpleName}")
        }
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
            maxTokens = TEST_MAX_TOKENS,
            sessionId = "kujira-probe-${UUID.randomUUID()}"
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
