package app.kujira.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kujira.android.data.model.ContentBlock
import app.kujira.android.domain.ContextUsage
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.Role
import app.kujira.android.ui.components.KujiraIconButton
import app.kujira.android.ui.theme.KujiraTheme
import kotlinx.coroutines.launch
import java.util.Calendar

/** One tappable prompt card on the chat empty state. */
data class ChatSuggestion(
    val title: String,
    val prompt: String,
    val icon: ImageVector = Icons.Outlined.AutoAwesome,
)

/** The four default prompts shown before the first message. */
val defaultChatSuggestions: List<ChatSuggestion> = listOf(
    ChatSuggestion(
        title = "服务器状态",
        prompt = "帮我看看服务器状态，列出 CPU、内存和磁盘占用",
        icon = Icons.Outlined.Dns,
    ),
    ChatSuggestion(
        title = "磁盘占用",
        prompt = "查一下磁盘占用，找出最占空间的文件",
        icon = Icons.Outlined.Storage,
    ),
    ChatSuggestion(
        title = "拉取日志",
        prompt = "拉取 nginx 最近 100 行日志，并解释其中的错误",
        icon = Icons.Outlined.Article,
    ),
    ChatSuggestion(
        title = "写段代码",
        prompt = "用 Kotlin 写一个带重试和超时的 HTTP 请求函数",
        icon = Icons.Outlined.Code,
    ),
)

/**
 * The chat screen: restrained top bar with a live status line, full-bleed
 * transcript, sticky composer, branded empty state, and follow-the-bottom
 * scrolling that pauses when the user scrolls up.
 *
 * Several conversations can generate at once: [isGenerating] describes the one
 * on screen, [otherRunningCount] the others (shown as a tappable pill that
 * opens the drawer). The draft is kept per conversation via [draftKey] /
 * [initialDraft] / [onDraftChange].
 */
