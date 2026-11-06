package app.kujira.android.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import app.kujira.android.data.model.Conversation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppDatabaseMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase("kujira.db")
    }

    @After
    fun tearDown() {
        context.deleteDatabase("kujira.db")
    }

    @Test
    fun `upgrade from v1 preserves conversations and adds summary columns`() = runBlocking {
        LegacyV1Database(context).writableDatabase.use { db ->
            db.insert(
                "conversations",
                null,
                ContentValues().apply {
                    put("id", "old")
                    put("title", "旧会话")
                    put("created_at", 1000L)
                    put("updated_at", 2000L)
                    put("pinned", 0)
                    put("archived", 0)
                }
            )
        }

        val database = AppDatabase(context)
        val old = database.getConversation("old")!!
        assertEquals("旧会话", old.title)
        assertNull(old.contextSummary)

        database.upsertConversation(
            old.copy(
                contextSummary = "较早对话的摘要",
                contextSummaryThrough = "message-42"
            )
        )
        val reopened = database.getConversation("old")!!
        assertEquals("较早对话的摘要", reopened.contextSummary)
        assertEquals("message-42", reopened.contextSummaryThrough)
        database.close()
    }

    private class LegacyV1Database(context: Context) :
        SQLiteOpenHelper(context, "kujira.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE conversations (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    pinned INTEGER NOT NULL DEFAULT 0,
                    provider_id TEXT,
                    model_id TEXT,
                    system_prompt TEXT,
                    archived INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE messages (
                    id TEXT PRIMARY KEY NOT NULL,
                    conversation_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    blocks_json TEXT NOT NULL DEFAULT '[]',
                    created_at INTEGER NOT NULL,
                    provider_id TEXT,
                    model_id TEXT,
                    status TEXT NOT NULL DEFAULT 'COMPLETE',
                    error TEXT,
                    usage_json TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE tool_audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id TEXT NOT NULL,
                    plugin_id TEXT NOT NULL,
                    plugin_name TEXT NOT NULL,
                    tool_name TEXT NOT NULL,
                    arguments_json TEXT NOT NULL,
                    result_preview TEXT NOT NULL,
                    is_error INTEGER NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
