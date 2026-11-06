package app.kujira.android.net.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiStreamStateTest {

    @Test
    fun `aggregates content reasoning finish reason and usage`() {
        val state = OpenAiStreamState()
        val deltas = mutableListOf<LlmEvent>()

        deltas += state.accept("""{"choices":[{"delta":{"role":"assistant","content":"你"},"finish_reason":null}]}""")
        deltas += state.accept("""{"choices":[{"delta":{"content":"好"},"finish_reason":null}]}""")
        deltas += state.accept("""{"choices":[{"delta":{"reasoning_content":"先想一下"},"finish_reason":null}]}""")
        // Usage arrives in a separate chunk whose `choices` array is empty.
        deltas += state.accept("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        deltas += state.accept("""{"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}""")

        assertEquals(listOf("你", "好"), deltas.filterIsInstance<LlmEvent.TextDelta>().map { it.text })
        assertEquals(listOf("先想一下"), deltas.filterIsInstance<LlmEvent.ReasoningDelta>().map { it.text })

        val finished = state.finished() as LlmEvent.Finished
        assertEquals("stop", finished.finishReason)
        assertEquals(11, finished.usage?.promptTokens)
        assertEquals(7, finished.usage?.completionTokens)
        assertEquals(18, finished.usage?.totalTokens)
        assertTrue(finished.toolCalls.isEmpty())
    }

    @Test
    fun `parses opencode cost cache reasoning and billing metadata`() {
        val state = OpenAiStreamState()
        state.accept(
            """
            {
              "choices": [],
              "usage": {
                "prompt_tokens": 1200,
                "completion_tokens": 300,
                "total_tokens": 1500,
                "prompt_tokens_details": {"cached_tokens": 800},
                "completion_tokens_details": {"reasoning_tokens": 120},
                "cost": 0.00412,
                "opencode": {
                  "billing_source": "go",
                  "allowance": 0.003,
                  "tokens": {"cache_write_5m": 10, "cache_write_1h": 20}
                }
              }
            }
            """.trimIndent()
        )

        val usage = (state.finished() as LlmEvent.Finished).usage!!
        assertEquals(1200, usage.promptTokens)
        assertEquals(300, usage.completionTokens)
        assertEquals(800, usage.cacheReadTokens)
        assertEquals(30, usage.cacheWriteTokens)
        assertEquals(120, usage.reasoningTokens)
        assertEquals(0.00412, usage.costUsd!!, 0.0000001)
        assertEquals("go", usage.billingSource)
        assertEquals(0.003, usage.allowanceUsd!!, 0.0000001)
    }

    @Test
    fun `reassembles tool call shards by index`() {
        val state = OpenAiStreamState()

        state.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"sentinel_ping","arguments":""}}]}}]}"""
        )
        state.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"ho"}}]}}]}"""
        )
        state.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"st_id\":\"h1\"}"}}]}}]}"""
        )
        state.accept("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")

        val finished = state.finished() as LlmEvent.Finished
        assertEquals("tool_calls", finished.finishReason)
        assertEquals(1, finished.toolCalls.size)
        assertEquals("call_a", finished.toolCalls[0].id)
        assertEquals("sentinel_ping", finished.toolCalls[0].name)
        assertEquals("""{"host_id":"h1"}""", finished.toolCalls[0].argumentsJson)
    }

    @Test
    fun `keeps multiple parallel tool calls separate and ordered`() {
        val state = OpenAiStreamState()
        state.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_b","function":{"name":"second","arguments":"{}"}},{"index":0,"id":"call_a","function":{"name":"first","arguments":"{\"x\":1}"}}]}}]}"""
        )

        val calls = (state.finished() as LlmEvent.Finished).toolCalls
        assertEquals(listOf("call_a", "call_b"), calls.map { it.id })
        assertEquals(listOf("first", "second"), calls.map { it.name })
        assertEquals("""{"x":1}""", calls[0].argumentsJson)
        assertEquals("{}", calls[1].argumentsJson)
    }

    @Test
    fun `supports legacy single function_call deltas`() {
        val state = OpenAiStreamState()
        state.accept("""{"choices":[{"delta":{"function_call":{"name":"do_thing","arguments":"{\"a\":"}}}]}""")
        state.accept("""{"choices":[{"delta":{"function_call":{"arguments":"1}"}},"finish_reason":"function_call"}]}""")

        val finished = state.finished() as LlmEvent.Finished
        assertEquals(1, finished.toolCalls.size)
        assertEquals("do_thing", finished.toolCalls[0].name)
        assertEquals("""{"a":1}""", finished.toolCalls[0].argumentsJson)
    }

    @Test
    fun `ignores done sentinel and malformed payloads`() {
        val state = OpenAiStreamState()
        assertEquals(emptyList<LlmEvent>(), state.accept("[DONE]"))
        assertEquals(emptyList<LlmEvent>(), state.accept("not json at all"))
        assertEquals(emptyList<LlmEvent>(), state.accept("""{"choices":[]}"""))
        val finished = state.finished() as LlmEvent.Finished
        assertEquals("stop", finished.finishReason)
    }

    @Test
    fun `stream error payload is surfaced`() {
        val state = OpenAiStreamState()
        val error = runCatching {
            state.accept(
                """{"error":{"message":"Incorrect API key provided","type":"authentication_error","code":"invalid_api_key"}}"""
            )
        }.exceptionOrNull()
        assertTrue(error is LlmStreamException)
        assertTrue((error as LlmStreamException).message!!.contains("Incorrect API key"))
    }
}
