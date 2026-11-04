package app.kujira.android.net.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class LlmErrorsTest {

    @Test
    fun `401 explains the api key problem and is not retryable`() {
        val failed = LlmEvent.Failed(
            LlmErrors.friendlyHttpMessage(401, """{"error":{"message":"Incorrect API key provided"}}"""),
            httpCode = 401,
            retryable = LlmErrors.isRetryableCode(401)
        )
        assertTrue(failed.message.contains("API Key"))
        assertTrue(failed.message.contains("Incorrect API key provided"))
        assertFalse(failed.retryable)
    }

    @Test
    fun `429 is retryable and mentions rate limits`() {
        val message = LlmErrors.friendlyHttpMessage(429, null)
        assertTrue(message.contains("429"))
        assertTrue(message.contains("频繁") || message.contains("额度"))
        assertTrue(LlmErrors.isRetryableCode(429))
    }

    @Test
    fun `5xx is retryable`() {
        assertEquals(true, LlmErrors.isRetryableCode(500))
        assertEquals(true, LlmErrors.isRetryableCode(503))
        assertEquals(false, LlmErrors.isRetryableCode(404))
        assertEquals(true, LlmErrors.isRetryableCode(408))
        assertEquals(true, LlmErrors.isRetryableCode(null))
    }

    @Test
    fun `400 includes the provider detail`() {
        val message = LlmErrors.friendlyHttpMessage(
            400,
            """{"error":{"message":"tools[0].function.name: invalid"}}"""
        )
        assertTrue(message.contains("400"))
        assertTrue(message.contains("tools[0].function.name: invalid"))
    }

    @Test
    fun `non json error body is still shown`() {
        val message = LlmErrors.friendlyHttpMessage(500, "<html>bad gateway</html>")
        assertTrue(message.contains("bad gateway"))
    }

    @Test
    fun `timeout io failure is retryable and human readable`() {
        val failed = LlmErrors.ioFailure(SocketTimeoutException("timeout"))
        assertTrue(failed.retryable)
        assertTrue(failed.message.contains("超时"))
    }

    @Test
    fun `dns and connection failures get specific messages`() {
        val dns = LlmErrors.ioFailure(UnknownHostException("api.example.com"))
        assertTrue(dns.message.contains("无法解析"))
        assertTrue(dns.retryable)

        val connect = LlmErrors.ioFailure(ConnectException("refused"))
        assertTrue(connect.message.contains("无法连接"))
        assertTrue(connect.retryable)
    }

    @Test
    fun `generic io failure falls back to its message`() {
        val failed = LlmErrors.ioFailure(IOException("stream reset"))
        assertTrue(failed.message.contains("stream reset"))
        assertTrue(failed.retryable)
    }
}
