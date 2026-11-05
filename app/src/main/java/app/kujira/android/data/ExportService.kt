package app.kujira.android.data

import app.kujira.android.core.SettingsStore
import app.kujira.android.data.db.AppDatabase
import app.kujira.android.data.model.Conversation
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.ProviderConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One conversation plus its transcript, as serialised in an export file. */
@Serializable
data class ConversationBundle(
    val conversation: Conversation,
    val messages: List<Message> = emptyList()
)

/** Portable backup format. API keys are deliberately never included. */
@Serializable
data class KujiraExport(
    val version: Int = 1,
    val app: String = "Kujira",
    val exportedAt: Long = System.currentTimeMillis(),
    val conversations: List<ConversationBundle> = emptyList(),
    val providers: List<ProviderConfig> = emptyList()
)

data class ImportResult(
    val conversations: Int,
    val messages: Int,
    val providersImported: Int,
    val skipped: Int
)

/**
 * JSON export/import of the local transcript (and provider list without keys).
 * The file is written to the app's external files dir and shared via
 * FileProvider from the settings screen.
 */
class ExportService(
    private val database: AppDatabase,
    private val settingsStore: SettingsStore
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun snapshot(): KujiraExport {
        val conversations = database.conversations.value.ifEmpty {
            database.refreshConversations()
            database.conversations.value
        }
        val bundles = conversations.map { conversation ->
            ConversationBundle(
                conversation = conversation,
                messages = database.messagesOf(conversation.id)
            )
        }
        return KujiraExport(
            conversations = bundles,
            providers = settingsStore.providers.value.map { it.copy(secretKey = "") }
        )
    }

    suspend fun exportJson(): String = json.encodeToString(KujiraExport.serializer(), snapshot())

    suspend fun importJson(text: String): ImportResult {
        val bundle = json.decodeFromString(KujiraExport.serializer(), text)
        var importedConversations = 0
        var importedMessages = 0
        var skipped = 0
        for (entry in bundle.conversations) {
            val conversation = entry.conversation
            if (conversation.id.isBlank()) {
                skipped++
                continue
            }
            database.upsertConversation(conversation)
            importedConversations++
            val messages = entry.messages.sortedBy { it.createdAt }
            for (message in messages) {
                database.insertMessage(message)
                importedMessages++
            }
        }
        database.refreshConversations()

        // Providers are imported as configuration only; the user re-enters keys.
        var importedProviders = 0
        val existing = settingsStore.providers.value.associateBy { it.id }
        for (provider in bundle.providers) {
            if (provider.id.isBlank()) continue
            val merged = provider.copy(
                secretKey = existing[provider.id]?.secretKey ?: "provider:${provider.id}"
            )
            settingsStore.upsertProvider(merged)
            importedProviders++
        }
        if (importedProviders > 0) {
            settingsStore.providers.value.firstOrNull { it.id == providerFirstId(bundle) }
                ?.let { } // no-op; selection is left untouched
        }
        return ImportResult(importedConversations, importedMessages, importedProviders, skipped)
    }

    private fun providerFirstId(bundle: KujiraExport): String? =
        bundle.providers.firstOrNull()?.id
}
