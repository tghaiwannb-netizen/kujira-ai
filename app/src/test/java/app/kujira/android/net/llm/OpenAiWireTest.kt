package app.kujira.android.net.llm

import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.ProviderKind
import app.kujira.android.data.model.Role
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiWireTest {

    private fun provider(
        kind: ProviderKind = ProviderKind.DEEPSEEK,
        baseUrl: String = "https://api.deepseek.com/v1",
    ): ProviderConfig = ProviderConfig(
        id = "p1",
        kind = kind,
        name = "Test",
        baseUrl = baseUrl,
        models = listOf(ModelInfo("deepseek-chat"))
    )

    private fun request(
        provider: ProviderConfig = provider(),
        model: String = "deepseek-chat",
        tools: List<LlmToolSpec> = listOf(
            LlmToolSpec(
                name = "sentinel_ping",
                description = "ping a host",
                inputSchemaJson = """{"type":"object","properties":{"host_id":{"type":"string"}}}"""
            )
        )
    ): LlmRequest = LlmRequest(
        provider = provider,
        apiKey = "sk-test",
        model = model,
        systemPrompt = "你是助手",
        messages = listOf(
            LlmTurn(Role.USER, "你好"),
            LlmTurn(
                role = Role.ASSISTANT,
                text = "我来调用工具",
                toolCalls = listOf(LlmToolCall("c1", "sentinel_ping", """{"host_id":"h1"}"""))
            ),
            LlmTurn(role = Role.TOOL, text = "pong", toolCallId = "c1", toolName = "sentinel_ping")
        ),
        tools = tools,
        temperature = 0.3,
        topP = 0.9,
        maxTokens = 512
    )

    @Test
    fun `endpoint resolves the chat completions path`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            OpenAiWire.endpoint("https://api.deepseek.com/v1")
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            OpenAiWire.endpoint("https://api.deepseek.com/v1/")
        )
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            OpenAiWire.endpoint("http://127.0.0.1:8080/v1/")
        )
        assertEquals(
            "https://proxy.example.com/chat/completions",
            OpenAiWire.endpoint("https://proxy.example.com/chat/completions")
        )
        assertThrows(IllegalArgumentException::class.java) { OpenAiWire.endpoint("   ") }
    }

    @Test
    fun `headers carry bearer auth and sse accept`() {
        val headers = OpenAiWire.headers(request())
        assertEquals("Bearer sk-test", headers["Authorization"])
        assertEquals("text/event-stream", headers["Accept"])
    }

    @Test
    fun `blank api key omits authorization header`() {
        val headers = OpenAiWire.headers(request().copy(apiKey = "  "))
        assertNull(headers["Authorization"])
    }

    @Test
    fun `openrouter gets attribution headers`() {
        val headers = OpenAiWire.headers(request(provider(ProviderKind.OPENROUTER)))
        assertEquals("Kujira", headers["X-Title"])
    }

    @Test
    fun `body contains streaming options messages and tools`() {
        val body = OpenAiWire.requestBody(request())

        assertEquals("deepseek-chat", body["model"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals(
            "true",
            body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content
        )
        assertEquals(0.3, body["temperature"]!!.jsonPrimitive.double, 0.0001)
        assertEquals(0.9, body["top_p"]!!.jsonPrimitive.double, 0.0001)
        assertEquals(512, body["max_tokens"]!!.jsonPrimitive.content.toInt())

        val messages = body["messages"]!!.jsonArray
        assertEquals(4, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("你是助手", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)

        val assistant = messages[2].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        assertEquals("我来调用工具", assistant["content"]!!.jsonPrimitive.content)
        val toolCall = assistant["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("c1", toolCall["id"]!!.jsonPrimitive.content)
        assertEquals("function", toolCall["type"]!!.jsonPrimitive.content)
        assertEquals(
            "sentinel_ping",
            toolCall["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        )
        assertEquals(
            """{"host_id":"h1"}""",
            toolCall["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
        )

        val toolMessage = messages[3].jsonObject
        assertEquals("tool", toolMessage["role"]!!.jsonPrimitive.content)
        assertEquals("c1", toolMessage["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("pong", toolMessage["content"]!!.jsonPrimitive.content)

        val tools = body["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val function = tools[0].jsonObject["function"]!!.jsonObject
        assertEquals("sentinel_ping", function["name"]!!.jsonPrimitive.content)
        assertEquals(
            "string",
            function["parameters"]!!.jsonObject["properties"]!!.jsonObject["host_id"]!!
                .jsonObject["type"]!!.jsonPrimitive.content
        )
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid tool schema falls back to an empty object schema`() {
        val body = OpenAiWire.requestBody(
            request(tools = listOf(LlmToolSpec("broken", "d", "not-a-schema")))
        )
        val parameters = body["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["parameters"]!!
        assertEquals("object", parameters.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reasoning models use max_completion_tokens and omit sampling params`() {
        val body = OpenAiWire.requestBody(request(provider(ProviderKind.OPENAI), model = "gpt-5.1"))

        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
        assertEquals("512", body["max_completion_tokens"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("max_tokens"))
    }

    @Test
    fun `regular openai models keep max_tokens and temperature`() {
        val body = OpenAiWire.requestBody(request(provider(ProviderKind.OPENAI), model = "gpt-4.1"))
        assertTrue(body.containsKey("temperature"))
        assertEquals("512", body["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun `system turns are preserved as system messages`() {
        val body = OpenAiWire.requestBody(
            request().copy(
                systemPrompt = null,
                messages = listOf(LlmTurn(Role.SYSTEM, "额外系统提示"), LlmTurn(Role.USER, "hi"))
            )
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("额外系统提示", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool turn without call id is dropped instead of producing a broken message`() {
        val request = request().copy(
            messages = listOf(LlmTurn(Role.TOOL, text = "orphan", toolCallId = null))
        )
        assertEquals(0, OpenAiWire.messagesArray(request).size)
    }

    @Test
    fun `request builder sets the resolved url and method`() {
        val http = OpenAiWire.httpRequest(request())
        assertEquals("https://api.deepseek.com/v1/chat/completions", http.url.toString())
        assertEquals("POST", http.method)
        assertEquals("Bearer sk-test", http.header("Authorization"))
        assertTrue(http.body != null)
    }
}
