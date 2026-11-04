package app.kujira.android.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Conversation
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.MessageStatus
import app.kujira.android.data.model.Role
import app.kujira.android.data.model.ToolAudit
import app.kujira.android.data.model.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Thin, hand-rolled SQLite layer.
 *
 * Messages store their [ContentBlock] list as JSON so the schema stays stable
 * while the block model evolves; conversation and audit tables are relational.
 */
class AppDatabase(context: Context) :
    SQLiteOpenHelper(context, NAME, null, VERSION) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

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
        db.execSQL("CREATE INDEX idx_messages_conv ON messages(conversation_id, created_at)")
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Require an explicit migration rather than silently destroying user data.
        throw IllegalStateException("Missing Kujira DB migration: $oldVersion -> $newVersion")
    }

    // ------------------------------------------------------------------
    // conversations
    // ------------------------------------------------------------------

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    suspend fun refreshConversations() = withContext(Dispatchers.IO) {
        _conversations.value = queryConversations()
    }

    private fun queryConversations(): List<Conversation> {
        val out = mutableListOf<Conversation>()
        readableDatabase.query(
            "conversations", null, null, null, null, null,
            "pinned DESC, updated_at DESC"
        ).use { c ->
            while (c.moveToNext()) out.add(c.toConversation())
        }
        return out
    }

    suspend fun getConversation(id: String): Conversation? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            "conversations", null, "id = ?", arrayOf(id), null, null, null
        ).use { if (it.moveToFirst()) it.toConversation() else null }
    }

    suspend fun upsertConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        writableDatabase.insertWithOnConflict(
            "conversations", null, conversation.toValues(), SQLiteDatabase.CONFLICT_REPLACE
        )
        refreshConversations()
        Unit
    }

    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("conversations", "id = ?", arrayOf(id))
        writableDatabase.delete("messages", "conversation_id = ?", arrayOf(id))
        refreshConversations()
        Unit
    }

    suspend fun searchConversations(query: String): List<Conversation> = withContext(Dispatchers.IO) {
        val like = "%${query.replace("%", "").replace("_", "")}%"
        val out = mutableListOf<Conversation>()
        readableDatabase.rawQuery(
            """
            SELECT DISTINCT c.* FROM conversations c
            LEFT JOIN messages m ON m.conversation_id = c.id
            WHERE c.title LIKE ? OR m.blocks_json LIKE ?
            ORDER BY c.pinned DESC, c.updated_at DESC
            """.trimIndent(),
            arrayOf(like, like)
        ).use { while (it.moveToNext()) out.add(it.toConversation()) }
        out
    }

    // ------------------------------------------------------------------
    // messages
    // ------------------------------------------------------------------

    suspend fun messagesOf(conversationId: String): List<Message> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Message>()
        readableDatabase.query(
            "messages", null, "conversation_id = ?", arrayOf(conversationId),
            null, null, "created_at ASC"
        ).use { c ->
            while (c.moveToNext()) out.add(c.toMessage())
        }
        out
    }

    suspend fun insertMessage(message: Message) = withContext(Dispatchers.IO) {
        writableDatabase.insertWithOnConflict(
            "messages", null, message.toValues(), SQLiteDatabase.CONFLICT_REPLACE
        )
        Unit
    }

    suspend fun updateMessage(message: Message) = insertMessage(message)

    suspend fun deleteMessage(id: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("messages", "id = ?", arrayOf(id))
        Unit
    }

    // ------------------------------------------------------------------
    // tool audit
    // ------------------------------------------------------------------

    suspend fun recordAudit(audit: ToolAudit) = withContext(Dispatchers.IO) {
        writableDatabase.insert("tool_audit", null, ContentValues().apply {
            put("conversation_id", audit.conversationId)
            put("plugin_id", audit.pluginId)
            put("plugin_name", audit.pluginName)
            put("tool_name", audit.toolName)
            put("arguments_json", audit.argumentsJson)
            put("result_preview", audit.resultPreview)
            put("is_error", if (audit.isError) 1 else 0)
            put("duration_ms", audit.durationMs)
            put("created_at", audit.createdAt)
        })
        Unit
    }

    suspend fun audits(limit: Int = 200): List<ToolAudit> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ToolAudit>()
        readableDatabase.query(
            "tool_audit", null, null, null, null, null, "created_at DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ToolAudit(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        conversationId = c.getString(c.getColumnIndexOrThrow("conversation_id")),
                        pluginId = c.getString(c.getColumnIndexOrThrow("plugin_id")),
                        pluginName = c.getString(c.getColumnIndexOrThrow("plugin_name")),
                        toolName = c.getString(c.getColumnIndexOrThrow("tool_name")),
                        argumentsJson = c.getString(c.getColumnIndexOrThrow("arguments_json")),
                        resultPreview = c.getString(c.getColumnIndexOrThrow("result_preview")),
                        isError = c.getInt(c.getColumnIndexOrThrow("is_error")) != 0,
                        durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        out
    }

    suspend fun clearAudits() = withContext(Dispatchers.IO) {
        writableDatabase.delete("tool_audit", null, null)
        Unit
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        writableDatabase.delete("messages", null, null)
        writableDatabase.delete("conversations", null, null)
        writableDatabase.delete("tool_audit", null, null)
        _conversations.value = emptyList()
        Unit
    }

    // ------------------------------------------------------------------
    // mappers
    // ------------------------------------------------------------------

    private fun Conversation.toValues() = ContentValues().apply {
        put("id", id)
        put("title", title)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("pinned", if (pinned) 1 else 0)
        put("provider_id", providerId)
        put("model_id", modelId)
        put("system_prompt", systemPrompt)
        put("archived", if (archived) 1 else 0)
    }

    private fun Message.toValues() = ContentValues().apply {
        put("id", id)
        put("conversation_id", conversationId)
        put("role", role.name)
        put("blocks_json", json.encodeToString(ListSerializer(ContentBlock.serializer()), blocks))
        put("created_at", createdAt)
        put("provider_id", providerId)
        put("model_id", modelId)
        put("status", status.name)
        put("error", error)
        put("usage_json", usage?.let { json.encodeToString(Usage.serializer(), it) })
    }

    private fun Cursor.toConversation() = Conversation(
        id = getString(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        pinned = getInt(getColumnIndexOrThrow("pinned")) != 0,
        providerId = getStringOrNull("provider_id"),
        modelId = getStringOrNull("model_id"),
        systemPrompt = getStringOrNull("system_prompt"),
        archived = getInt(getColumnIndexOrThrow("archived")) != 0
    )

    private fun Cursor.toMessage() = Message(
        id = getString(getColumnIndexOrThrow("id")),
        conversationId = getString(getColumnIndexOrThrow("conversation_id")),
        role = runCatching { Role.valueOf(getString(getColumnIndexOrThrow("role"))) }
            .getOrDefault(Role.ASSISTANT),
        blocks = runCatching {
            json.decodeFromString(
                ListSerializer(ContentBlock.serializer()),
                getString(getColumnIndexOrThrow("blocks_json")) ?: "[]"
            )
        }.getOrDefault(emptyList()),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        providerId = getStringOrNull("provider_id"),
        modelId = getStringOrNull("model_id"),
        status = runCatching { MessageStatus.valueOf(getString(getColumnIndexOrThrow("status"))) }
            .getOrDefault(MessageStatus.COMPLETE),
        error = getStringOrNull("error"),
        usage = getStringOrNull("usage_json")?.let {
            runCatching { json.decodeFromString(Usage.serializer(), it) }.getOrNull()
        }
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    companion object {
        private const val NAME = "kujira.db"
        private const val VERSION = 1
    }
}
