package app.kujira.android.net.llm

import app.kujira.android.data.model.ProviderKind
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * A [LlmRequest] → stream-of-events function.
 *
 * Exists so the chat engine depends on behaviour, not on [LlmRouter] the class:
 * unit tests feed it a fake, production wires it to the real router.
 */
fun interface LlmStreamer {
    fun stream(request: LlmRequest): Flow<LlmEvent>
}

/**
 * Picks the right [LlmClient] for a provider kind.
 *
 * Anthropic gets its native `/v1/messages` client; everything else
 * (OpenAI, DeepSeek, OpenRouter, custom) speaks the OpenAI-compatible
 * `/chat/completions` protocol. The shared [OkHttpClient] is injected so the
 * app can reuse one connection pool and configure timeouts centrally.
 */
class LlmRouter(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val json: kotlinx.serialization.json.Json = LlmJson,
) : LlmStreamer {

    private val openAiCompatibleClient = OpenAiCompatibleClient(httpClient, json)
    private val anthropicClient = AnthropicClient(httpClient, json)

    fun clientFor(kind: ProviderKind): LlmClient = when (kind) {
        ProviderKind.ANTHROPIC -> anthropicClient
        else -> openAiCompatibleClient
    }

    /** Convenience: uses the provider config embedded in the request. */
    override fun stream(request: LlmRequest): Flow<LlmEvent> =
        clientFor(request.provider.kind).stream(request)

    companion object {
        /**
         * Client tuned for long-lived SSE streams: generous read timeout (models
         * can pause between tokens), no overall call timeout, short connect.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
