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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicWireTest {

    private fun provider(baseUrl: String = "https://api.anthropic.com/v1"): ProviderConfig =
        ProviderConfig(
            id = "anthropic",
            kind = ProviderKind.ANTHROPIC,
            name = "Anthropic",
            baseUrl = baseUrl,
            models = listOf(ModelInfo("claude-sonnet-4-6"))
        )

    private fun request(
        messages: List<LlmTurn> = listOf(
            LlmTurn(Role.USER, "你好"),
            LlmTurn(
                role = Role.ASSISTANT,
                text = "我看看",
                toolCalls = listOf(LlmToolCall("toolu_1", "sentinel_ping", """{"host_id":"h1"}"""))
            ),
            LlmTurn(role = Role.TOOL, text = "pong", toolCallId = "toolu_1", toolName = "sentinel_ping")
        ),
        tools: List<LlmToolSpec> = listOf(
            LlmToolSpec(
                name = "sentinel_ping",
                description = "ping a host",
                inputSchemaJson = """{"type":"object","properties":{"host_id":{"type":"string"}}}"""
            )
        )
    ): LlmRequest = LlmRequest(
        provider = provider(),
        apiKey = "sk-ant-test",
        model = "claude-sonnet-4-6",
        systemPrompt = "你是助手",
        messages = messages,
        tools = tools,
        temperature = 0.4,
        topP = 0.8,
        maxTokens = 300
    )

    @Test
    fun `endpoint resolves messages path`() {
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicWire.endpoint("https://api.anthropic.com/v1"))
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicWire.endpoint("https://api.anthropic.com/v1/"))
        assertEquals("https://proxy/messages", AnthropicWire.endpoint("https://proxy/messages"))
        assertThrows(IllegalArgumentException::class.java) { AnthropicWire.endpoint("") }
    }

    @Test
    fun `headers carry api key and version`() {
        val headers = AnthropicWire.headers(request())
        assertEquals("sk-ant-test", headers["x-api-key"])
        assertEquals(AnthropicWire.API_VERSION, headers["anthropic-version"])
        assertEquals("text/event-stream", headers["Accept"])
    }

    @Test
    fun `body hoists the system prompt and converts the round trip`() {
        val body = AnthropicWire.requestBody(request())

        assertEquals("claude-sonnet-4-6", body["model"]!!.jsonPrimitive.content)
        assertEquals(300, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("你是助手", body["system"]!!.jsonPrimitive.content)
        assertEquals(0.4, body["temperature"]!!.jsonPrimitive.double, 0.0001)
        assertEquals(0.8, body["top_p"]!!.jsonPrimitive.double, 0.0001)

        val messages = body["messages"]!!.jsonArray
        assertEquals(3, messages.size)

        val user = messages[0].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        assertEquals(
            "text",
            user["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content
        )

        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val assistantBlocks = assistant["content"]!!.jsonArray
        assertEquals(2, assistantBlocks.size)
        assertEquals("text", assistantBlocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("我看看", assistantBlocks[0].jsonObject["text"]!!.jsonPrimitive.content)
        val toolUse = assistantBlocks[1].jsonObject
        assertEquals("tool_use", toolUse["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", toolUse["id"]!!.jsonPrimitive.content)
        assertEquals("sentinel_ping", toolUse["name"]!!.jsonPrimitive.content)
        assertEquals(
            "h1",
            toolUse["input"]!!.jsonObject["host_id"]!!.jsonPrimitive.content
        )

        val toolResultMessage = messages[2].jsonObject
        assertEquals("user", toolResultMessage["role"]!!.jsonPrimitive.content)
        val toolResult = toolResultMessage["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", toolResult["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", toolResult["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("pong", toolResult["content"]!!.jsonPrimitive.content)

        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("sentinel_ping", tool["name"]!!.jsonPrimitive.content)
        assertEquals(
            "string",
            tool["input_schema"]!!.jsonObject["properties"]!!.jsonObject["host_id"]!!
                .jsonObject["type"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `system turns are merged into the top level system text and excluded from messages`() {
        val body = AnthropicWire.requestBody(
            request(
                messages = listOf(
                    LlmTurn(Role.SYSTEM, "额外系统"),
                    LlmTurn(Role.USER, "hi")
                )
            )
        )
        assertEquals("你是助手\n\n额外系统", body["system"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `consecutive same role turns are merged into one message`() {
        val converted = AnthropicWire.convertMessages(
            listOf(LlmTurn(Role.USER, "a"), LlmTurn(Role.USER, "b"))
        )
        assertEquals(1, converted.size)
        assertEquals(2, converted[0]["content"]!!.jsonArray.size)
    }

    @Test
    fun `leading assistant turn is dropped so the list starts with user`() {
        val converted = AnthropicWire.convertMessages(
            listOf(LlmTurn(Role.ASSISTANT, "orphan"), LlmTurn(Role.USER, "hi"))
        )
        assertEquals(1, converted.size)
        assertEquals("user", converted[0]["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools with invalid names are filtered out`() {
        val body = AnthropicWire.requestBody(
            request(
                tools = listOf(
                    LlmToolSpec("sentinel_ping", "ok", """{"type":"object"}"""),
                    LlmToolSpec("bad.tool.name", "skipped", """{"type":"object"}""")
                )
            )
        )
        val tools = body["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("sentinel_ping", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no tools means no tools field`() {
        val body = AnthropicWire.requestBody(request(tools = emptyList()))
        assertFalse(body.containsKey("tools"))
    }

    @Test
    fun `temperature is clamped to the anthropic range`() {
        val body = AnthropicWire.requestBody(request().copy(temperature = 1.8))
        assertEquals(1.0, body["temperature"]!!.jsonPrimitive.double, 0.0001)
    }

    @Test
    fun `request builder sets url method and headers`() {
        val http = AnthropicWire.httpRequest(request())
        assertEquals("https://api.anthropic.com/v1/messages", http.url.toString())
        assertEquals("POST", http.method)
        assertEquals("sk-ant-test", http.header("x-api-key"))
        assertTrue(http.body != null)
    }
}
