package app.kujira.android.net.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Supplies bearer tokens to the transport, and refreshes them once on 401.
 *
 * Implementations wrap the Keystore-backed token store: [accessToken] is
 * called before every request, [refreshAccessToken] when the server rejected
 * the token. Throwing [McpAuthRequiredException] from either means "user must
 * authorize again".
 */
interface McpAuthTokenProvider {
    /** Current bearer token. */
    suspend fun accessToken(): String

    /** Forces a refresh; throws [McpAuthRequiredException] when re-auth is required. */
    suspend fun refreshAccessToken(): String
}

/**
 * HTTP binding for MCP's "Streamable HTTP" transport.
 *
 * One instance == one MCP session: it owns the `mcp-session-id` header and
 * knows how to decode both response shapes (plain JSON and SSE streams).
 * Requests without an expected response (notifications) accept 202/204.
 *
 * Cancellation: all blocking OkHttp work (connect, headers *and* reading the
 * body) runs on [Dispatchers.IO], and a watcher coroutine calls
 * [Call.cancel] the moment the caller is cancelled. Before this, tapping
 * "stop" during a tool call had to wait for the HTTP request to finish
 * (up to the 180 s tool timeout) and the body was even parsed on the caller's
 * (main) thread.
 */
class StreamableHttpTransport(
    private val endpoint: String,
    okHttpClient: OkHttpClient = OkHttpClient(),
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val auth: McpAuthTokenProvider? = null,
    private val logger: (String) -> Unit = {}
) {

    private val http: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // A tool call runs on a remote host; the socket can stay quiet for a while.
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val url: HttpUrl = runCatching { endpoint.trim().toHttpUrl() }.getOrElse {
        throw McpException("无效的 MCP 端点：$endpoint", it)
    }

    /** Session id echoed by the server's `mcp-session-id` response header, when present. */
    @Volatile
    var sessionId: String? = null
        private set

    /** Protocol version echoed by the `mcp-protocol-version` header, when present. */
    @Volatile
    var protocolVersion: String? = null
        private set

    @Volatile
    var isClosed: Boolean = false
        private set

    val endpointUrl: String get() = endpoint

    /**
     * POSTs one JSON-RPC payload.
     *
     * When [expectedId] is non-null the server answers with the matching frame
     * (otherwise null is acceptable, e.g. for notifications that return 202).
     */
    suspend fun post(
        payload: String,
        expectedId: JsonElement? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): JsonRpcResponse? {
        if (isClosed) throw McpException("MCP 连接已关闭")

        var token: String? = auth?.accessToken()
        var attempt = execute(payload, expectedId, timeoutMs, token)

        if (attempt.code == HTTP_UNAUTHORIZED && auth != null) {
            logger("服务器返回 401，尝试刷新授权后重试一次")
            token = auth.refreshAccessToken()
            attempt = execute(payload, expectedId, timeoutMs, token)
        }

        if (attempt.code == HTTP_UNAUTHORIZED) {
            throw McpAuthRequiredException(authHint(attempt))
        }
        if (attempt.code !in 200..299) {
            throw httpFailure(attempt)
        }
        return attempt.frame
    }

    /** Marks the transport unusable. The underlying OkHttpClient is owned by the caller. */
    fun close() {
        isClosed = true
        sessionId = null
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private data class Attempt(
        val code: Int,
        val frame: JsonRpcResponse?,
        val body: String
    )

    private suspend fun execute(
        payload: String,
        expectedId: JsonElement?,
        timeoutMs: Long,
        token: String?
    ): Attempt {
        val request = buildRequest(payload, token)
        val call = http.newCall(request)
        // Per-call total timeout: the client-level defaults only bound sockets.
        runCatching {
            call.timeout().timeout(timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS), TimeUnit.MILLISECONDS)
        }
        logger("→ ${request.method} ${request.url}")

        return try {
            coroutineScope {
                // Blocking socket reads do not react to coroutine cancellation,
                // so a watcher cancels the OkHttp call as soon as we are cancelled.
                val canceller = launch(Dispatchers.Default) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    withContext(Dispatchers.IO) { readAttempt(call, expectedId) }
                } finally {
                    canceller.cancel()
                }
            }
        } catch (timeout: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            throw McpException("MCP 请求超时（${timeoutMs / 1000}s）：服务器没有及时响应", timeout)
        } catch (io: IOException) {
            // A cancelled call surfaces as IOException("Canceled"): turn it back
            // into a CancellationException instead of a fake connection error.
            currentCoroutineContext().ensureActive()
            throw McpException("无法连接 MCP 服务器：${io.message ?: io.javaClass.simpleName}", io)
        }
    }

    /** Blocking: runs the call and decodes the response. Must stay off the main thread. */
    private fun readAttempt(call: Call, expectedId: JsonElement?): Attempt {
        val response: Response = call.execute()
        return response.use { res ->
            res.header(HEADER_SESSION_ID)?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            res.header(HEADER_PROTOCOL_VERSION)?.takeIf { it.isNotBlank() }?.let { protocolVersion = it }

            val code = res.code
            if (code == HTTP_ACCEPTED || code == HTTP_NO_CONTENT) return@use Attempt(code, null, "")

            val contentType = res.header("content-type").orEmpty()
            if (code !in 200..299) {
                val body = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
                logger("← HTTP $code：${body.take(300)}")
                return@use Attempt(code, null, body)
            }

            if (contentType.contains("text/event-stream", ignoreCase = true)) {
                return@use Attempt(code, readSse(res, expectedId), "")
            }

            val text = runCatching { res.body?.string().orEmpty() }.getOrDefault("").trim()
            if (text.isEmpty()) return@use Attempt(code, null, "")

            // Some servers send SSE without advertising it.
            if (text.startsWith("event:") || text.startsWith("data:")) {
                return@use Attempt(code, Sse.extractResponse(text, expectedId), "")
            }

            val frames = JsonRpc.decodeBatch(text)
            val frame = frames.firstOrNull { expectedId != null && JsonRpc.sameId(it.id, expectedId) }
                ?: frames.lastOrNull { it.isResponse }
            Attempt(code, frame, "")
        }
    }

    private fun readSse(response: Response, expectedId: JsonElement?): JsonRpcResponse? {
        val body = response.body ?: return null
        val decoder = SseEventDecoder()
        var last: JsonRpcResponse? = null

        body.use { buffered ->
            val source = buffered.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                for (payload in decoder.feedLine(line)) {
                    val frame = JsonRpc.decodeOrNull(payload) ?: continue
                    if (!frame.isResponse) continue
                    last = frame
                    if (expectedId != null && JsonRpc.sameId(frame.id, expectedId)) {
                        logger("← SSE 帧 id=${frame.id}")
                        return frame
                    }
                }
            }
        }

        for (payload in decoder.finish()) {
            val frame = JsonRpc.decodeOrNull(payload) ?: continue
            if (!frame.isResponse) continue
            last = frame
            if (expectedId != null && JsonRpc.sameId(frame.id, expectedId)) return frame
        }
        return last
    }

    private fun buildRequest(payload: String, token: String?): Request {
        val builder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("accept", ACCEPT)

        if (!token.isNullOrBlank()) builder.header("authorization", "Bearer $token")
        sessionId?.let { builder.header(HEADER_SESSION_ID, it) }
        extraHeaders.forEach { (key, value) ->
            if (key.lowercase() !in RESERVED_HEADERS) builder.header(key, value)
        }
        return builder.build()
    }

    private fun httpFailure(attempt: Attempt): McpException {
        val json = McpJson.parseObject(attempt.body)
        val serverMessage = (json?.get("detail") as? JsonPrimitive)?.contentOrNull
            ?: (json?.get("error") as? JsonPrimitive)?.contentOrNull
            ?: (json?.get("message") as? JsonPrimitive)?.contentOrNull
        val suffix = when {
            !serverMessage.isNullOrBlank() -> " — ${serverMessage.take(300)}"
            attempt.body.isNotBlank() -> " — ${attempt.body.take(300)}"
            else -> ""
        }
        return McpException("MCP 服务器返回 HTTP ${attempt.code}$suffix")
    }

    private fun authHint(attempt: Attempt): String {
        val json = McpJson.parseObject(attempt.body)
        val detail = (json?.get("detail") as? JsonPrimitive)?.contentOrNull
        return if (detail.isNullOrBlank()) {
            "授权已失效（HTTP 401），请重新登录"
        } else {
            "授权已失效（HTTP 401）：${detail.take(200)}"
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MIN_TIMEOUT_MS = 5_000L
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val READ_TIMEOUT_SECONDS = 300L
        const val WRITE_TIMEOUT_SECONDS = 60L

        const val HTTP_ACCEPTED = 202
        const val HTTP_NO_CONTENT = 204
        const val HTTP_UNAUTHORIZED = 401

        const val HEADER_SESSION_ID = "mcp-session-id"
        const val HEADER_PROTOCOL_VERSION = "mcp-protocol-version"
        const val ACCEPT = "application/json, text/event-stream"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val RESERVED_HEADERS = setOf(
            "host", "content-length", "content-type", "accept", "authorization", "mcp-session-id"
        )
    }
}
