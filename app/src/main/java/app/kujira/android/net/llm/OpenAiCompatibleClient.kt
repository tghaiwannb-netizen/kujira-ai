package app.kujira.android.net.llm

import app.kujira.android.data.model.isOpenCode
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
 *
 * A stream that goes silent never hangs the chat: see the idle watchdog in
 * [SseTransport]. If the model already reported a finish reason and the gateway
 * simply forgot to close the stream, the answer is accepted as complete after a
 * short grace period instead of being reported as an error.
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
            transport.open(httpRequest) {
                if (state.hasFinishReason) STREAM_FINISHED_GRACE_MS else STREAM_IDLE_TIMEOUT_MS
            }
                .takeWhile { !it.isDone }
                .collect { sse ->
                    state.accept(sse.data).forEach { event -> emit(event) }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SseIdleTimeoutException) {
            if (!state.hasFinishReason) {
                emit(
                    LlmEvent.Failed(
                        "模型 ${e.idleMs / 1000} 秒没有返回任何数据，已中止。可能是网络卡住或模型在排队，请重试或换个模型。",
                        null,
                        true
                    )
                )
                return@flow
            }
            // finish_reason was already received: the gateway just never closed the stream.
        } catch (e: LlmHttpException) {
            val message = when {
                request.provider.kind.isOpenCode && e.code == 403 ->
                    "OpenCode 返回 403：免费档模型只允许在 OpenCode 客户端内使用；" +
                        "请改用付费模型，或确认当前 Key 已开通对应的 Go 订阅。"

                request.provider.kind.isOpenCode && e.code == 429 ->
                    "OpenCode 上游过载或限流（429）：当前模型服务暂时繁忙。" +
                        "请稍后重试，或切换到同 Provider 的其他模型。"

                else -> e.message ?: "请求失败"
            }
            emit(LlmEvent.Failed(message, e.code, LlmErrors.isRetryableCode(e.code)))
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
