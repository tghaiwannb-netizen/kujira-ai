package app.kujira.android

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.kujira.android.data.model.MessageStatus
import app.kujira.android.data.model.Role
import app.kujira.android.ui.chat.ChatViewModel
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

/**
 * End-to-end chat flow against a loopback SSE server: ChatViewModel ->
 * ChatEngine -> OpenAI-compatible client -> state + SQLite persistence.
 * This is the closest thing to a real conversation we can run on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = KujiraApplication::class)
class ChatFlowE2ETest {

    private lateinit var server: HttpServer
    private var baseUrl = ""
    private var requests = mutableListOf<String>()

    @Before
    fun startFakeProvider() {
        requests = mutableListOf()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            requests.add(body)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                fun frame(payload: String) {
                    out.write("data: $payload\n\n".toByteArray())
                    out.flush()
                }
                frame("""{"id":"chatcmpl-1","choices":[{"index":0,"delta":{"role":"assistant","content":"你好"}}]}""")
                frame("""{"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"，我是 Kujira"}}]}""")
                frame("""{"id":"chatcmpl-1","choices":[{"index":0,"delta":{"content":"。"},"finish_reason":"stop"}]}""")
                frame("""{"id":"chatcmpl-1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""")
                frame("[DONE]")
            }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}/v1"
    }

    @After
    fun stopFakeProvider() {
        server.stop(0)
    }

    @Test
    fun sendStreamsReplyAndPersists() {
        val app = ApplicationProvider.getApplicationContext<KujiraApplication>()
        val container = app.container
        val provider = container.providerRepository.activeProvider()!!
        container.providerRepository.save(provider.copy(baseUrl = baseUrl))
        container.providerRepository.saveApiKey(provider.id, "test-key")
        container.providerRepository.setActive(provider.id, provider.defaultModel()!!.id)
        // Keep the MCP layer out of this test (no real network calls).
        container.settingsStore.plugins.value.forEach { plugin ->
            container.settingsStore.upsertPlugin(plugin.copy(enabled = false))
        }
        container.settingsStore.updateSettings { it.copy(pluginsEnabled = false) }

        val viewModel = ChatViewModel(app)
        pump(3000) { viewModel.state.value.activeConversation != null }
        viewModel.newConversation()
        pump(2000) { viewModel.state.value.activeConversation != null }

        viewModel.send("你好")
        pump(15000) {
            val state = viewModel.state.value
            !state.isGenerating && state.messages.any {
                it.role == Role.ASSISTANT && it.status == MessageStatus.COMPLETE
            }
        }

        val state = viewModel.state.value
        assertFalse("still generating", state.isGenerating)
        assertEquals("expected user + assistant", 2, state.messages.size)
        assertEquals(Role.USER, state.messages[0].role)
        assertEquals("你好", state.messages[0].text)
        val answer = state.messages[1]
        assertEquals(Role.ASSISTANT, answer.role)
        assertEquals(MessageStatus.COMPLETE, answer.status)
        assertEquals("你好，我是 Kujira。", answer.text)
        assertEquals(15, answer.usage?.totalTokens)

        // Requests carry the provider-neutral conversation.
        assertEquals(1, requests.size)
        assertTrue(requests[0].contains("\"content\":\"你好\""))
        assertTrue(requests[0].contains("\"stream\":true"))

        // And the transcript is persisted.
        val conversationId = state.activeConversation!!.id
        val stored = runBlocking { container.database.messagesOf(conversationId) }
        assertEquals(2, stored.size)
        assertEquals("你好，我是 Kujira。", stored[1].text)
        // First user message becomes the conversation title.
        pump(500) { true }
        val conversation = runBlocking { container.database.getConversation(conversationId) }
        assertEquals("你好", conversation?.title)
    }

    private fun pump(timeoutMs: Long, condition: () -> Boolean) {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMs) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
    }
}
