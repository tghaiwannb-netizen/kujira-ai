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
import app.kujira.android.domain.TokenEstimator
import app.kujira.android.domain.ToolConfirmer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val messages: List<Message> = emptyList(),
    val isGenerating: Boolean = false,
    val error: String? = null,
    val modelLabel: String = "",
    val pluginsLabel: String? = null,
    val pluginsHealthy: Boolean = true,
    val showReasoning: Boolean = true,
    val pendingTool: ToolConfirmationRequest? = null,
    val pendingToolCallId: String? = null,
    val sending: Boolean = false,
    val compacting: Boolean = false,
    val contextUsage: ContextUsage = ContextUsage()
)

/**
 * Chat screen state holder: conversation selection/persistence, the streaming
 * loop, and the tool-confirmation handshake with the UI.
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

    private var sendJob: Job? = null
    private var pendingUserMessage: Message? = null
    private var pendingConfirm: CompletableDeferred<Boolean>? = null
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
                refreshContextUsage()
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
        refreshContextUsage()
        settingsStore.updateSettings { it.copy(lastConversationId = conversation.id) }
        if (persist) {
            viewModelScope.launch { db.upsertConversation(conversation) }
        }
    }

    fun selectConversation(id: String) {
        viewModelScope.launch {
            val conversation = db.getConversation(id) ?: return@launch
            openConversation(conversation)
        }
    }

    private suspend fun openConversation(conversation: Conversation) {
        activeConversationId = conversation.id
        settingsStore.updateSettings { it.copy(lastConversationId = conversation.id) }
        val messages = db.messagesOf(conversation.id)
        _state.update {
            it.copy(activeConversation = conversation, messages = messages, error = null)
        }
        refreshContextUsage()
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch {
            val conversation = db.getConversation(id) ?: return@launch
            val updated = conversation.copy(title = title.trim(), updatedAt = System.currentTimeMillis())
            db.upsertConversation(updated)
            if (_state.value.activeConversation?.id == id) {
                _state.update { it.copy(activeConversation = updated) }
            }
        }
    }

    fun deleteConversation(id: String) {
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
        if (trimmed.isEmpty() || _state.value.isGenerating) return
        var conversation = _state.value.activeConversation ?: return

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
        pendingUserMessage = if (existingUserMessage == null) userMessage else null
        val streaming = Message(
            conversationId = conversation.id,
            role = Role.ASSISTANT,
            blocks = emptyList(),
            status = MessageStatus.STREAMING,
            providerId = provider.id,
            modelId = modelId
        )
        _state.update { state ->
            // The user turn is appended here (unless it already exists, e.g. a
            // regenerate after an edit), then the streaming placeholder follows.
            val base = if (existingUserMessage == null) state.messages + userMessage else state.messages
            state.copy(
                messages = base + streaming,
                isGenerating = true,
                sending = true,
                error = null
            )
        }
        refreshContextUsage()

        sendJob = viewModelScope.launch {
            var effectiveHistory = effectiveHistory(conversation, history)
            val window = modelInfo?.contextWindow
            val projected = contextUsageFor(conversation, history, extraText = trimmed).usedTokens
            if (window != null && projected > window * AUTO_COMPACT_RATIO) {
                _state.update { it.copy(compacting = true) }
                var compacted: Conversation? = null
                try {
                    compacted = performCompaction(
                        conversation = conversation,
                        messages = history,
                        provider = provider,
                        apiKey = apiKey,
                        modelId = modelId,
                        modelInfo = modelInfo
                    )
                } catch (cancelled: CancellationException) {
                    _state.update { it.copy(compacting = false) }
                    finishInterrupted(conversation, streaming)
                    throw cancelled
                } catch (_: Throwable) {
                    // If compression fails, still try the request. The provider's
                    // own error (or a successful send) is more useful than
                    // blocking the turn because an optional optimisation failed.
                } finally {
                    _state.update { it.copy(compacting = false) }
                }
                val updated = compacted
                if (updated != null) {
                    conversation = updated
                    effectiveHistory = effectiveHistory(updated, history)
                }
            }

            try {
                engine.send(
                    ChatRequest(
                        conversationId = conversation.id,
                        history = effectiveHistory,
                        userText = trimmed,
                        provider = provider,
                        apiKey = apiKey,
                        modelId = modelId,
                        settings = settingsStore.settings.value,
                        systemPrompt = conversation.systemPrompt,
                        toolsEnabled = settingsStore.settings.value.pluginsEnabled
                    )
                ).collect { event -> handleEvent(event, conversation, streaming) }
            } catch (cancelled: CancellationException) {
                finishInterrupted(conversation, streaming)
                throw cancelled
            } catch (failureThrowable: Throwable) {
                _state.update {
                    it.copy(
                        isGenerating = false,
                        sending = false,
                        error = failureThrowable.message ?: "生成失败"
                    )
                }
            }
        }
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

        viewModelScope.launch {
            _state.update { it.copy(compacting = true, error = null) }
            try {
                performCompaction(
                    conversation = conversation,
                    messages = _state.value.messages,
                    provider = provider,
                    apiKey = apiKey,
                    modelId = modelId,
                    modelInfo = modelInfo
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(error = "上下文压缩失败：${failure.message ?: "未知错误"}")
                }
            } finally {
                _state.update { it.copy(compacting = false) }
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
        viewModelScope.launch {
            db.upsertConversation(updated)
            _state.update { it.copy(activeConversation = updated) }
            refreshContextUsage()
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
    ): ContextUsage {
        val settings = settingsStore.settings.value
        val systemText = conversation.systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
            ?: settings.systemPrompt
        val systemTokens = TokenEstimator.estimate(systemText)
        val hasSummary = !conversation.contextSummary.isNullOrBlank() &&
            conversation.contextSummaryThrough != null
        val summaryTokens = if (hasSummary) TokenEstimator.estimate(conversation.contextSummary.orEmpty()) else 0
        val messageTokens = TokenEstimator.estimate(recentMessages(conversation, messages)) +
            TokenEstimator.estimate(extraText)
        return ContextUsage(
            usedTokens = systemTokens + summaryTokens + messageTokens,
            windowTokens = activeModelInfo()?.contextWindow,
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

    private suspend fun performCompaction(
        conversation: Conversation,
        messages: List<Message>,
        provider: ProviderConfig,
        apiKey: String,
        modelId: String,
        modelInfo: ModelInfo?,
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
        _state.update { it.copy(activeConversation = updated) }
        refreshContextUsage()
        return updated
    }

    fun stop() {
        sendJob?.cancel()
        sendJob = null
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun deleteMessage(message: Message) {
        viewModelScope.launch {
            db.deleteMessage(message.id)
            val conversation = _state.value.activeConversation
            val summaryInvalidated = conversation?.contextSummaryThrough == message.id
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
                state.copy(messages = state.messages.filterNot { it.id == message.id })
            }
            refreshContextUsage()
        }
    }

    fun retryLast() {
        val lastUser = _state.value.messages.lastOrNull { it.role == Role.USER } ?: return
        val text = lastUser.text
        if (text.isNotBlank()) {
            _state.update { it.copy(messages = it.messages.filterNot { m -> m.id == lastUser.id }) }
            send(text)
        }
    }

    // ------------------------------------------------------------------
    // tool confirmation
    // ------------------------------------------------------------------

    private suspend fun awaitConfirmation(tool: ToolDescriptor, argumentsJson: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirm = deferred
        _state.update {
            it.copy(
                pendingTool = ToolConfirmationRequest(
                    toolName = tool.name,
                    pluginName = tool.pluginName,
                    argumentsJson = argumentsJson,
                    dangerous = tool.isDangerous
                )
            )
        }
        val allowed = try {
            deferred.await()
        } finally {
            pendingConfirm = null
            _state.update { it.copy(pendingTool = null) }
        }
        return allowed
    }

    fun respondToTool(allow: Boolean) {
        pendingConfirm?.complete(allow)
    }

    // ------------------------------------------------------------------
    // event handling
    // ------------------------------------------------------------------

    private suspend fun handleEvent(event: ChatEvent, conversation: Conversation, streaming: Message) {
        when (event) {
            is ChatEvent.TextDelta -> appendBlock<ContentBlock.Text>(streaming.id, event.text) {
                ContentBlock.Text(it)
            }

            is ChatEvent.ReasoningDelta -> appendBlock<ContentBlock.Reasoning>(streaming.id, event.text) {
                ContentBlock.Reasoning(it)
            }

            is ChatEvent.ToolStarted -> {
                val block = ContentBlock.ToolCall(
                    id = event.callId,
                    name = event.toolName,
                    argumentsJson = event.argumentsJson,
                    pluginId = event.pluginId,
                    pluginName = event.pluginName
                )
                _state.update { state ->
                    val messages = state.messages.map { message ->
                        if (message.id == streaming.id) {
                            message.copy(blocks = message.blocks + block)
                        } else {
                            message
                        }
                    }
                    state.copy(messages = messages, pendingToolCallId = event.callId)
                }
            }

            is ChatEvent.ToolFinished -> {
                val block = ContentBlock.ToolResult(
                    callId = event.callId,
                    toolName = event.toolName,
                    pluginId = "",
                    output = event.output,
                    isError = event.isError,
                    durationMs = event.durationMs
                )
                _state.update { state ->
                    val messages = state.messages.map { message ->
                        if (message.id == streaming.id) {
                            message.copy(blocks = message.blocks + block)
                        } else {
                            message
                        }
                    }
                    state.copy(messages = messages, pendingToolCallId = null)
                }
            }

            is ChatEvent.Completed -> finishCompleted(conversation, streaming, event)
            is ChatEvent.Failed -> finishFailed(conversation, streaming, event)
        }
    }

    private inline fun <reified T : ContentBlock> appendBlock(
        streamingId: String,
        delta: String,
        create: (String) -> ContentBlock
    ) {
        _state.update { state ->
            val messages = state.messages.map { message ->
                if (message.id != streamingId) return@map message
                val blocks = message.blocks.toMutableList()
                val index = blocks.indexOfLast { it is T }
                if (index >= 0) {
                    val existing = blocks[index]
                    val merged = when (existing) {
                        is ContentBlock.Text -> ContentBlock.Text(existing.text + delta)
                        is ContentBlock.Reasoning -> ContentBlock.Reasoning(existing.text + delta)
                        else -> existing
                    }
                    blocks[index] = merged
                } else {
                    blocks.add(create(delta))
                }
                message.copy(blocks = blocks)
            }
            state.copy(messages = messages)
        }
    }

    private suspend fun finishCompleted(
        conversation: Conversation,
        streaming: Message,
        event: ChatEvent.Completed
    ) {
        // Persist: the user turn is not in the DB yet; the engine returns the
        // assistant/tool turns it produced.
        pendingUserMessage?.let { db.insertMessage(it) }
        pendingUserMessage = null
        event.messages.forEach { message -> db.insertMessage(message) }

        val updated = conversation.copy(
            updatedAt = System.currentTimeMillis(),
            title = conversation.title.ifBlank { titleFrom(event) }
        )
        db.upsertConversation(updated)

        _state.update { state ->
            state.copy(
                messages = state.messages.filterNot { it.id == streaming.id } + event.messages,
                isGenerating = false,
                sending = false,
                activeConversation = updated,
                error = if (event.toolLimitReached) "已达到工具调用轮数上限，回答可能不完整。" else null
            )
        }
        refreshContextUsage()
    }

    private suspend fun finishFailed(
        conversation: Conversation,
        streaming: Message,
        event: ChatEvent.Failed
    ) {
        val partialText = event.partialText.ifBlank { streaming.text }
        val failed = streaming.copy(
            blocks = if (partialText.isBlank()) emptyList() else listOf(ContentBlock.Text(partialText)),
            status = MessageStatus.ERROR,
            error = event.message
        )
        pendingUserMessage?.let { db.insertMessage(it) }
        pendingUserMessage = null
        event.partialMessages.forEach { db.insertMessage(it) }
        db.insertMessage(failed)
        db.upsertConversation(conversation.copy(updatedAt = System.currentTimeMillis()))
        _state.update {
            it.copy(
                messages = it.messages.filterNot { m -> m.id == streaming.id } + failed,
                isGenerating = false,
                sending = false,
                error = event.message
            )
        }
        refreshContextUsage()
    }

    private suspend fun finishInterrupted(conversation: Conversation, streaming: Message) {
        val current = _state.value.messages.firstOrNull { it.id == streaming.id } ?: return
        val stopped = current.copy(status = MessageStatus.STOPPED)
        pendingUserMessage?.let { db.insertMessage(it) }
        pendingUserMessage = null
        if (stopped.blocks.isNotEmpty()) db.insertMessage(stopped)
        db.upsertConversation(conversation.copy(updatedAt = System.currentTimeMillis()))
        _state.update {
            it.copy(
                messages = it.messages.filterNot { m -> m.id == streaming.id } +
                    (if (stopped.blocks.isNotEmpty()) listOf(stopped) else emptyList()),
                isGenerating = false,
                sending = false
            )
        }
        refreshContextUsage()
    }

    private fun titleFrom(event: ChatEvent.Completed): String {
        val text = _state.value.messages.lastOrNull { it.role == Role.USER }?.text.orEmpty()
        return text.replace('\n', ' ').trim().take(28).ifBlank { "新对话" }
    }

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
        sendJob?.cancel()
        pendingConfirm?.complete(false)
        super.onCleared()
    }

    private companion object {
        const val AUTO_COMPACT_RATIO = 0.85f
        const val CONTEXT_KEEP_RATIO = 0.30
        const val DEFAULT_CONTEXT_WINDOW = 128_000
    }
}
