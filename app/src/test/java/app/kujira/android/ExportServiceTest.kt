package app.kujira.android

import androidx.test.core.app.ApplicationProvider
import app.kujira.android.core.SettingsStore
import app.kujira.android.data.ExportService
import app.kujira.android.data.db.AppDatabase
import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Conversation
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExportServiceTest {

    @Test
    fun exportImportRoundTrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = AppDatabase(context)
        val settings = SettingsStore(context)
        val service = ExportService(database, settings)

        val conversation = Conversation(title = "服务器排障")
        database.upsertConversation(conversation)
        database.insertMessage(
            Message(
                conversationId = conversation.id,
                role = Role.USER,
                blocks = listOf(ContentBlock.Text("看看磁盘"))
            )
        )
        database.insertMessage(
            Message(
                conversationId = conversation.id,
                role = Role.ASSISTANT,
                blocks = listOf(
                    ContentBlock.Text("磁盘正常。"),
                    ContentBlock.ToolCall(
                        id = "call-1",
                        name = "sentinel_exec",
                        argumentsJson = """{"command":"df -h"}""",
                        pluginId = "sentinelx",
                        pluginName = "SentinelX"
                    )
                )
            )
        )

        val payload = service.exportJson()
        assertTrue("export mentions the conversation", payload.contains("服务器排障"))
        assertTrue("export keeps tool calls", payload.contains("sentinel_exec"))
        assertTrue("export never contains api keys", !payload.contains("test-key"))

        database.clearAll()
        assertEquals(0, database.conversations.value.size)

        val result = service.importJson(payload)
        assertEquals(1, result.conversations)
        assertEquals(2, result.messages)

        val restored = database.messagesOf(conversation.id)
        assertEquals(2, restored.size)
        assertEquals("看看磁盘", restored[0].text)
        assertEquals("磁盘正常。", restored[1].text)
        assertEquals(2, restored[1].blocks.size)
    }
}
