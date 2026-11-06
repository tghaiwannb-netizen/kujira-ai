package app.kujira.android.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.kujira.android.KujiraApplication
import app.kujira.android.data.model.AppSettings
import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Conversation
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.MessageStatus
import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.Role
import app.kujira.android.data.model.ToolDescriptor
import app.kujira.android.data.model.ToolPolicy
import app.kujira.android.domain.ChatEvent
import app.kujira.android.domain.ChatRequest
import app.kujira.android.domain.ContextCompactionException
import app.kujira.android.domain.ContextUsage
import app.kujira.android.domain.ConversationContext
import app.kujira.android.domain.TokenEstimator
import app.kujira.android.domain.ToolConfirmer
import app.kujira.android.domain.mergeToolResults
import app.kujira.android.domain.sanitizeHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A tool call parked until the user taps allow/deny. */
data class ToolConfirmationRequest(
    val toolName: String,
    val pluginName: String,
    val argumentsJson: String,
    val dangerous: Boolean
)

data class ChatUiState(
    val conversations: List<Conversation> = emptyList(),
    val activeConversation: Conversation? = null,
    /** Transcript of [activeConversation] only. */
    val messages: List<Message> = emptyList(),
    /** True while the *open* conversation is generating. */
    val isGenerating: Boolean = false,
    /** Every conversation that currently has a generation in flight. */
    val generatingIds: Set<String> = emptySet(),
    val error: String? = null,
    val modelLabel: String = "",
    val pluginsLabel: String? = null,
    val pluginsHealthy: Boolean = true,
    val showReasoning: Boolean = true,
    /** Confirmation request of the open conversation, if it is waiting for one. */
    val pendingTool: ToolConfirmationRequest? = null,
    val pendingToolCallId: String? = null,
    val sending: Boolean = false,
    /** True while the open conversation is compacting its context. */
    val compacting: Boolean = false,
    val contextUsage: ContextUsage = ContextUsage()
)