@Composable
fun ChatScreen(
    messages: List<Message>,
    modifier: Modifier = Modifier,
    conversationTitle: String = "",
    modelLabel: String = "DeepSeek V3.2",
    isGenerating: Boolean = false,
    pluginsLabel: String? = null,
    pluginsHealthy: Boolean = true,
    showReasoning: Boolean = true,
    codeFontEnabled: Boolean = true,
    /** Set false in tests/screenshot harnesses to skip enter transitions. */
    animateEntrances: Boolean = true,
    awaitingToolCallId: String? = null,
    contextUsage: ContextUsage = ContextUsage(),
    compacting: Boolean = false,
    contextSummary: String? = null,
    /** Number of *other* conversations that are generating right now. */
    otherRunningCount: Int = 0,
    /** Identity of the open conversation; a new key swaps in that conversation's draft. */
    draftKey: String? = null,
    initialDraft: String = "",
    onDraftChange: (String) -> Unit = {},
    suggestions: List<ChatSuggestion> = defaultChatSuggestions,
    onSend: (String) -> Unit = {},
    onStop: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onOpenModelPicker: () -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
    onPluginsClick: (() -> Unit)? = null,
    onCopyMessage: ((Message) -> Unit)? = null,
    onRegenerate: ((Message) -> Unit)? = null,
    onEditMessage: ((Message) -> Unit)? = null,
    onDeleteMessage: ((Message) -> Unit)? = null,
    onRetryMessage: ((Message) -> Unit)? = null,
    onToolDecision: ((ContentBlock.ToolCall, Boolean) -> Unit)? = null,
    onCompactContext: () -> Unit = {},
    onClearContextSummary: () -> Unit = {},
) {
    var draft by rememberSaveable(draftKey) { mutableStateOf(initialDraft) }
    var contextSheetVisible by remember { mutableStateOf(false) }
    val latestOnDraftChange by rememberUpdatedState(onDraftChange)
    LaunchedEffect(draft) { latestOnDraftChange(draft) }
    var autoFollow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Tool-result turns are provider-history plumbing; their results are
    // rendered inside the preceding assistant message's tool cards.
    val visibleMessages = remember(messages) { messages.filterNot { it.role == Role.TOOL } }

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val items = layoutInfo.visibleItemsInfo
            if (layoutInfo.totalItemsCount == 0 || items.isEmpty()) {
                true
            } else {
                val last = items.last()
                last.index >= layoutInfo.totalItemsCount - 1 &&
                    last.offset + last.size <= layoutInfo.viewportEndOffset + 24
            }
        }
    }
    val showTopDivider by remember { derivedStateOf { listState.canScrollBackward } }

    // Switching conversation: follow the new transcript from its bottom.
    LaunchedEffect(draftKey) {
        autoFollow = true
        if (visibleMessages.isNotEmpty()) listState.scrollToItem(visibleMessages.size)
    }
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) autoFollow = true
    }
    LaunchedEffect(isDragged) {
        if (isDragged && !isAtBottom) autoFollow = false
    }

    val lastMessagesCount = visibleMessages.size
    LaunchedEffect(lastMessagesCount) {
        if (lastMessagesCount > 0 && autoFollow) {
            // messages.size is the index of the 1dp end spacer: aligning it to
            // the top clamps to the real bottom of the list.
            listState.animateScrollToItem(lastMessagesCount)
        }
    }
    val streamingTick = visibleMessages.lastOrNull()?.let { message ->
        message.blocks.sumOf { block ->
            when (block) {
                is ContentBlock.Text -> block.text.length
                is ContentBlock.Reasoning -> block.text.length
                is ContentBlock.ToolResult -> block.output.length
                is ContentBlock.ToolCall -> block.argumentsJson.length
            }
        }
    } ?: 0
    LaunchedEffect(streamingTick) {
        if (lastMessagesCount > 0 && autoFollow) {
            listState.scrollToItem(lastMessagesCount)
        }
    }

    fun sendDraft() {
        val text = draft.trim()
        if (text.isEmpty() || isGenerating) return
        draft = ""
        autoFollow = true
        onSend(text)
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                ChatTopBar(
                    title = conversationTitle,
                    modelLabel = modelLabel,
                    isGenerating = isGenerating,
                    otherRunningCount = otherRunningCount,
                    onOpenDrawer = onOpenDrawer,
                    onNewChat = onNewChat,
                    onOpenModelPicker = onOpenModelPicker,
                    onOpenSettings = onOpenSettings,
                )
                // 2dp status strip: progress while replying, hairline when scrolled.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                ) {
                    if (isGenerating) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.Transparent,
                        )
                    } else if (showTopDivider) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .align(Alignment.BottomCenter)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        )
                    }
                }
            }
        },
        bottomBar = {
            Composer(
                value = draft,
                onValueChange = { draft = it },
                onSend = { sendDraft() },
                isGenerating = isGenerating,
                onStop = onStop,
                modelLabel = modelLabel,
                onModelClick = onOpenModelPicker,
                pluginsLabel = pluginsLabel,
                pluginsHealthy = pluginsHealthy,
                onPluginsClick = onPluginsClick,
                contextUsage = contextUsage,
                compacting = compacting,
                onContextClick = { contextSheetVisible = true },
                placeholder = if (isGenerating) "正在回复…可新建会话并行处理" else "给 Kujira 发消息…",
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (visibleMessages.isEmpty()) {
                ChatEmptyState(
                    suggestions = suggestions,
                    onSuggestionClick = { suggestion -> draft = suggestion.prompt },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 20.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    itemsIndexed(
                        items = visibleMessages,
                        key = { _, message -> message.id },
                    ) { index, message ->
                        val isLast = index == visibleMessages.lastIndex
                        MessageItem(
                            message = message,
                            modifier = Modifier.animateItem(),
                            animateIn = isLast && animateEntrances,
                            isStreaming = isGenerating && isLast && message.role == Role.ASSISTANT,
                            showReasoning = showReasoning,
                            codeFontEnabled = codeFontEnabled,
                            awaitingToolCallId = if (isLast) awaitingToolCallId else null,
                            onCopy = onCopyMessage,
                            onRegenerate = onRegenerate,
                            onEdit = onEditMessage,
                            onDelete = onDeleteMessage,
                            onRetry = onRetryMessage,
                            onToolDecision = onToolDecision,
                        )
                    }
                    item(key = "chat-end-spacer") {
                        Spacer(Modifier.height(1.dp))
                    }
                }
            }

            if (visibleMessages.isNotEmpty()) {
                AnimatedVisibility(
                    visible = !isAtBottom,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    enter = fadeIn(animationSpec = tween(150)) +
                        slideInVertically(animationSpec = tween(150)) { height -> height / 2 },
                    exit = fadeOut(animationSpec = tween(120)) +
                        slideOutVertically(animationSpec = tween(120)) { height -> height / 2 },
                ) {
                    Surface(
                        onClick = {
                            autoFollow = true
                            scope.launch {
                                listState.animateScrollToItem(visibleMessages.size)
                            }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.ArrowDownward,
                                contentDescription = "回到底部",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (contextSheetVisible) {
        ContextSheet(
            usage = contextUsage,
            messages = messages,
            modelLabel = modelLabel,
            summary = contextSummary,
            compacting = compacting,
            onDismiss = { contextSheetVisible = false },
            onCompact = {
                onCompactContext()
            },
            onClearSummary = {
                onClearContextSummary()
            },
        )
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    modelLabel: String,
    isGenerating: Boolean,
    otherRunningCount: Int,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KujiraIconButton(
            icon = Icons.Outlined.Menu,
            contentDescription = "打开会话列表",
            onClick = onOpenDrawer,
            size = 44.dp,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 2.dp, end = 8.dp),
        ) {
            Text(
                text = title.ifBlank { "新会话" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenModelPicker)
                        .padding(vertical = 2.dp, horizontal = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isGenerating) {
                        PulsingDot(color = MaterialTheme.colorScheme.primary, size = 5.dp)
                    } else {
                        Box(
                            Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isGenerating) {
                        Text(
                            text = "回复中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
                if (otherRunningCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    RunningElsewherePill(count = otherRunningCount, onClick = onOpenDrawer)
                }
            }
        }
        KujiraIconButton(
            icon = Icons.Outlined.Add,
            contentDescription = "新建会话",
            onClick = onNewChat,
            size = 42.dp,
        )
        if (onOpenSettings != null) {
            KujiraIconButton(
                icon = Icons.Outlined.Settings,
                contentDescription = "设置",
                onClick = onOpenSettings,
                size = 42.dp,
            )
        }
    }
}

/** Tappable "N 个会话运行中" pill: other conversations are still working. */
@Composable
private fun RunningElsewherePill(count: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
        contentColor = MaterialTheme.colorScheme.tertiary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PulsingDot(color = MaterialTheme.colorScheme.tertiary, size = 5.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                text = "另有 $count 个运行中",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/** A small dot that breathes; used wherever something is live. */
@Composable
private fun PulsingDot(color: Color, size: Dp) {
    val transition = rememberInfiniteTransition(label = "pulsing-dot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulsing-dot-alpha",
    )
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

private fun greetingForNow(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        hour < 5 -> "夜深了"
        hour < 11 -> "早上好"
        hour < 14 -> "中午好"
        hour < 18 -> "下午好"
        else -> "晚上好"
    }
}

@Composable
private fun ChatEmptyState(
    suggestions: List<ChatSuggestion>,
    onSuggestionClick: (ChatSuggestion) -> Unit,
) {
    val greeting = remember { greetingForNow() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        Text(
            text = buildAnnotatedString {
                append("Kujira")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                    append(".")
                }
            },
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "$greeting，今天想让我帮你做点什么？",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            suggestions.chunked(2).forEach { rowSuggestions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowSuggestions.forEach { suggestion ->
                        SuggestionCard(
                            suggestion = suggestion,
                            modifier = Modifier.weight(1f),
                            onClick = { onSuggestionClick(suggestion) },
                        )
                    }
                    if (rowSuggestions.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "每个会话独立运行：新建会话，就能并行处理多个任务",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SuggestionCard(
    suggestion: ChatSuggestion,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 92.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(
                imageVector = suggestion.icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = suggestion.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(name = "Chat dark", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun ChatScreenDarkPreview() {
    KujiraTheme(darkTheme = true) {
        ChatScreen(
            messages = previewMessages,
            conversationTitle = "服务器状态检查",
            modelLabel = "DeepSeek V3.2",
            pluginsLabel = "2 个插件已连接",
            otherRunningCount = 1,
            onOpenModelPicker = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Chat generating dark", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun ChatGeneratingDarkPreview() {
    KujiraTheme(darkTheme = true) {
        ChatScreen(
            messages = previewMessages,
            conversationTitle = "服务器状态检查",
            modelLabel = "DeepSeek V3.2",
            isGenerating = true,
            otherRunningCount = 2,
            onOpenModelPicker = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Chat empty dark", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun ChatEmptyDarkPreview() {
    KujiraTheme(darkTheme = true) {
        ChatScreen(
            messages = emptyList(),
            modelLabel = "DeepSeek V3.2",
            onOpenModelPicker = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Chat light", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun ChatScreenLightPreview() {
    KujiraTheme(darkTheme = false) {
        ChatScreen(
            messages = listOf(
                previewUserMessage("帮我看看服务器状态"),
                previewAssistantMessage(
                    blocks = listOf(ContentBlock.Text(previewAssistantMarkdown)),
                    id = "light-chat-reply",
                ),
            ),
            conversationTitle = "服务器状态",
            modelLabel = "Claude Sonnet 4.5",
            onOpenModelPicker = {},
        )
    }
}
