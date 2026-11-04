package app.kujira.android.net.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * [LlmClient] for the OpenAI `/chat/completions` wire format.
 *
 * Works for OpenAI itself, DeepSeek (including `reasoning_content`), OpenRouter
 * and any custom OpenAI-compatible gateway.
 *
 * The returned flow emits streaming deltas and then exactly one terminal event:
 * [LlmEvent.Finished] on success or [LlmEvent.Failed] with a human-readable
 * Chinese message on any HTTP/transport/protocol failure. Cancelling collection
 * cancels the underlying HTTP call.
 */
class OpenAiCompatibleClient(
    private val httpClient: OkHttpClient,
    private val json: Json = LlmJson,
) : LlmClient {

    private val transport = SseTransport(httpClient)

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val httpRequest = try {
            OpenAiWire.httpRequest(request, json)
        } catch (e: IllegalArgumentException) {
            emit(
                LlmEvent.Failed(
                    "接口地址无效：${request.provider.baseUrl.ifBlank { "（空）" }}",
                    null,
                    false
                )
            )
            return@flow
        }

        val state = OpenAiStreamState(json)
        try {
            transport.open(httpRequest)
                .takeWhile { !it.isDone }
                .collect { sse ->
                    state.accept(sse.data).forEach { event -> emit(event) }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmHttpException) {
            emit(LlmEvent.Failed(e.message ?: "请求失败", e.code, LlmErrors.isRetryableCode(e.code)))
            return@flow
        } catch (e: LlmStreamException) {
            emit(LlmEvent.Failed(e.message ?: "流式响应异常", e.code, e.retryable))
            return@flow
        } catch (e: IOException) {
            emit(LlmErrors.ioFailure(e))
            return@flow
        } catch (e: Throwable) {
            emit(LlmEvent.Failed("请求失败：${e.message ?: e.javaClass.simpleName}", null, false))
            return@flow
        }

        emit(state.finished())
    }
}
