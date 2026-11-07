package app.kujira.android.net.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** No SSE event for this long (comments/keep-alives do not count) → the stream is considered stuck. */
internal const val STREAM_IDLE_TIMEOUT_MS = 150_000L

/**
 * Once the model has reported a finish reason, only a trailing usage chunk can
 * still follow. If the gateway then neither closes the stream nor sends
 * `[DONE]`, wait this long and treat the answer as complete.
 */
internal const val STREAM_FINISHED_GRACE_MS = 4_000L

/** Absolute grace period after the first finish reason, in [System.nanoTime] units. */
internal const val STREAM_FINISHED_GRACE_NANOS = STREAM_FINISHED_GRACE_MS * 1_000_000L

private const val WATCHDOG_TICK_MS = 1_000L

/**
 * One dispatched `text/event-stream` event.
 *
 * [event] is the SSE `event:` field (`null` when the server only sends `data:`),
 * [data] is the `data:` field with multi-line values joined by `\n`.
 */
internal data class SseEvent(
    val event: String?,
    val data: String,
    val id: String? = null,
) {
    /** OpenAI-compatible providers terminate the stream with `data: [DONE]`. */
    val isDone: Boolean get() = data.trim() == "[DONE]"
}

/**
 * Incremental SSE parser.
 *
 * Feed arbitrary chunks with [feed] — incomplete trailing lines are buffered
 * until their newline arrives, so chunk boundaries never split a line. Call
 * [finish] once the connection ends to flush an event that was not followed by
 * a blank line (some gateways drop the final delimiter).
 *
 * Implements the WHATWG event-stream rules the providers rely on:
 * comments (`:` lines) are ignored, field values lose one optional leading
 * space, multiple `data:` lines are joined with `\n`, and an `event:` name is
 * attached to the next dispatched event.
 */
internal class SseParser {

    private val pendingLine = StringBuilder()
    private val data = StringBuilder()
    private var eventName: String? = null
    private var lastEventId: String? = null
    private var hasData = false

    /** Consumes a chunk of decoded text and returns every event completed by it. */
    fun feed(chunk: String): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        for (ch in chunk) {
            if (ch == '\n') {
                val line = pendingLine.toString().removeSuffix("\r")
                pendingLine.setLength(0)
                acceptLine(line, out)
            } else {
                pendingLine.append(ch)
            }
        }
        return out
    }

    /** Feeds one complete line (without its newline). */
    fun acceptLine(line: String): SseEvent? {
        val out = mutableListOf<SseEvent>()
        acceptLine(line, out)
        return out.firstOrNull()
    }

    /** Flushes the parser at end-of-stream. */
    fun finish(): SseEvent? {
        val out = mutableListOf<SseEvent>()
        if (pendingLine.isNotEmpty()) {
            acceptLine(pendingLine.toString().removeSuffix("\r"), out)
            pendingLine.setLength(0)
        }
        dispatch(out)
        return out.firstOrNull()
    }

    private fun acceptLine(line: String, out: MutableList<SseEvent>) {
        if (line.isEmpty()) {
            dispatch(out)
            return
        }
        // Comment / keep-alive.
        if (line.startsWith(":")) return
        val colon = line.indexOf(':')
        val field: String
        val rawValue: String
        if (colon < 0) {
            field = line
            rawValue = ""
        } else {
            field = line.substring(0, colon)
            rawValue = line.substring(colon + 1).let { if (it.startsWith(" ")) it.substring(1) else it }
        }
        when (field) {
            "data" -> {
                if (hasData) data.append('\n')
                data.append(rawValue)
                hasData = true
            }

            "event" -> eventName = rawValue
            "id" -> if (!rawValue.contains('\u0000')) lastEventId = rawValue
            // "retry" and unknown fields are irrelevant for one-shot streams.
            else -> Unit
        }
    }

    private fun dispatch(out: MutableList<SseEvent>) {
        if (hasData) {
            out += SseEvent(event = eventName, data = data.toString(), id = lastEventId)
        }
        data.setLength(0)
        hasData = false
        eventName = null
    }
}

