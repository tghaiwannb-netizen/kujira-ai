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
import app.kujira.android.data.model.Role
import app.kujira.android.data.model.ToolDescriptor
import app.kujira.android.data.model.ToolPolicy
import app.kujira.android.domain.ChatEvent
import app.kujira.android.domain.ChatRequest
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
    val sending: Boolean = false
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
        }
    }

    // ------------------------------------------------------------------
    // conversations
    // ------------------------------------------------------------------

    fun newConversation(persist: Boolean = true) {
        viewModelScope.launch {
            val conversation = Conversation(
                title = "",
                providerId = providerRepo.settings.value.activeProviderId,
                modelId = providerRepo.activeModelId()
            )
            if (persist) db.upsertConversation(conversation)
            activeConversationId = conversation.id
            settingsStore.updateSettings { it.copy(lastConversationId = conversation.id) }
            _state.update {
                it.copy(
                    activeConversation = conversation,
                    messages = emptyList(),
                    error = null
                )
            }
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
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.isGenerating) return
        val conversation = _state.value.activeConversation ?: return

        val provider = providerRepo.activeProvider()
        val modelId = providerRepo.activeModelId() ?: provider?.defaultModel()?.id
        val apiKey = provider?.let { providerRepo.apiKey(it.id) }
        if (provider == null || apiKey.isNullOrBlank() || modelId == null) {
            _state.update { it.copy(error = "还没有配置好模型：请在「设置 → 模型」里选择 provider 并填入 API Key。") }
            return
        }

        val history = _state.value.messages
        val userMessage = Message(
            conversationId = conversation.id,
            role = Role.USER,
            blocks = listOf(ContentBlock.Text(trimmed)),
            status = MessageStatus.COMPLETE
        )
        pendingUserMessage = userMessage
        val streaming = Message(
            conversationId = conversation.id,
            role = Role.ASSISTANT,
            blocks = emptyList(),
            status = MessageStatus.STREAMING,
            providerId = provider.id,
            modelId = modelId
        )
        _state.update {
            it.copy(
                messages = it.messages + userMessage + streaming,
                isGenerating = true,
                sending = true,
                error = null
            )
        }

        sendJob = viewModelScope.launch {
            try {
                engine.send(
                    ChatRequest(
                        conversationId = conversation.id,
                        history = history,
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
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        isGenerating = false,
                        sending = false,
                        error = failure.message ?: "生成失败"
                    )
                }
            }
        }
    }

    fun stop() {
        sendJob?.cancel()
        sendJob = null
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun deleteMessage(message: Message) {
        viewModelScope.launch {
            db.deleteMessage(message.id)
            _state.update { state ->
                state.copy(messages = state.messages.filterNot { it.id == message.id })
            }
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
}
