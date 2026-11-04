package app.kujira.android.net.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class LoopbackServerTest {

    private fun httpGet(port: Int, target: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(
                "GET $target HTTP/1.1\r\nHost: localhost:$port\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun `receives the oauth callback and answers with a success page`() = runBlocking {
        val server = LoopbackServer(port = 0)
        server.start()
        try {
            val response = async(Dispatchers.IO) {
                httpGet(server.boundPort, "/oauth/callback?code=the-code&state=the-state")
            }
            val callback = server.awaitCallback(5_000)
            assertEquals("the-code", callback.code)
            assertEquals("the-state", callback.state)
            val http = response.await()
            assertTrue(http.startsWith("HTTP/1.1 200 OK"))
            assertTrue(http.contains("授权完成"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `serves an error callback page`() = runBlocking {
        val server = LoopbackServer(port = 0)
        server.start()
        try {
            val response = async(Dispatchers.IO) {
                httpGet(server.boundPort, "/oauth/callback?error=access_denied&error_description=nope")
            }
            val callback = server.awaitCallback(5_000)
            assertEquals("access_denied", callback.error)
            assertEquals("nope", callback.errorDescription)
            assertTrue(response.await().contains("HTTP/1.1 400"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `ignores unrelated paths and keeps waiting for the callback`() = runBlocking<Unit> {
        val server = LoopbackServer(port = 0)
        server.start()
        try {
            // Both requests are sent from one coroutine, in order, while the
            // accept loop runs in awaitCallback: first a 404, then the callback.
            val requests = async(Dispatchers.IO) {
                val favicon = httpGet(server.boundPort, "/favicon.ico")
                val callback = httpGet(server.boundPort, "/oauth/callback?code=c2&state=s2")
                favicon to callback
            }
            val result = async(Dispatchers.IO) { server.awaitCallback(5_000) }
            val (faviconResponse, callbackResponse) = requests.await()
            val callback = result.await()
            assertEquals("c2", callback.code)
            assertTrue(faviconResponse.contains("HTTP/1.1 404"))
            assertTrue(callbackResponse.contains("HTTP/1.1 200"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `reports a busy port as a readable error`() {
        val blocker = ServerSocket()
        blocker.reuseAddress = true
        blocker.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        try {
            val server = LoopbackServer(port = blocker.localPort)
            try {
                server.start()
                fail("expected McpException")
            } catch (expected: McpException) {
                assertTrue(expected.message!!.contains("监听授权回调"))
            }
        } finally {
            blocker.close()
        }
    }

    @Test
    fun `timeout is reported`() = runBlocking {
        val server = LoopbackServer(port = 0)
        server.start()
        try {
            try {
                server.awaitCallback(1_100)
                fail("expected McpException")
            } catch (expected: McpException) {
                assertTrue(expected.message!!.contains("授权超时"))
            }
        } finally {
            server.stop()
        }
    }
}