/**
 * Chat screen state holder: conversation selection/persistence, the streaming
 * loops, and the tool-confirmation handshake with the UI.
 *
 * Concurrency model: every conversation owns an independent [Run] (job,
 * streaming placeholder, pending tool confirmation). Starting, stopping or
 * failing one run never touches another, and switching the open conversation
 * only changes which run the UI mirrors. All state is mutated on the main
 * thread (viewModelScope), so no locking is needed.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as KujiraApplication).container
    private val db = container.database
    private val settingsStore = container.settingsStore
    private val providerRepo = container.providerRepository
    private val engine = container.chatEngine
    private val compactor = container.contextCompactor
    private val mcp = container.mcpManager
    private val gated = container.gatedToolExecutor

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** One in-flight generation. */
    private class Run(
        var conversation: Conversation,
        val streamingId: String
    ) {
        var job: Job? = null
        var pendingConfirm: CompletableDeferred<Boolean>? = null
        var pendingTool: ToolConfirmationRequest? = null
        var pendingToolCallId: String? = null
        var compacting: Boolean = false

        /** Set when the conversation was deleted mid-flight: persist nothing. */
        var discard: Boolean = false
    }

    private val runs = LinkedHashMap<String, Run>()

    /** Live transcripts of conversations that have a run (kept while they are not on screen). */
    private val transcripts = HashMap<String, List<Message>>()

    /** Unsent composer text per conversation. */
    private val drafts = HashMap<String, String>()

    private var activeConversationId: String? = null

    init {
        gated.confirmer = ToolConfirmer { tool, args -> awaitConfirmation(tool, args) }

        viewModelScope.launch {
            db.refreshConversations()
            db.conversations.collect { list -> _state.update { it.copy(conversations = list) } }
        }
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _state.update { it.copy(showReasoning = settings.showReasoning) }
                refreshLabels()
            }
        }
        viewModelScope.launch {
            settingsStore.plugins.collect { refreshLabels() }
        }
        viewModelScope.launch {
            val lastId = settingsStore.settings.value.lastConversationId
            val existing = lastId?.let { db.getConversation(it) }
            if (existing != null) openConversation(existing) else newConversation(persist = false)
            mcp.connectEnabled()
            refreshLabels()
            container.pendingPrompt?.let { prompt ->
                container.pendingPrompt = null
                send(prompt)
            }
        }
    }

    // ------------------------------------------------------------------
    // conversations
    // ------------------------------------------------------------------

    fun newConversation(persist: Boolean = true) {
        // Tapping "new" on an already-empty, idle conversation would only pile up
        // blank rows in the history, so it is a no-op.
        val current = _state.value
        val active = current.activeConversation
        if (persist && active != null && current.messages.isEmpty() && !runs.containsKey(active.id)) return

        // State flips synchronously so a message sent immediately after this
        // call still lands in the new conversation; the DB write is async.
        val conversation = Conversation(
            title = "",
            providerId = providerRepo.settings.value.activeProviderId,
            modelId = providerRepo.activeModelId()
        )
        activeConversationId = conversation.id
        _state.update {
            it.copy(activeConversation = conversation, messages = emptyList(), error = null)
        }
        publishRunState()
        refreshContextUsage()
        settingsStore.updateSettings { it.copy(lastConversationId = conversation.id) }
        if (persist) {
            viewModelScope.launch { db.upsertConversation(conversation) }
        }
    }

    fun selectConversation(id: String) {
        if (_state.value.activeConversation?.id == id) return
        viewModelScope.launch {
            val conversation = db.getConversation(id) ?: return@launch
            openConversation(conversation)
        }
    }

    private suspend fun openConversation(conversation: Conversation) {
        activeConversationId = conversation.id
        settingsStore.updateSettings { it.copy(lastConversationId = conversation.id) }

        // A conversation that is still generating is shown from its live
        // transcript (the DB does not have the streaming turn yet).
        val live = if (runs.containsKey(conversation.id)) transcripts[conversation.id] else null
        val messages = mergeToolResults(live ?: db.messagesOf(conversation.id))

        // The user may have tapped another conversation while we were loading.
        if (activeConversationId != conversation.id) return

        val latest = mergeToolResults(
            if (runs.containsKey(conversation.id)) transcripts[conversation.id] ?: messages else messages
        )
        _state.update {
            it.copy(activeConversation = conversation, messages = latest, error = null)
        }
        publishRunState()
        refreshContextUsage()
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch {
            val conversation = db.getConversation(id) ?: return@launch
            val updated = conversation.copy(title = title.trim(), updatedAt = System.currentTimeMillis())
            db.upsertConversation(updated)
            runs[id]?.conversation = updated
            if (_state.value.activeConversation?.id == id) {
                _state.update { it.copy(activeConversation = updated) }
            }
        }
    }

    fun deleteConversation(id: String) {
        // Stop its generation first and make sure it cannot write the
        // conversation back to the database while it winds down.
        runs[id]?.let { run ->
            run.discard = true
            run.job?.cancel()
        }
        drafts.remove(id)
        viewModelScope.launch {
            db.deleteConversation(id)
            if (_state.value.activeConversation?.id == id) {
                val next = _state.value.conversations.firstOrNull { it.id != id }
                if (next != null) selectConversation(next.id) else newConversation(persist = false)
            }
        }
    }

    fun togglePin(id: String) {
        viewModelScope.launch {
            val conversation = db.getConversation(id) ?: return@launch
            db.upsertConversation(conversation.copy(pinned = !conversation.pinned))
        }
    }

    // ------------------------------------------------------------------
    // drafts
    // ------------------------------------------------------------------

    fun draftFor(conversationId: String?): String =
        conversationId?.let { drafts[it] }.orEmpty()

    fun saveDraft(conversationId: String?, text: String) {
        if (conversationId == null) return
        if (text.isEmpty()) drafts.remove(conversationId) else drafts[conversationId] = text
    }

    // ------------------------------------------------------------------
    // sending
    // ------------------------------------------------------------------

    fun send(text: String) {
        startGeneration(text.trim(), _state.value.messages)
    }

    /** Re-asks the model for the answer that follows [message]'s user turn. */
    fun regenerate(message: Message) {
        if (_state.value.isGenerating) return
        val all = _state.value.messages
        val index = all.indexOfFirst { it.id == message.id }
        if (index < 0) return
        val userIndex = (index - 1 downTo 0).firstOrNull { all[it].role == Role.USER } ?: return
        val userTurn = all[userIndex]
        val history = all.take(userIndex)
        viewModelScope.launch {
            all.drop(userIndex + 1).forEach { db.deleteMessage(it.id) }
            _state.update { it.copy(messages = history + userTurn, error = null) }
            startGeneration(userTurn.text, history, existingUserMessage = userTurn)
        }
    }

    /** Replaces [message]'s text and regenerates everything after it. */
    fun editMessage(message: Message, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty() || _state.value.isGenerating) return
        val all = _state.value.messages
        val index = all.indexOfFirst { it.id == message.id }
        if (index < 0 || message.role != Role.USER) return
        val history = all.take(index)
        val updated = message.copy(blocks = listOf(ContentBlock.Text(trimmed)))
        viewModelScope.launch {
            all.drop(index + 1).forEach { db.deleteMessage(it.id) }
            db.updateMessage(updated)
            _state.update { it.copy(messages = history + updated, error = null) }
            startGeneration(trimmed, history, existingUserMessage = updated)
        }
    }

    private fun startGeneration(
        trimmed: String,
        history: List<Message>,
        existingUserMessage: Message? = null
    ) {
        if (trimmed.isEmpty()) return
        val conversation = _state.value.activeConversation ?: return

        // Only *this* conversation is blocked while it is answering; every other
        // conversation can start its own generation in parallel.
        val running = runs[conversation.id]
        if (running != null) {
            if (running.job?.isActive != false) return
            // A run whose job is already finished but never got cleaned up: recover.
            runs.remove(conversation.id)
            transcripts.remove(conversation.id)
        }

        val provider = providerRepo.activeProvider()
        val modelId = providerRepo.activeModelId() ?: provider?.defaultModel()?.id
        val apiKey = provider?.let { providerRepo.apiKey(it.id) }
        if (provider == null || apiKey.isNullOrBlank() || modelId == null) {
            _state.update { it.copy(error = "还没有配置好模型：请在「设置 → 模型」里选择 provider 并填入 API Key。") }
            return
        }
        val modelInfo = provider.models.firstOrNull { it.id == modelId } ?: provider.defaultModel()

        val userMessage = existingUserMessage ?: Message(
            conversationId = conversation.id,
            role = Role.USER,
            blocks = listOf(ContentBlock.Text(trimmed)),
            status = MessageStatus.COMPLETE
        )
        val streaming = Message(
            conversationId = conversation.id,
            role = Role.ASSISTANT,
            blocks = emptyList(),
            status = MessageStatus.STREAMING,
            providerId = provider.id,
            modelId = modelId
        )

        // Title from the first message right away, so the conversation is
        // identifiable in the drawer while it is still generating.
        val titled = conversation.copy(title = conversation.title.ifBlank { titleFromText(trimmed) })
        val run = Run(titled, streaming.id)
        runs[conversation.id] = run

        // The user turn is appended here (unless it already exists, e.g. a
        // regenerate after an edit), then the streaming placeholder follows.
        val base = if (existingUserMessage == null) _state.value.messages + userMessage else _state.value.messages
        val withStreaming = mergeToolResults(base + streaming)
        transcripts[conversation.id] = withStreaming
        _state.update {
            it.copy(messages = withStreaming, activeConversation = titled, error = null)
        }
        publishRunState()
        refreshContextUsage()

        run.job = viewModelScope.launch(ConversationContext(conversation.id)) {
            try {
                if (!run.discard) {
                    // Persist up-front so the turn survives the app being killed
                    // and the conversation shows up in the drawer immediately.
                    // Start from the stored row: pin/rename may have changed since
                    // the UI state was captured, and the upsert replaces the row.
                    val stored = db.getConversation(titled.id) ?: titled
                    val saved = stored.copy(
                        title = stored.title.ifBlank { titled.title },
                        updatedAt = System.currentTimeMillis()
                    )
                    db.upsertConversation(saved)
                    run.conversation = saved
                    _state.update {
                        if (it.activeConversation?.id == saved.id) {
                            it.copy(activeConversation = saved)
                        } else {
                            it
                        }
                    }
                    if (existingUserMessage == null) db.insertMessage(userMessage)
                }

                var effectiveConversation = titled
                var effectiveHistory = effectiveHistory(effectiveConversation, history)
                val window = modelInfo?.contextWindow
                val projected = contextUsageFor(
                    conversation = effectiveConversation,
                    messages = history,
                    extraText = trimmed,
                    modelInfo = modelInfo
                ).usedTokens
                if (!run.discard && window != null && projected > window * AUTO_COMPACT_RATIO) {
                    run.compacting = true
                    publishRunState()
                    var compacted: Conversation? = null
                    try {
                        compacted = performCompaction(
                            conversation = effectiveConversation,
                            messages = history,
                            provider = provider,
                            apiKey = apiKey,
                            modelId = modelId,
                            modelInfo = modelInfo,
                            run = run
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // Compaction is an optimisation: if it fails, still try
                        // the request and let the provider report the real error.
                    } finally {
                        run.compacting = false
                        publishRunState()
                    }
                    if (compacted != null) {
                        effectiveConversation = compacted
                        effectiveHistory = effectiveHistory(compacted, history)
                        run.conversation = compacted
                    }
                }

                if (run.discard) return@launch
                val effectiveApiKey = apiKey
                val effectiveModelId = modelId
                val request = ChatRequest(
                    conversationId = effectiveConversation.id,
                    history = sanitizeHistory(effectiveHistory),
                    userText = trimmed,
                    provider = provider,
                    apiKey = effectiveApiKey,
                    modelId = effectiveModelId,
                    settings = settingsStore.settings.value,
                    systemPrompt = effectiveConversation.systemPrompt,
                    toolsEnabled = settingsStore.settings.value.pluginsEnabled
                )
                engine.send(request).collect { event -> handleEvent(event, run) }
            } catch (cancelled: CancellationException) {
                // Stop was pressed. Cleanup must not itself be cancelled, otherwise
                // the "generating" flag is never reset and the UI freezes.
                runCatching { withContext(NonCancellable) { finishInterrupted(run) } }
                throw cancelled
            } catch (failure: Throwable) {
                runCatching { withContext(NonCancellable) { finishCrashed(run, failure) } }
            } finally {
                withContext(NonCancellable) { endRun(conversation.id, run) }
            }
        }
    }

    // ------------------------------------------------------------------
    // context budget + compaction
    // ------------------------------------------------------------------

    /**
     * Requests use the summary plus only the messages after the cutoff. The
     * full transcript stays visible in the UI and in the database.
     */
    private fun effectiveHistory(conversation: Conversation, messages: List<Message>): List<Message> {
        val summary = conversation.contextSummary?.trim()?.takeIf { it.isNotEmpty() }
            ?: return messages
        val through = conversation.contextSummaryThrough ?: return messages
        val index = messages.indexOfFirst { it.id == through }
        val recent = if (index >= 0) messages.drop(index + 1) else messages
        val summaryMessage = Message(
            conversationId = conversation.id,
            role = Role.SYSTEM,
            blocks = listOf(
                ContentBlock.Text(
                    "以下是较早对话的压缩摘要，请把它当作已确认的上下文，继续完成后续任务：\n\n$summary"
                )
            ),
            status = MessageStatus.COMPLETE
        )
        return listOf(summaryMessage) + recent
    }

    private fun recentMessages(conversation: Conversation, messages: List<Message>): List<Message> {
        if (conversation.contextSummary.isNullOrBlank()) return messages
        val through = conversation.contextSummaryThrough ?: return messages
        val index = messages.indexOfFirst { it.id == through }
        return if (index >= 0) messages.drop(index + 1) else messages
    }

    private fun activeModelInfo(): ModelInfo? {
        val settings = providerRepo.settings.value
        val provider = providerRepo.activeProvider() ?: return null
        return provider.models.firstOrNull { it.id == settings.activeModelId }
            ?: provider.defaultModel()
    }

    private fun contextUsageFor(
        conversation: Conversation,
        messages: List<Message>,
        extraText: String = "",
        modelInfo: ModelInfo? = activeModelInfo(),
    ): ContextUsage {
        val settings = settingsStore.settings.value
        val systemText = conversation.systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
            ?: settings.systemPrompt
        val systemTokens = TokenEstimator.estimate(systemText)
        val hasSummary = !conversation.contextSummary.isNullOrBlank() &&
            conversation.contextSummaryThrough != null
        val summaryTokens = if (hasSummary) {
            TokenEstimator.estimate(conversation.contextSummary.orEmpty())
        } else {
            0
        }
        val messageTokens = TokenEstimator.estimate(recentMessages(conversation, messages)) +
            TokenEstimator.estimate(extraText)
        return ContextUsage(
            usedTokens = systemTokens + summaryTokens + messageTokens,
            windowTokens = modelInfo?.contextWindow,
            systemTokens = systemTokens,
            summaryTokens = summaryTokens,
            messageTokens = messageTokens,
            compacted = hasSummary
        )
    }

    private fun refreshContextUsage() {
        val conversation = _state.value.activeConversation ?: return
        val usage = contextUsageFor(conversation, _state.value.messages)
        _state.update { it.copy(contextUsage = usage) }
    }

    /**
     * Manual compaction from the context sheet. Never deletes transcript
     * messages; it only changes what subsequent requests send.
     */
    fun compactContext() {
        val state = _state.value
        if (state.isGenerating || state.compacting) return
        val conversation = state.activeConversation ?: return
        val provider = providerRepo.activeProvider() ?: return
        val modelId = providerRepo.activeModelId() ?: provider.defaultModel()?.id ?: return
        val apiKey = providerRepo.apiKey(provider.id) ?: return
        val modelInfo = provider.models.firstOrNull { it.id == modelId } ?: provider.defaultModel()
        val run = runs[conversation.id]

        viewModelScope.launch {
            if (run != null) {
                run.compacting = true
                publishRunState()
            } else {
                _state.update { it.copy(compacting = true) }
            }
            try {
                performCompaction(
                    conversation = conversation,
                    messages = _state.value.messages,
                    provider = provider,
                    apiKey = apiKey,
                    modelId = modelId,
                    modelInfo = modelInfo,
                    run = run
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(error = "上下文压缩失败：${failure.message ?: "未知错误"}")
                }
            } finally {
                if (run != null) run.compacting = false
                _state.update { it.copy(compacting = false) }
                publishRunState()
            }
        }
    }

    fun clearContextSummary() {
        val conversation = _state.value.activeConversation ?: return
        if (conversation.contextSummary.isNullOrBlank()) return
        val updated = conversation.copy(
            contextSummary = null,
            contextSummaryThrough = null,
            updatedAt = System.currentTimeMillis()
        )
        runs[conversation.id]?.conversation = updated
        viewModelScope.launch {
            db.upsertConversation(updated)
            _state.update { it.copy(activeConversation = updated) }
            refreshContextUsage()
        }
    }

    private suspend fun performCompaction(
        conversation: Conversation,
        messages: List<Message>,
        provider: ProviderConfig,
        apiKey: String,
        modelId: String,
        modelInfo: ModelInfo?,
        run: Run? = null,
    ): Conversation {
        val window = modelInfo?.contextWindow ?: DEFAULT_CONTEXT_WINDOW
        val previousIndex = conversation.contextSummaryThrough
            ?.let { id -> messages.indexOfFirst { it.id == id } }
            ?: -1
        val startIndex = if (conversation.contextSummary.isNullOrBlank()) 0 else previousIndex + 1
        if (startIndex >= messages.size) {
            throw ContextCompactionException("没有新的旧消息可以压缩")
        }

        val keepBudget = (window * CONTEXT_KEEP_RATIO).toInt().coerceAtLeast(2_000)
        var keepStart = messages.size
        var accumulated = 0
        for (index in messages.indices.reversed()) {
            if (index < startIndex) break
            val tokens = TokenEstimator.estimate(messages[index])
            if (accumulated + tokens > keepBudget && keepStart < messages.size) break
            accumulated += tokens
            keepStart = index
        }
        // Never start the retained window on a tool result: its assistant
        // tool-call turn would be hidden in the summary, producing an orphan
        // tool message that OpenAI/Anthropic reject.
        while (keepStart > startIndex && messages[keepStart].role == Role.TOOL) {
            keepStart--
        }
        if (keepStart <= startIndex) {
            throw ContextCompactionException("当前上下文还很充裕，不需要压缩")
        }

        val toSummarize = messages.subList(startIndex, keepStart)
        val chunkTokens = (window * 0.30).toInt().coerceIn(1_500, 24_000)
        val result = compactor.summarize(
            provider = provider,
            apiKey = apiKey,
            modelId = modelId,
            messages = toSummarize,
            previousSummary = conversation.contextSummary,
            sessionId = conversation.id,
            chunkTokenLimit = chunkTokens
        )
        val updated = conversation.copy(
            contextSummary = result.summary,
            contextSummaryThrough = toSummarize.last().id,
            updatedAt = System.currentTimeMillis()
        )
        db.upsertConversation(updated)
        run?.conversation = updated
        if (_state.value.activeConversation?.id == updated.id) {
            _state.update { it.copy(activeConversation = updated) }
            refreshContextUsage()
        }
        return updated
    }

    /** Stops the generation of the conversation that is on screen. */
    fun stop() {
        val id = _state.value.activeConversation?.id ?: return
        stopConversation(id)
    }

    /** Stops the generation of [conversationId] (it may be a background one). */
    fun stopConversation(conversationId: String) {
        val run = runs[conversationId] ?: return
        val job = run.job
        if (job == null || !job.isActive) {
            // Nothing left to cancel: make sure a stale run cannot block the UI.
            runs.remove(conversationId)
            transcripts.remove(conversationId)
            publishRunState()
            return
        }
        job.cancel()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun deleteMessage(message: Message) {
        if (_state.value.isGenerating) return
        viewModelScope.launch {
            val all = _state.value.messages
            val index = all.indexOfFirst { it.id == message.id }
            val toDelete = mutableSetOf(message.id)
            if (message.role == Role.ASSISTANT && index >= 0) {
                var next = index + 1
                while (next < all.size && all[next].role == Role.TOOL) {
                    toDelete += all[next].id
                    next++
                }
            }
            toDelete.forEach { db.deleteMessage(it) }

            val conversation = _state.value.activeConversation
            val summaryInvalidated = conversation?.contextSummaryThrough
                ?.let { it in toDelete } == true
            if (conversation != null && summaryInvalidated) {
                val cleared = conversation.copy(
                    contextSummary = null,
                    contextSummaryThrough = null,
                    updatedAt = System.currentTimeMillis()
                )
                db.upsertConversation(cleared)
                _state.update { it.copy(activeConversation = cleared) }
            }
            _state.update { state ->
                state.copy(messages = state.messages.filterNot { it.id in toDelete })
            }
            refreshContextUsage()
        }
    }

    /** Re-sends the last user turn, dropping whatever (failed) answer followed it. */
    fun retryLast() {
        if (_state.value.isGenerating) return
        val all = _state.value.messages
        val userIndex = all.indexOfLast { it.role == Role.USER }
        if (userIndex < 0) return
        val userTurn = all[userIndex]
        val text = userTurn.text
        if (text.isBlank()) return
        val history = all.take(userIndex)
        viewModelScope.launch {
            all.drop(userIndex).forEach { db.deleteMessage(it.id) }
            _state.update { it.copy(messages = history, error = null) }
            startGeneration(text, history)
        }
    }

    // ------------------------------------------------------------------
    // tool confirmation
    // ------------------------------------------------------------------

    private suspend fun awaitConfirmation(tool: ToolDescriptor, argumentsJson: String): Boolean {
        // The chat loop tags its coroutine with the conversation it belongs to.
        val conversationId = currentCoroutineContext()[ConversationContext]?.conversationId
            ?: return false
        val run = runs[conversationId] ?: return false

        val deferred = CompletableDeferred<Boolean>()
        run.pendingConfirm = deferred
        run.pendingTool = ToolConfirmationRequest(
            toolName = tool.name,
            pluginName = tool.pluginName,
            argumentsJson = argumentsJson,
            dangerous = tool.isDangerous
        )
        publishRunState()
        return try {
            deferred.await()
        } finally {
            run.pendingConfirm = null
            run.pendingTool = null
            publishRunState()
        }
    }

    /** Answers the confirmation request of the conversation on screen. */
    fun respondToTool(allow: Boolean) {
        val id = _state.value.activeConversation?.id ?: return
        runs[id]?.pendingConfirm?.complete(allow)
    }

    // ------------------------------------------------------------------
    // event handling
    // ------------------------------------------------------------------

    private suspend fun handleEvent(event: ChatEvent, run: Run) {
        val conversationId = run.conversation.id
        when (event) {
            is ChatEvent.TextDelta -> appendDelta(conversationId, run.streamingId, event.text, reasoning = false)

            is ChatEvent.ReasoningDelta -> appendDelta(conversationId, run.streamingId, event.text, reasoning = true)

            is ChatEvent.ToolStarted -> {
                appendBlock(
                    conversationId,
                    run.streamingId,
                    ContentBlock.ToolCall(
                        id = event.callId,
                        name = event.toolName,
                        argumentsJson = event.argumentsJson,
                        pluginId = event.pluginId,
                        pluginName = event.pluginName
                    )
                )
                run.pendingToolCallId = event.callId
                publishRunState()
            }

            is ChatEvent.ToolFinished -> {
                appendBlock(
                    conversationId,
                    run.streamingId,
                    ContentBlock.ToolResult(
                        callId = event.callId,
                        toolName = event.toolName,
                        pluginId = "",
                        output = event.output,
                        isError = event.isError,
                        durationMs = event.durationMs
                    )
                )
                run.pendingToolCallId = null
                publishRunState()
            }

            is ChatEvent.Completed -> finishCompleted(run, event)
            is ChatEvent.Failed -> finishFailed(run, event)
        }
    }

    private fun appendDelta(conversationId: String, streamingId: String, delta: String, reasoning: Boolean) {
        updateTranscript(conversationId) { messages ->
            messages.map { message ->
                if (message.id != streamingId) return@map message
                val blocks = message.blocks.toMutableList()
                val index = blocks.indexOfLast {
                    if (reasoning) it is ContentBlock.Reasoning else it is ContentBlock.Text
                }
                if (index >= 0) {
                    blocks[index] = when (val existing = blocks[index]) {
                        is ContentBlock.Text -> ContentBlock.Text(existing.text + delta)
                        is ContentBlock.Reasoning -> ContentBlock.Reasoning(existing.text + delta)
                        else -> existing
                    }
                } else {
                    blocks.add(if (reasoning) ContentBlock.Reasoning(delta) else ContentBlock.Text(delta))
                }
                message.copy(blocks = blocks)
            }
        }
    }

    private fun appendBlock(conversationId: String, streamingId: String, block: ContentBlock) {
        updateTranscript(conversationId) { messages ->
            messages.map { message ->
                if (message.id == streamingId) message.copy(blocks = message.blocks + block) else message
            }
        }
    }

    private suspend fun finishCompleted(run: Run, event: ChatEvent.Completed) {
        if (run.discard) return
        val conversation = run.conversation
        // Conversation gone (e.g. "clear all data"): do not resurrect it.
        val stored = db.getConversation(conversation.id) ?: return
        // The user turn was persisted when the run started; the engine returns
        // the assistant/tool turns it produced.
        event.messages.forEach { message -> db.insertMessage(message) }

        val updated = stored.copy(updatedAt = System.currentTimeMillis())
        db.upsertConversation(updated)
        run.conversation = updated

        updateTranscript(conversation.id) { list ->
            mergeToolResults(list.filterNot { it.id == run.streamingId } + event.messages)
        }
        _state.update { state ->
            if (state.activeConversation?.id == conversation.id) {
                state.copy(
                    activeConversation = updated,
                    error = if (event.toolLimitReached) "已达到工具调用轮数上限，回答可能不完整。" else null
                )
            } else {
                state
            }
        }
        refreshContextUsage()
    }

    private suspend fun finishFailed(run: Run, event: ChatEvent.Failed) {
        if (run.discard) return
        val conversationId = run.conversation.id
        val current = transcriptOf(conversationId).firstOrNull { it.id == run.streamingId } ?: return
        val partialText = event.partialText.ifBlank { current.text }
        val failed = current.copy(
            blocks = if (partialText.isBlank()) emptyList() else listOf(ContentBlock.Text(partialText)),
            status = MessageStatus.ERROR,
            error = event.message
        )
        val stored = db.getConversation(conversationId) ?: return
        event.partialMessages.forEach { db.insertMessage(it) }
        db.insertMessage(failed)
        db.upsertConversation(stored.copy(updatedAt = System.currentTimeMillis()))

        updateTranscript(conversationId) { list ->
            mergeToolResults(list.filterNot { it.id == run.streamingId } + event.partialMessages + failed)
        }
        reportError(run, event.message)
        refreshContextUsage()
    }

    private suspend fun finishCrashed(run: Run, failure: Throwable) {
        finishFailed(
            run,
            ChatEvent.Failed(
                message = failure.message ?: "生成失败",
                httpCode = null,
                retryable = false,
                partialText = "",
                partialMessages = emptyList()
            )
        )
    }

    private suspend fun finishInterrupted(run: Run) {
        if (run.discard) return
        val conversationId = run.conversation.id
        val current = transcriptOf(conversationId).firstOrNull { it.id == run.streamingId } ?: return
        // A tool call that was in flight never produced a result. Close it so the
        // card does not spin forever and the history stays valid for the provider.
        val stopped = closeOpenToolCalls(current).copy(status = MessageStatus.STOPPED)
        val keep = stopped.blocks.isNotEmpty()

        val stored = db.getConversation(conversationId) ?: return
        if (keep) db.insertMessage(stopped)
        db.upsertConversation(stored.copy(updatedAt = System.currentTimeMillis()))

        updateTranscript(conversationId) { list ->
            list.filterNot { it.id == run.streamingId } + (if (keep) listOf(stopped) else emptyList())
        }
        refreshContextUsage()
    }

    private fun closeOpenToolCalls(message: Message): Message {
        val answered = message.blocks.filterIsInstance<ContentBlock.ToolResult>().map { it.callId }.toSet()
        val open = message.blocks.filterIsInstance<ContentBlock.ToolCall>().filter { it.id !in answered }
        if (open.isEmpty()) return message
        val results = open.map { call ->
            ContentBlock.ToolResult(
                callId = call.id,
                toolName = call.name,
                pluginId = call.pluginId,
                output = "已被用户中断，未返回结果。",
                isError = true,
                durationMs = 0L
            )
        }
        return message.copy(blocks = message.blocks + results)
    }

    private fun endRun(conversationId: String, run: Run) {
        if (runs[conversationId] === run) {
            runs.remove(conversationId)
            transcripts.remove(conversationId)
        }
        publishRunState()
    }

    private fun reportError(run: Run, message: String) {
        val onScreen = _state.value.activeConversation?.id == run.conversation.id
        val text = if (onScreen) {
            message
        } else {
            "「${run.conversation.title.ifBlank { "新会话" }}」出错：$message"
        }
        _state.update { it.copy(error = text) }
    }

    // ------------------------------------------------------------------
    // transcript / run bookkeeping
    // ------------------------------------------------------------------

    private fun transcriptOf(conversationId: String): List<Message> {
        val state = _state.value
        return if (state.activeConversation?.id == conversationId) {
            state.messages
        } else {
            transcripts[conversationId].orEmpty()
        }
    }

    /**
     * Applies [transform] to a conversation's live transcript: the visible one
     * when it is on screen, the parked copy otherwise.
     */
    private fun updateTranscript(conversationId: String, transform: (List<Message>) -> List<Message>) {
        val state = _state.value
        val onScreen = state.activeConversation?.id == conversationId
        val current = if (onScreen) state.messages else (transcripts[conversationId] ?: return)
        val next = transform(current)
        if (runs.containsKey(conversationId)) transcripts[conversationId] = next
        if (onScreen) _state.update { it.copy(messages = next) }
    }

    /** Re-derives the run-related UI state for the conversation on screen. */
    private fun publishRunState() {
        _state.update { state ->
            val run = state.activeConversation?.id?.let { runs[it] }
            state.copy(
                generatingIds = runs.keys.toSet(),
                isGenerating = run != null,
                sending = run != null,
                pendingTool = run?.pendingTool,
                pendingToolCallId = run?.pendingToolCallId,
                compacting = run?.compacting == true
            )
        }
    }

    private fun titleFromText(text: String): String =
        text.replace('\n', ' ').trim().take(28).ifBlank { "新对话" }

    // ------------------------------------------------------------------
    // misc
    // ------------------------------------------------------------------

    private fun refreshLabels() {
        val settings = providerRepo.settings.value
        val provider = providerRepo.activeProvider()
        val model = provider?.models?.firstOrNull { it.id == settings.activeModelId } ?: provider?.defaultModel()
        val connected = mcp.connectedTools().keys
        val plugins = settingsStore.plugins.value.filter { it.enabled }
        val label = when {
            !settings.pluginsEnabled -> null
            connected.isNotEmpty() -> "${connected.size} 个插件已连接"
            plugins.isNotEmpty() -> "${plugins.size} 个插件已启用"
            else -> null
        }
        _state.update {
            it.copy(
                modelLabel = model?.displayName ?: settings.activeModelId ?: "未选择模型",
                pluginsLabel = label,
                pluginsHealthy = settings.pluginsEnabled
            )
        }
    }

    fun messagePolicyFor(tool: ToolDescriptor): ToolPolicy = container.toolExecutor.policyFor(tool)

    fun onAppSettingsChanged(settings: AppSettings) = Unit

    override fun onCleared() {
        runs.values.forEach { it.pendingConfirm?.complete(false) }
        super.onCleared()
    }

    private companion object {
        const val AUTO_COMPACT_RATIO = 0.85f
        const val CONTEXT_KEEP_RATIO = 0.30
        const val DEFAULT_CONTEXT_WINDOW = 128_000
    }
}
