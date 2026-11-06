package app.kujira.android.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kujira.android.data.model.Message
import app.kujira.android.di.AppContainer
import app.kujira.android.ui.components.KujiraTextInputDialog
import app.kujira.android.ui.components.rememberClipboardCopy
import app.kujira.android.ui.sessions.SessionDrawerHost
import kotlinx.coroutines.launch

/**
 * Wires [ChatScreen] to [ChatViewModel] and the conversation drawer.
 * Navigation is delegated to the caller so the route stays reusable.
 */
@Composable
fun ChatRoute(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenPlugins: () -> Unit
) {
    val viewModel: ChatViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copy = rememberClipboardCopy()

    var editingMessage by remember { mutableStateOf<Message?>(null) }
    var editingText by remember { mutableStateOf("") }

    LaunchedEffect(state.error) {
        state.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.dismissError()
        }
    }

    val activeId = state.activeConversation?.id
    // Conversations other than the open one that are still generating.
    val otherRunningCount = state.generatingIds.count { it != activeId }

    Box(modifier = Modifier.fillMaxSize()) {
        SessionDrawerHost(
            conversations = state.conversations,
            currentConversationId = activeId,
            drawerState = drawerState,
            onNewConversation = {
                viewModel.newConversation()
                scope.launch { drawerState.close() }
            },
            onSelectConversation = { conversation ->
                viewModel.selectConversation(conversation.id)
                scope.launch { drawerState.close() }
            },
            onRenameConversation = { conversation, title ->
                viewModel.renameConversation(conversation.id, title)
            },
            onDeleteConversation = { conversation ->
                viewModel.deleteConversation(conversation.id)
                Toast.makeText(context, "已删除对话", Toast.LENGTH_SHORT).show()
            },
            onTogglePin = { conversation -> viewModel.togglePin(conversation.id) },
            onOpenSettings = onOpenSettings
        ) {
            ChatScreen(
                messages = state.messages,
                conversationTitle = state.activeConversation?.title.orEmpty(),
                modelLabel = state.modelLabel,
                isGenerating = state.isGenerating,
                pluginsLabel = state.pluginsLabel,
                pluginsHealthy = state.pluginsHealthy,
                showReasoning = state.showReasoning,
                // Only show allow/deny while a confirmation is really pending —
                // AUTO tools never wait for the user.
                awaitingToolCallId = if (state.pendingTool != null) state.pendingToolCallId else null,
                contextUsage = state.contextUsage,
                compacting = state.compacting,
                contextSummary = state.activeConversation?.contextSummary,
                otherRunningCount = otherRunningCount,
                draftKey = activeId,
                initialDraft = viewModel.draftFor(activeId),
                onDraftChange = { text -> viewModel.saveDraft(activeId, text) },
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onNewChat = { viewModel.newConversation() },
                onOpenModelPicker = onOpenSettings,
                onOpenSettings = onOpenSettings,
                onPluginsClick = onOpenPlugins,
                onToolDecision = { _, approved -> viewModel.respondToTool(approved) },
                onCopyMessage = { message -> copy(message.text.ifBlank { message.reasoning }) },
                onRegenerate = viewModel::regenerate,
                onEditMessage = { message ->
                    editingMessage = message
                    editingText = message.text
                },
                onDeleteMessage = { message -> viewModel.deleteMessage(message) },
                onRetryMessage = { viewModel.retryLast() },
                onCompactContext = viewModel::compactContext,
                onClearContextSummary = viewModel::clearContextSummary
            )
        }
    }

    editingMessage?.let { message ->
        KujiraTextInputDialog(
            title = "编辑消息",
            value = editingText,
            onValueChange = { editingText = it },
            onConfirm = {
                viewModel.editMessage(message, editingText)
                editingMessage = null
            },
            onDismiss = { editingMessage = null },
            label = "消息内容",
            confirmText = "重新发送"
        )
    }
}
