package app.kujira.android.net.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicStreamStateTest {

    private fun AnthropicStreamState.event(name: String, payload: String): List<LlmEvent> =
        accept(name, payload)

    @Test
    fun `aggregates text thinking tool use and usage across the event sequence`() {
        val state = AnthropicStreamState()
        val deltas = mutableListOf<LlmEvent>()

        state.event(
            "message_start",
            """{"type":"message_start","message":{"id":"msg_1","role":"assistant","usage":{"input_tokens":12,"output_tokens":1}}}"""
        )
        state.event("ping", """{"type":"ping"}""")

        state.event(
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
        )
        deltas += state.event(
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}"""
        )
        deltas += state.event(
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"，世界"}}"""
        )
        state.event("content_block_stop", """{"type":"content_block_stop","index":0}""")

        state.event(
            "content_block_start",
            """{"type":"content_block_start","index":1,"content_block":{"type":"thinking","thinking":""}}"""
        )
        deltas += state.event(
            "content_block_delta",
            """{"type":"content_block_delta","index":1,"delta":{"type":"thinking_delta","thinking":"让我想想"}}"""
        )
        state.event("content_block_stop", """{"type":"content_block_stop","index":1}""")

        // Tool input arrives as JSON fragments and is parsed only at block stop.
        state.event(
            "content_block_start",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"sentinel_ping","input":{}}}"""
        )
        state.event(
            "content_block_delta",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"host_i"}}"""
        )
        state.event(
            "content_block_delta",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"d\":\"h1\"}"}}"""
        )
        state.event("content_block_stop", """{"type":"content_block_stop","index":2}""")

        state.event(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":42}}"""
        )
        state.event("message_stop", """{"type":"message_stop"}""")

        assertEquals(listOf("你好", "，世界"), deltas.filterIsInstance<LlmEvent.TextDelta>().map { it.text })
        assertEquals(listOf("让我想想"), deltas.filterIsInstance<LlmEvent.ReasoningDelta>().map { it.text })
        assertTrue(state.sawMessageStop)

        val finished = state.finished() as LlmEvent.Finished
        assertEquals("tool_use", finished.finishReason)
        assertEquals(1, finished.toolCalls.size)
        assertEquals("toolu_1", finished.toolCalls[0].id)
        assertEquals("sentinel_ping", finished.toolCalls[0].name)
        assertEquals("""{"host_id":"h1"}""", finished.toolCalls[0].argumentsJson)
        assertEquals(12, finished.usage?.promptTokens)
        assertEquals(42, finished.usage?.completionTokens)
        assertEquals(54, finished.usage?.totalTokens)
    }

    @Test
    fun `parses anthropic prompt cache usage`() {
        val state = AnthropicStreamState()
        state.event(
            "message_start",
            """{"type":"message_start","message":{"usage":{"input_tokens":12,"output_tokens":1,"cache_read_input_tokens":800,"cache_creation_input_tokens":40}}}"""
        )
        state.event(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}"""
        )
        state.event("message_stop", """{"type":"message_stop"}""")

        val usage = (state.finished() as LlmEvent.Finished).usage!!
        assertEquals(800, usage.cacheReadTokens)
        assertEquals(40, usage.cacheWriteTokens)
    }

    @Test
    fun `tool use with start input only and no deltas keeps the start json`() {
        val state = AnthropicStreamState()
        state.event(
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_2","name":"sentinel_state","input":{"host_id":"h2"}}}"""
        )
        state.event("content_block_stop", """{"type":"content_block_stop","index":0}""")

        val finished = state.finished() as LlmEvent.Finished
        assertEquals(1, finished.toolCalls.size)
        assertEquals("""{"host_id":"h2"}""", finished.toolCalls[0].argumentsJson)
    }

    @Test
    fun `tool use without any input becomes an empty object`() {
        val state = AnthropicStreamState()
        state.event(
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_3","name":"sentinel_ping"}}"""
        )
        state.event("content_block_stop", """{"type":"content_block_stop","index":0}""")

        val finished = state.finished() as LlmEvent.Finished
        assertEquals(1, finished.toolCalls.size)
        assertEquals("{}", finished.toolCalls[0].argumentsJson)
    }

    @Test
    fun `two tool blocks produce two calls`() {
        val state = AnthropicStreamState()
        state.event(
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_a","name":"first","input":{}}}"""
        )
        state.event("content_block_stop", """{"type":"content_block_stop","index":0}""")
        state.event(
            "content_block_start",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_b","name":"second","input":{}}}"""
        )
        state.event("content_block_stop", """{"type":"content_block_stop","index":1}""")

        assertEquals(listOf("first", "second"), (state.finished() as LlmEvent.Finished).toolCalls.map { it.name })
    }

    @Test
    fun `usage is absent when no tokens were reported`() {
        val state = AnthropicStreamState()
        assertNull((state.finished() as LlmEvent.Finished).usage)
    }

    @Test
    fun `stream error event is surfaced with retryable flag for overloaded`() {
        val state = AnthropicStreamState()
        val error = runCatching {
            state.event(
                "error",
                """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
            )
        }.exceptionOrNull()

        assertTrue(error is LlmStreamException)
        error as LlmStreamException
        assertTrue(error.retryable)
        assertTrue(error.message!!.contains("Overloaded"))
    }
}
