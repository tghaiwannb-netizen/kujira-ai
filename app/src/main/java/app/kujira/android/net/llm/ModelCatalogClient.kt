package app.kujira.android.net.llm

import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.data.model.isOpenCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * One model returned by a provider's `/models` endpoint.
 *
 * Capability fields are nullable because OpenAI's plain catalogue only returns
 * `{id, object, created, owned_by}` while OpenRouter / OpenCode / models.dev
 * return context limits and tool/vision/reasoning metadata. Null means "the
 * server did not say", so merging can keep what we already know.
 */
data class CatalogModelInfo(
    val id: String,
    val displayName: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val supportsTools: Boolean? = null,
    val supportsVision: Boolean? = null,
    val supportsReasoning: Boolean? = null,
    val supportsTemperature: Boolean? = null,
    val inputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    val cacheReadPricePerMillion: Double? = null,
)

data class RemoteModelCatalog(
    val models: List<CatalogModelInfo>,
    val sourceUrl: String,
    val elapsedMs: Long,
)

/** Failure while fetching or parsing a remote model catalogue. */
class ModelCatalogException(
    message: String,
    val httpCode: Int? = null,
) : IOException(message)

/**
 * Talks to the OpenAI-style `GET <base>/models` catalogue that virtually every
 * provider exposes. OpenCode publishes its catalogue at a separate URL, which
 * [ProviderConfig.modelsUrl] carries for both gateways.
 *
 * Inspired by RikkaHub's "model list" page, Chatbox's provider definitions and
 * Cherry Studio's model sync: fetch once, keep user-edited display names, and
 * fill in capabilities the provider actually reported.
 */
