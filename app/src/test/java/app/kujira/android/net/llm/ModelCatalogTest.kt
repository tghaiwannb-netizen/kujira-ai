package app.kujira.android.net.llm

import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ModelCatalogTest {

    private lateinit var server: HttpServer
    private val pool = Executors.newCachedThreadPool()
    private val client = OkHttpClient.Builder()
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

    @Test
    fun `parses openai data array`() {
        val root = LlmJson.parseToJsonElement(
            """{"object":"list","data":[{"id":"gpt-4.1","object":"model"},{"id":"gpt-5.4"}]}"""
        )
        val models = ModelCatalogClient.parseModelCatalog(root)
        assertEquals(listOf("gpt-4.1", "gpt-5.4"), models.map { it.id })
        assertNull(models[0].contextWindow)
        assertNull(models[0].supportsTools)
    }

    @Test
    fun `parses opencode models dev metadata`() {
        val root = LlmJson.parseToJsonElement(
            """
            {
              "data": [
                {
                  "id": "deepseek-v4.1-flash",
                  "name": "DeepSeek V4.1 Flash",
                  "limit": {"context": 1000000, "output": 384000},
                  "capabilities": {
                    "tools": {"supported": true},
                    "reasoning": {"supported": true},
                    "temperature": false
                  },
                  "modalities": {"input": ["text", "image/png"], "output": ["text"]}
                },
                {
                  "id": "qwen3-coder",
                  "limit": {"context": 262144, "output": 65536},
                  "capabilities": {"tools": {"supported": true}, "reasoning": {"supported": false}},
                  "modalities": {"input": ["text"], "output": ["text"]}
                }
              ]
            }
            """.trimIndent()
        )
        val models = ModelCatalogClient.parseModelCatalog(root)
        assertEquals(2, models.size)
        val flash = models[0]
        assertEquals("DeepSeek V4.1 Flash", flash.displayName)
        assertEquals(1_000_000, flash.contextWindow)
        assertEquals(384_000, flash.maxOutputTokens)
        assertEquals(true, flash.supportsTools)
        assertEquals(true, flash.supportsVision)
        assertEquals(true, flash.supportsReasoning)
        assertEquals(false, flash.supportsTemperature)
        assertEquals(false, models[1].supportsVision)
        assertEquals(false, models[1].supportsReasoning)
    }

    @Test
    fun `parses map keyed by model id and skips non-chat models`() {
        val root = LlmJson.parseToJsonElement(
            """
            {
              "models": {
                "glm-5.3": {"name": "GLM-5.3", "tool_call": true, "reasoning": true},
                "text-embedding-3": {"type": "embedding"},
                "image-1": {"type": "image"}
              }
            }
            """.trimIndent()
        )
        val models = ModelCatalogClient.parseModelCatalog(root)
        assertEquals(listOf("glm-5.3"), models.map { it.id })
        assertEquals("GLM-5.3", models.single().displayName)
        assertEquals(true, models.single().supportsReasoning)
    }

    @Test
    fun `parses openrouter capabilities and architecture`() {
        val root = LlmJson.parseToJsonElement(
            """
            {
              "data": [{
                "id": "anthropic/claude-sonnet-4.6",
                "name": "Anthropic: Claude Sonnet 4.6",
                "context_length": 1000000,
                "architecture": {
                  "input_modalities": ["text", "image"],
                  "output_modalities": ["text"]
                },
                "top_provider": {"max_completion_tokens": 64000},
                "supported_parameters": ["tools", "tool_choice", "temperature", "reasoning"]
              }]
            }
            """.trimIndent()
        )
        val model = ModelCatalogClient.parseModelCatalog(root).single()
        assertEquals(1_000_000, model.contextWindow)
        assertEquals(64_000, model.maxOutputTokens)
        assertEquals(true, model.supportsTools)
        assertEquals(true, model.supportsVision)
        assertEquals(true, model.supportsReasoning)
        assertEquals(true, model.supportsTemperature)
    }

    @Test
    fun `merge preserves custom display name and known capabilities`() {
        val existing = listOf(
            ModelInfo(
                id = "deepseek-v4.1-flash",
                displayName = "我的 V4.1",
                contextWindow = 999,
                supportsReasoning = true,
                supportsVision = true,
                maxOutputTokens = 123
            )
        )
        val remote = listOf(
            CatalogModelInfo(
                id = "deepseek-v4.1-flash",
                displayName = "DeepSeek V4.1 Flash",
                contextWindow = 1_000_000,
                supportsTools = true
            ),
            CatalogModelInfo(id = "kimi-k3", displayName = "Kimi K3")
        )

        val merged = ModelCatalogClient.mergeModels(existing, remote)
        assertEquals(2, merged.size)
        val flash = merged.first { it.id == "deepseek-v4.1-flash" }
        assertEquals("我的 V4.1", flash.displayName)
        assertEquals(1_000_000, flash.contextWindow)
        assertEquals(123, flash.maxOutputTokens)
        assertTrue(flash.supportsReasoning)
        assertTrue(flash.supportsVision)
        assertEquals("Kimi K3", merged.first { it.id == "kimi-k3" }.displayName)
    }

    @Test
    fun `opencode endpoints resolve to the shared catalogue`() {
        val catalog = ModelCatalogClient(OkHttpClient())
        val zen = ProviderConfig(
            id = "opencode-zen",
            kind = ProviderKind.OPENCODE_ZEN,
            name = "OpenCode Zen",
            baseUrl = "https://opencode.ai/inference/openai/v1"
        )
        val go = zen.copy(
            id = "opencode-go",
            kind = ProviderKind.OPENCODE_GO,
            baseUrl = "https://opencode.ai/inference/go/openai/v1"
        )
        val custom = zen.copy(
            kind = ProviderKind.OPENAI,
            baseUrl = "http://127.0.0.1:11434/v1/",
            modelsUrl = null
        )

        assertEquals("https://opencode.ai/inference/v1/models", catalog.modelsEndpoint(zen))
        assertEquals("https://opencode.ai/inference/v1/models", catalog.modelsEndpoint(go))
        assertEquals("http://127.0.0.1:11434/v1/models", catalog.modelsEndpoint(custom))
        assertEquals(
            "https://example.com/custom-models",
            catalog.modelsEndpoint(custom.copy(modelsUrl = "https://example.com/custom-models"))
        )
    }

    @Test
    fun `fetches catalogue with bearer auth and opencode session header`() = runBlocking {
        val authorization = AtomicReference<String?>()
        val session = AtomicReference<String?>()
        val path = AtomicReference<String>()
        server.createContext("/v1/models") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            session.set(exchange.requestHeaders.getFirst("x-opencode-session"))
            path.set(exchange.requestURI.path)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use {
                it.write("""{"data":[{"id":"kimi-k3","name":"Kimi K3"}]}""".toByteArray())
            }
        }

        val provider = ProviderConfig(
            id = "opencode-go",
            kind = ProviderKind.OPENCODE_GO,
            name = "OpenCode Go",
            baseUrl = "$baseUrl/openai/v1",
            modelsUrl = "$baseUrl/v1/models"
        )
        val result = ModelCatalogClient(client).fetch(provider, "oc_sk_test")

        assertEquals("/v1/models", path.get())
        assertEquals("Bearer oc_sk_test", authorization.get())
        assertEquals("kujira-models", session.get())
        assertEquals("kimi-k3", result.models.single().id)
        assertTrue(result.sourceUrl.endsWith("/v1/models"))
    }

    @Test
    fun `http errors become friendly messages`() = runBlocking {
        server.createContext("/v1/models") { exchange ->
            val body = """{"error":{"message":"bad key"}}""".toByteArray()
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val provider = ProviderConfig(
            id = "p",
            kind = ProviderKind.OPENAI,
            name = "Test",
            baseUrl = baseUrl,
            modelsUrl = "$baseUrl/v1/models"
        )

        val error = runCatching {
            ModelCatalogClient(client).fetch(provider, "sk-wrong")
        }.exceptionOrNull()

        assertTrue(error is ModelCatalogException)
        assertEquals(401, (error as ModelCatalogException).httpCode)
        assertTrue(error.message.orEmpty().contains("API Key"))
        assertFalse(error.message.orEmpty().isBlank())
    }
}
