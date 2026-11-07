package app.kujira.android.net.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * [LlmClient] for Anthropic's `/v1/messages` SSE protocol.
 *
 * Emits [LlmEvent.TextDelta]/[LlmEvent.ReasoningDelta] for visible content and
 * thinking, and a single terminal [LlmEvent.Finished] carrying any `tool_use`
 * blocks whose JSON arguments were fully accumulated when their content block
 * closed. Failures become a single [LlmEvent.Failed] with a friendly Chinese
 * message; cancelling collection cancels the HTTP call. A stream that stays
 * silent (apart from `ping`s) is aborted by the idle watchdog in [SseTransport].
 */
class AnthropicClient(
    private val httpClient: OkHttpClient,
    private val json: Json = LlmJson,
) : LlmClient {

    private val transport = SseTransport(httpClient)

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val httpRequest = try {
            AnthropicWire.httpRequest(request, json)
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

        val state = AnthropicStreamState(json)
        try {
            transport.open(httpRequest).collect { sse ->
                state.accept(sse.event, sse.data).forEach { event -> emit(event) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SseIdleTimeoutException) {
            emit(
                LlmEvent.Failed(
                    "模型 ${e.idleMs / 1000} 秒没有返回任何数据，已中止。可能是网络卡住或模型在排队，请重试或换个模型。",
                    null,
                    true
                )
            )
            return@flow
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