class ModelCatalogClient(
    private val httpClient: OkHttpClient,
    private val json: Json = LlmJson,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun fetch(provider: ProviderConfig, apiKey: String): RemoteModelCatalog =
        withContext(Dispatchers.IO) {
            val url = modelsEndpoint(provider)
            val startedAt = clock()
            val request = Request.Builder()
                .url(url)
                .headers(catalogHeaders(provider, apiKey))
                .get()
                .build()

            val body = try {
                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw ModelCatalogException(
                            LlmErrors.friendlyHttpMessage(response.code, text),
                            response.code
                        )
                    }
                    text
                }
            } catch (e: ModelCatalogException) {
                throw e
            } catch (e: IOException) {
                throw ModelCatalogException(LlmErrors.ioFailure(e).message)
            }

            val parsed = try {
                parseModelCatalog(json.parseToJsonElement(body))
            } catch (e: Exception) {
                throw ModelCatalogException("模型列表不是有效的 JSON：${e.message ?: "解析失败"}")
            }
            if (parsed.isEmpty()) {
                throw ModelCatalogException("服务端没有返回任何聊天模型，请检查 Base URL 是否指向 OpenAI 兼容端点。")
            }
            RemoteModelCatalog(
                models = parsed,
                sourceUrl = url,
                elapsedMs = clock() - startedAt
            )
        }

    /** The URL to GET, honouring an explicit catalogue URL first. */
    fun modelsEndpoint(provider: ProviderConfig): String {
        provider.modelsUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        var base = provider.baseUrl.trim().trimEnd('/')
        base = base.removeSuffix("/chat/completions").removeSuffix("/messages").trimEnd('/')
        if (provider.kind.isOpenCode) {
            // Base URLs are /inference/openai/v1 and /inference/go/openai/v1;
            // the catalogue is shared at /inference/v1/models.
            val root = base.substringBefore("/openai/v1").ifBlank { base }
            return if (root.contains("/inference")) {
                root.substringBefore("/go").trimEnd('/') + "/v1/models"
            } else {
                "$base/models"
            }
        }
        return "$base/models"
    }

    private fun catalogHeaders(provider: ProviderConfig, apiKey: String) = okhttp3.Headers.Builder().apply {
        add("Accept", "application/json")
        add("User-Agent", "Kujira/1.0 (Android)")
        val key = apiKey.trim()
        if (key.isNotEmpty()) {
            when (provider.kind) {
                ProviderKind.ANTHROPIC -> {
                    add("x-api-key", key)
                    add("anthropic-version", AnthropicWire.API_VERSION)
                }

                else -> add("Authorization", "Bearer $key")
            }
        }
        if (provider.kind == ProviderKind.OPENROUTER) {
            add("HTTP-Referer", "https://kujira.app")
            add("X-Title", "Kujira")
        }
        if (provider.kind.isOpenCode) {
            add("x-opencode-session", openCodeSessionId("kujira-models"))
        }
    }.build()

    companion object {

        /**
         * Parses the catalogue shapes used in the wild:
         * - `{"data":[{"id":...}]}` (OpenAI / OpenRouter / OpenCode)
         * - a bare array
         * - `{"models":[...]}` (Ollama-like)
         * - a map keyed by model id
         * - models.dev provider objects (`{models:{id:{...}}}`)
         */
        internal fun parseModelCatalog(root: JsonElement): List<CatalogModelInfo> {
            val entries = modelObjects(root)
            val seen = HashSet<String>()
            return entries.mapNotNull { (fallbackId, obj) ->
                parseModel(obj, fallbackId)?.takeIf { seen.add(it.id) }
            }
        }

        private fun modelObjects(root: JsonElement): List<Pair<String?, JsonObject>> {
            return when (root) {
                is JsonArray -> root.mapNotNull { (it as? JsonObject)?.let { obj -> null to obj } }
                is JsonObject -> {
                    // A provider object from models.dev: recurse into its models map.
                    if (root.containsKey("models") &&
                        (root.containsKey("api") || root.containsKey("npm") || root.containsKey("env"))
                    ) {
                        return modelObjects(root["models"]!!)
                    }
                    if (isModelObject(root)) return listOf(null to root)

                    val containers = listOf("data", "models", "results", "items")
                    for (key in containers) {
                        root[key]?.let { child ->
                            val parsed = modelObjects(child)
                            if (parsed.isNotEmpty()) return parsed
                        }
                    }

                    // A map of provider -> provider object.
                    val childObjects = root.values.filterIsInstance<JsonObject>()
                    if (childObjects.isNotEmpty() && childObjects.all { it.containsKey("models") }) {
                        return childObjects.flatMap { provider -> modelObjects(provider["models"]!!) }
                    }

                    // A map of model id -> model object.
                    root.entries.mapNotNull { (key, value) ->
                        (value as? JsonObject)?.let { key to it }
                    }
                }

                else -> emptyList()
            }
        }

        private fun isModelObject(obj: JsonObject): Boolean =
            listOf("id", "model", "modelId", "model_id", "slug").any { obj.stringOf(it) != null }

        private fun parseModel(
            obj: JsonObject,
            fallbackId: String?,
        ): CatalogModelInfo? {
            val id = sequenceOf("id", "model", "modelId", "model_id", "slug")
                .mapNotNull { obj.stringOf(it) }
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: fallbackId?.takeIf { it.isNotBlank() }
                ?: return null

            val type = obj.stringOf("type")?.lowercase()
            if (type in setOf("embedding", "image", "tts", "transcription", "rerank")) return null

            val modalities = obj["modalities"] as? JsonObject
                ?: obj["architecture"] as? JsonObject
            val inputModalities = modalities?.get("input")?.stringList()
                ?: modalities?.get("input_modalities")?.stringList()
            val outputModalities = modalities?.get("output")?.stringList()
                ?: modalities?.get("output_modalities")?.stringList()
            if (outputModalities != null && outputModalities.none { it.equals("text", true) }) return null

            val limit = obj["limit"] as? JsonObject
            val capabilities = obj["capabilities"] as? JsonObject
            val tools = capabilities?.get("tools")
            val reasoning = capabilities?.get("reasoning")
            // OpenRouter publishes capabilities as a flat parameter list.
            val supportedParameters = obj["supported_parameters"]?.stringList()

            val supportsTools = when (tools) {
                is JsonObject -> tools["supported"].asBoolean()
                else -> null
            } ?: obj["tool_call"].asBoolean()
                ?: obj["tools"].asBoolean()
                ?: obj["supports_tools"].asBoolean()
                ?: obj["supportsTools"].asBoolean()
                ?: supportedParameters?.any { it.equals("tools", true) }

            val supportsReasoning = when (reasoning) {
                is JsonObject -> reasoning["supported"].asBoolean()
                is JsonPrimitive -> reasoning.asBoolean()
                else -> null
            } ?: obj["reasoning"].asBoolean()
                ?: obj["supports_reasoning"].asBoolean()
                ?: obj["supportsReasoning"].asBoolean()
                ?: supportedParameters?.any { it.equals("reasoning", true) }

            val supportsVision = obj["attachment"].asBoolean()
                ?: obj["vision"].asBoolean()
                ?: capabilities?.get("vision").asBoolean()
                ?: inputModalities?.let { list -> list.any { it.contains("image", true) } }

            val supportsTemperature = capabilities?.get("temperature").asBoolean()
                ?: obj["temperature"].asBoolean()
                ?: obj["supports_temperature"].asBoolean()
                ?: obj["supportsTemperature"].asBoolean()
                ?: supportedParameters?.any { it.equals("temperature", true) }

            val context = limit?.intOf("context", "input", "context_window")
                ?: obj.intOf("context_length", "context_window", "contextWindow", "max_context_length")

            val output = limit?.intOf("output", "max_output")
                ?: obj.intOf("max_output_tokens", "maxOutputTokens", "max_output")
                ?: (obj["top_provider"] as? JsonObject)?.intOf("max_completion_tokens")

            val cost = obj["cost"] as? JsonObject
            val costText = cost?.get("text") as? JsonObject
            val pricing = obj["pricing"] as? JsonObject
            val inputPrice = cost?.doubleOf("input")
                ?: costText?.doubleOf("input")
                ?: pricing?.doubleOf("prompt")?.times(1_000_000)
            val outputPrice = cost?.doubleOf("output")
                ?: costText?.doubleOf("output")
                ?: pricing?.doubleOf("completion")?.times(1_000_000)
            val cacheReadPrice = cost?.doubleOf("cache_read")
                ?: costText?.doubleOf("cache_read")
                ?: pricing?.doubleOf("input_cache_read")?.times(1_000_000)
                ?: pricing?.doubleOf("prompt_cache_read")?.times(1_000_000)

            val display = sequenceOf("name", "display_name", "displayName", "nickname")
                .mapNotNull { obj.stringOf(it) }
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: id

            return CatalogModelInfo(
                id = id,
                displayName = display,
                contextWindow = context,
                maxOutputTokens = output,
                supportsTools = supportsTools,
                supportsVision = supportsVision,
                supportsReasoning = supportsReasoning,
                supportsTemperature = supportsTemperature,
                inputPricePerMillion = inputPrice,
                outputPricePerMillion = outputPrice,
                cacheReadPricePerMillion = cacheReadPrice,
            )
        }

        private fun JsonElement?.stringList(): List<String>? {
            val array = this as? JsonArray ?: return null
            return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }

        private fun JsonObject.doubleOf(vararg keys: String): Double? =
            keys.firstNotNullOfOrNull { key ->
                (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()
            }

        /**
         * Merge a live catalogue into the user's list. Existing display names,
         * manually added models and any metadata the server omitted survive.
         */
        fun mergeModels(existing: List<ModelInfo>, remote: List<CatalogModelInfo>): List<ModelInfo> {
            val byId = existing.associateBy { it.id }
            val merged = remote.map { info ->
                val old = byId[info.id]
                ModelInfo(
                    id = info.id,
                    displayName = old?.displayName
                        ?.takeIf { it.isNotBlank() && it != old.id }
                        ?: info.displayName.ifBlank { info.id },
                    contextWindow = info.contextWindow ?: old?.contextWindow,
                    supportsTools = info.supportsTools ?: old?.supportsTools ?: true,
                    supportsVision = info.supportsVision ?: old?.supportsVision ?: false,
                    supportsReasoning = info.supportsReasoning ?: old?.supportsReasoning ?: false,
                    supportsTemperature = info.supportsTemperature ?: old?.supportsTemperature ?: true,
                    maxOutputTokens = info.maxOutputTokens ?: old?.maxOutputTokens,
                    inputPricePerMillion = info.inputPricePerMillion ?: old?.inputPricePerMillion,
                    outputPricePerMillion = info.outputPricePerMillion ?: old?.outputPricePerMillion,
                    cacheReadPricePerMillion = info.cacheReadPricePerMillion ?: old?.cacheReadPricePerMillion,
                )
            }
            val remoteIds = remote.mapTo(HashSet()) { it.id }
            return (merged + existing.filterNot { it.id in remoteIds }).distinctBy { it.id }
        }
    }
}
