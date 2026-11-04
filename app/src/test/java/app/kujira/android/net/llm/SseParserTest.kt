package app.kujira.android.net.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the SSE line/event parser — no network, no Android.
 */
class SseParserTest {

    @Test
    fun `parses an event split across arbitrary chunks`() {
        val parser = SseParser()
        val events = mutableListOf<SseEvent>()

        // A single logical line is delivered in three TCP-ish fragments.
        events += parser.feed("event: message_st")
        events += parser.feed("art\ndata: {\"type\":\"mess")
        events += parser.feed("age_start\"}\n\n")

        assertEquals(1, events.size)
        assertEquals("message_start", events[0].event)
        assertEquals("""{"type":"message_start"}""", events[0].data)
    }

    @Test
    fun `joins multiple data lines with a newline`() {
        val parser = SseParser()
        val events = parser.feed("data: line one\ndata: line two\n\n")
        assertEquals(1, events.size)
        assertEquals("line one\nline two", events[0].data)
    }

    @Test
    fun `ignores comments keep-alives and unknown fields`() {
        val parser = SseParser()
        assertEquals(emptyList<SseEvent>(), parser.feed(": keep-alive\n\n"))
        assertEquals(emptyList<SseEvent>(), parser.feed("retry: 3000\nfoo: bar\n\n"))
        val events = parser.feed("data: hello\n\n")
        assertEquals(1, events.size)
        assertEquals("hello", events[0].data)
    }

    @Test
    fun `detects the openai done sentinel`() {
        val parser = SseParser()
        val events = parser.feed("data: [DONE]\n\n")
        assertEquals(1, events.size)
        assertTrue(events[0].isDone)
    }

    @Test
    fun `handles crlf line endings and field spacing`() {
        val parser = SseParser()
        val events = parser.feed("event: message_start\r\ndata:{} \r\n\r\n")
        // "data:{} " keeps the trailing space after the value, only one leading
        // space is stripped per the event-stream spec.
        assertEquals(1, events.size)
        assertEquals("message_start", events[0].event)
        assertEquals("{} ", events[0].data)
    }

    @Test
    fun `dispatches multiple events from one chunk and keeps event names separate`() {
        val parser = SseParser()
        val events = parser.feed(
            "event: a\ndata: 1\n\n" +
                "event: b\ndata: 2\n\n" +
                "data: 3\n\n"
        )
        assertEquals(3, events.size)
        assertEquals("a", events[0].event)
        assertEquals("1", events[0].data)
        assertEquals("b", events[1].event)
        assertEquals("2", events[1].data)
        assertNull(events[2].event)
        assertEquals("3", events[2].data)
    }

    @Test
    fun `finish flushes a trailing event without a blank line`() {
        val parser = SseParser()
        assertEquals(emptyList<SseEvent>(), parser.feed("data: tail"))
        val flushed = parser.finish()
        assertEquals("tail", flushed?.data)
        assertNull(parser.finish())
    }

    @Test
    fun `acceptLine dispatches when the blank delimiter arrives`() {
        val parser = SseParser()
        assertNull(parser.acceptLine("data: x"))
        val event = parser.acceptLine("")
        assertEquals("x", event?.data)
    }
}
