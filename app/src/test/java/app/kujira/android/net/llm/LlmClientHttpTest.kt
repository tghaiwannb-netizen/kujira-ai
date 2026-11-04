package app.kujira.android.net.llm

import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.data.model.Role
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Integration-style tests that drive the real SSE transport and HTTP clients
 * against a loopback HTTP server (JDK built-in, no extra dependencies).
 *
 * This exercises what unit tests of the parsers cannot: async OkHttp calls,
 * response chunk boundaries, headers, HTTP error mapping and cancellation
 * actually closing the socket.
 */
class LlmClientHttpTest {

    private lateinit var server: HttpServer
    private val pool = Executors.newCachedThreadPool()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private var baseUrl: String = ""

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            start()
        }
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
        pool.shutdownNow()
    }

    private fun provider(kind: ProviderKind = ProviderKind.OPENAI) = ProviderConfig(
        id = "test",
        kind = kind,
        name = "Test",
        baseUrl = baseUrl,
        models = listOf(ModelInfo("test-model")),
        defaultModelId = "test-model"
    )

    private fun request(kind: ProviderKind = ProviderKind.OPENAI) = LlmRequest(
        provider = provider(kind),
        apiKey = "sk-test",
        model = "test-model",
        systemPrompt = "be nice",
        messages = listOf(LlmTurn(Role.USER, "hi")),
        temperature = 0.0,
        topP = 1.0,
        maxTokens = 16
    )

    @Test
    fun `openai client streams fragmented sse over real http`() = runBlocking {
        val authorization = AtomicReference<String?>()
        val requestBody = AtomicReference<String>()
        val accept = AtomicReference<String?>()

        server.createContext("/chat/completions") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            accept.set(exchange.requestHeaders.getFirst("Accept"))
            requestBody.set(exchange.requestBody.readBytes().decodeToString())
            exchange.streamSse { out ->
                // Deliberately split one SSE line across two writes: the line and
                // even a JSON token are broken by the chunk boundary.
                write(out, "data: {\"choices\":[{\"delta\":{\"content\":\"你")
                Thread.sleep(15)
                write(
                    out,
                    "\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"想\"},\"finish_reason\":null}]}\n\n"
                )
                Thread.sleep(15)
                write(
                    out,
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7}}\n\n" +
                        "data: [DONE]\n\n"
                )
            }
        }

        val events = withTimeout(10_000) {
            OpenAiCompatibleClient(httpClient).stream(request()).toList()
        }

        assertEquals("Bearer sk-test", authorization.get())
        assertEquals("text/event-stream", accept.get())
        assertTrue(requestBody.get().contains("\"stream\":true"))
        assertTrue(requestBody.get().contains("\"include_usage\":true"))

        assertEquals(listOf("你"), events.filterIsInstance<LlmEvent.TextDelta>().map { it.text })
        assertEquals(listOf("想"), events.filterIsInstance<LlmEvent.ReasoningDelta>().map { it.text })
        val finished = events.last() as LlmEvent.Finished
        assertEquals(3, finished.usage?.promptTokens)
        assertEquals(4, finished.usage?.completionTokens)
        assertEquals(7, finished.usage?.totalTokens)
        assertEquals("stop", finished.finishReason)
        assertEquals(1, events.count { it is LlmEvent.Finished || it is LlmEvent.Failed })
    }

    @Test
    fun `http 401 becomes a friendly failed event`() = runBlocking {
        server.createContext("/chat/completions") { exchange ->
            val body = """{"error":{"message":"Incorrect API key provided"}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val events = withTimeout(10_000) {
            OpenAiCompatibleClient(httpClient).stream(request()).toList()
        }

        val failed = events.single() as LlmEvent.Failed
        assertEquals(401, failed.httpCode)
        assertFalse(failed.retryable)
        assertTrue(failed.message.contains("API Key"))
        assertTrue(failed.message.contains("Incorrect API key provided"))
    }

    @Test
    fun `anthropic client parses a full event stream over real http`() = runBlocking {
        val apiKey = AtomicReference<String?>()
        val version = AtomicReference<String?>()
        val requestBody = AtomicReference<String>()

        server.createContext("/messages") { exchange ->
            apiKey.set(exchange.requestHeaders.getFirst("x-api-key"))
            version.set(exchange.requestHeaders.getFirst("anthropic-version"))
            requestBody.set(exchange.requestBody.readBytes().decodeToString())
            exchange.streamSse { out ->
                write(
                    out,
                    "event: message_start\n" +
                        "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"usage\":{\"input_tokens\":9,\"output_tokens\":1}}}\n\n"
                )
                write(
                    out,
                    "event: content_block_start\n" +
                        "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                )
                write(
                    out,
                    "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}\n\n"
                )
                write(
                    out,
                    "event: content_block_start\n" +
                        "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"sentinel_ping\",\"input\":{}}}\n\n"
                )
                write(
                    out,
                    "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"host_id\\\":\"}}\n\n"
                )
                write(
                    out,
                    "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"h1\\\"}\"}}\n\n"
                )
                write(
                    out,
                    "event: content_block_stop\n" +
                        "data: {\"type\":\"content_block_stop\",\"index\":1}\n\n"
                )
                write(
                    out,
                    "event: message_delta\n" +
                        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":21}}\n\n"
                )
                write(out, "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
            }
        }

        val events = withTimeout(10_000) {
            AnthropicClient(httpClient).stream(request(ProviderKind.ANTHROPIC)).toList()
        }

        assertEquals("sk-test", apiKey.get())
        assertEquals(AnthropicWire.API_VERSION, version.get())
        assertTrue(requestBody.get().contains("\"system\":\"be nice\""))

        assertEquals(listOf("你好"), events.filterIsInstance<LlmEvent.TextDelta>().map { it.text })
        val finished = events.last() as LlmEvent.Finished
        assertEquals("tool_use", finished.finishReason)
        assertEquals("sentinel_ping", finished.toolCalls.single().name)
        assertEquals("""{"host_id":"h1"}""", finished.toolCalls.single().argumentsJson)
        assertEquals(9, finished.usage?.promptTokens)
        assertEquals(21, finished.usage?.completionTokens)
    }

    @Test
    fun `cancelling collection closes the stream instead of hanging`() = runBlocking {
        val closed = AtomicBoolean(false)
        server.createContext("/chat/completions") { exchange ->
            exchange.streamSse { out ->
                try {
                    while (true) {
                        write(out, "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n")
                        Thread.sleep(10)
                    }
                } catch (e: Exception) {
                    closed.set(true)
                }
            }
        }

        val received = withTimeout(5_000) {
            OpenAiCompatibleClient(httpClient).stream(request()).take(3).toList()
        }
        assertEquals(3, received.size)

        // The server-side writer must notice the socket going away quickly.
        val deadline = System.currentTimeMillis() + 3_000
        while (!closed.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("stream should have been cancelled", closed.get())
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun HttpExchange.streamSse(block: (OutputStream) -> Unit) {
        responseHeaders.add("Content-Type", "text/event-stream")
        responseHeaders.add("Cache-Control", "no-cache")
        sendResponseHeaders(200, 0)
        responseBody.use(block)
    }

    private fun write(out: OutputStream, text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }
}