/** The stream stayed silent (no real SSE event) for [idleMs]; the call was aborted. */
internal class SseIdleTimeoutException(val idleMs: Long) : IOException("stream idle for ${idleMs}ms")

/**
 * Opens an HTTP request and streams its SSE events.
 *
 * All blocking OkHttp work runs in a child coroutine on [Dispatchers.IO], so
 * the surrounding flow can be cancelled at any time: `awaitClose` calls
 * `call.cancel()`, which breaks the blocking `execute()` / line reads and
 * closes the socket. Non-2xx responses are converted into [LlmHttpException]
 * with a friendly Chinese message; transport failures surface as [IOException].
 *
 * Idle watchdog: OkHttp's read timeout is reset by *any* bytes, including the
 * `: keep-alive` comments many gateways (OpenRouter, proxies) send while the
 * model is stuck or queued, so a stalled request could wait forever behind a
 * "正在思考…" placeholder. The watchdog only counts real events (comments and
 * Anthropic `ping`s excluded); when none arrives for [idleTimeoutMs] it cancels
 * the call and fails the flow with [SseIdleTimeoutException]. [idleTimeoutMs]
 * is re-evaluated every second so a client can shorten it once the answer is
 * known to be complete.
 *
 * [hardDeadlineNanos] is an absolute [System.nanoTime] deadline. Once it is
 * reached the stream is closed normally — even if the gateway keeps sending
 * (for example repeated usage chunks after `finish_reason`). This is what makes
 * the "finished grace period" a real deadline instead of another idle timeout.
 *
 * [lastActivityNanos] lets a protocol client supply a stricter definition of
 * "progress" than "any SSE frame". OpenAI-compatible gateways commonly send
 * `data: {"choices":[]}` heartbeats; those keep the socket warm but are not
 * progress. When provided, this clock replaces the raw-frame clock.
 */
internal class SseTransport(private val callFactory: Call.Factory) {

    fun open(
        request: Request,
        idleTimeoutMs: () -> Long = { STREAM_IDLE_TIMEOUT_MS },
        hardDeadlineNanos: () -> Long? = { null },
        lastActivityNanos: (() -> Long)? = null,
    ): Flow<SseEvent> = channelFlow {
        val call = callFactory.newCall(request)
        val lastActivity = AtomicLong(System.nanoTime())
        val idleFiredMs = AtomicLong(0L)

        launch(Dispatchers.IO) {
            var response: Response? = null
            try {
                response = call.execute()
                lastActivity.set(System.nanoTime())
                if (!response.isSuccessful) {
                    val rawBody = runCatching { response.body?.string() }.getOrNull()
                    throw LlmHttpException(
                        code = response.code,
                        rawBody = rawBody,
                        message = LlmErrors.friendlyHttpMessage(response.code, rawBody)
                    )
                }
                val source = response.body?.source()
                if (source == null) {
                    close()
                    return@launch
                }
                val parser = SseParser()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    for (event in parser.feed(line + "\n")) {
                        if (event.event != "ping") lastActivity.set(System.nanoTime())
                        send(event)
                    }
                }
                parser.finish()?.let { send(it) }
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A watchdog-triggered cancel surfaces here as IOException("Canceled"):
                // report the real reason instead.
                val idle = idleFiredMs.get()
                close(if (idle > 0L) SseIdleTimeoutException(idle) else e)
            } finally {
                response?.close()
            }
        }

        val watchdog = launch(Dispatchers.Default) {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val deadline = hardDeadlineNanos()
                if (deadline != null && System.nanoTime() >= deadline) {
                    // The answer is already complete; stop reading instead of
                    // waiting for a [DONE]/socket close that may never arrive.
                    // Closing normally (not cancelling) lets the client emit the
                    // accumulated answer as a successful finish.
                    close()
                    break
                }
                val lastProgress = lastActivityNanos?.invoke() ?: lastActivity.get()
                val idleForMs = (System.nanoTime() - lastProgress) / 1_000_000L
                if (idleForMs > idleTimeoutMs()) {
                    idleFiredMs.set(idleForMs.coerceAtLeast(1L))
                    call.cancel()
                    break
                }
            }
        }

        awaitClose {
            watchdog.cancel()
            call.cancel()
        }
    }
}
