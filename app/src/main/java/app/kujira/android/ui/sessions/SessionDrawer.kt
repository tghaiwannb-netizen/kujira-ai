package app.kujira.android.ui.sessions

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kujira.android.data.model.Conversation
import app.kujira.android.ui.components.EmptyState
import app.kujira.android.ui.components.KujiraButton
import app.kujira.android.ui.components.KujiraButtonStyle
import app.kujira.android.ui.components.KujiraIconButton
import app.kujira.android.ui.components.KujiraTextInputDialog
import app.kujira.android.ui.theme.KujiraTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** A date-grouped bucket of conversations, rendered as one section. */
data class SessionGroup(
    val title: String,
    val conversations: List<Conversation>,
)

/**
 * Groups conversations into 置顶 / 今天 / 昨天 / 近 7 天 / 更早.
 * Pure function so it can be unit-tested without Compose.
 */
fun groupConversations(
    conversations: List<Conversation>,
    now: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<SessionGroup> {
    if (conversations.isEmpty()) return emptyList()
    val sorted = conversations.sortedWith(
        compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt }
    )
    val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
    val yesterday = today.minusDays(1)
    val weekStart = today.minusDays(7)

    val groups = mutableListOf<SessionGroup>()
    val pinned = sorted.filter { it.pinned }
    if (pinned.isNotEmpty()) groups += SessionGroup("置顶", pinned)

    val buckets = linkedMapOf(
        "今天" to mutableListOf<Conversation>(),
        "昨天" to mutableListOf(),
        "近 7 天" to mutableListOf(),
        "更早" to mutableListOf(),
    )
    sorted.filterNot { it.pinned }.forEach { conversation ->
        val date = Instant.ofEpochMilli(conversation.updatedAt).atZone(zoneId).toLocalDate()
        val bucket = when {
            date == today -> "今天"
            date == yesterday -> "昨天"
            date.isAfter(weekStart) -> "近 7 天"
            else -> "更早"
        }
        buckets.getValue(bucket) += conversation
    }
    buckets.forEach { (title, items) ->
        if (items.isNotEmpty()) groups += SessionGroup(title, items)
    }
    return groups
}

/** Short human label for a conversation row, e.g. "14:20" / "昨天" / "3 天前". */
fun formatSessionTimestamp(
    updatedAt: Long,
    now: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val updated = Instant.ofEpochMilli(updatedAt).atZone(zoneId)
    val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
    val date = updated.toLocalDate()
    return when {
        date == today -> DateTimeFormatter.ofPattern("HH:mm").format(updated)
        date == today.minusDays(1) -> "昨天"
        date.isAfter(today.minusDays(7)) -> "${ChronoUnit.DAYS.between(date, today)} 天前"
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("M月d日"))
        else -> date.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    }
}

/**
 * Wraps the app content in a [ModalNavigationDrawer] with the session list.
 *
 * Renaming is handled in-drawer via a dialog; deletion is swipe-to-delete or
 * the row overflow menu. Delete is a plain callback — the Lead should remove
 * the row from state and show the undo snackbar.
 *
 * Several conversations can generate at once: rows whose id is in
 * [generatingIds] show a live "生成中…" marker instead of their timestamp, and
 * their overflow menu offers "停止生成" when [onStopConversation] is provided.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDrawerHost(
    conversations: List<Conversation>,
    currentConversationId: String?,
    drawerState: DrawerState,
    onNewConversation: () -> Unit,
    onSelectConversation: (Conversation) -> Unit,
    onRenameConversation: (Conversation, String) -> Unit,
    onDeleteConversation: (Conversation) -> Unit,
    onTogglePin: (Conversation) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    generatingIds: Set<String> = emptySet(),
    onStopConversation: ((Conversation) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet(
                drawerState = drawerState,
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .widthIn(max = 340.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                SessionDrawerContent(
                    conversations = conversations,
                    currentConversationId = currentConversationId,
                    onNewConversation = onNewConversation,
                    onSelectConversation = onSelectConversation,
                    onRenameConversation = onRenameConversation,
                    onDeleteConversation = onDeleteConversation,
                    onTogglePin = onTogglePin,
                    onOpenSettings = onOpenSettings,
                    generatingIds = generatingIds,
                    onStopConversation = onStopConversation,
                )
            }
        },
        content = content,
    )
}

@Composable
internal fun SessionDrawerContent(
    conversations: List<Conversation>,
    currentConversationId: String?,
    onNewConversation: () -> Unit,
    onSelectConversation: (Conversation) -> Unit,
    onRenameConversation: (Conversation, String) -> Unit,
    onDeleteConversation: (Conversation) -> Unit,
    onTogglePin: (Conversation) -> Unit,
    onOpenSettings: (() -> Unit)?,
    generatingIds: Set<String> = emptySet(),
    onStopConversation: ((Conversation) -> Unit)? = null,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    val now = remember { System.currentTimeMillis() }

    val filtered = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) {
            conversations
        } else {
            conversations.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) }
        }
    }
    val groups = remember(filtered, now) { groupConversations(filtered, now) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildAnnotatedString {
                    append("Kujira")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(".") }
                },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (onOpenSettings != null) {
                KujiraIconButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = "设置",
                    onClick = onOpenSettings,
                    size = 40.dp,
                )
            }
        }

        KujiraButton(
            text = "新建会话",
            onClick = onNewConversation,
            style = KujiraButtonStyle.TONAL,
            leadingIcon = Icons.Outlined.Add,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )

        SessionSearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(10.dp))

        if (groups.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.ChatBubbleOutline,
                title = if (searchQuery.isBlank()) "还没有会话" else "没有找到会话",
                subtitle = if (searchQuery.isBlank()) {
                    "新建一个会话，和 Kujira 聊点什么"
                } else {
                    "换个关键词试试"
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            ) {
                groups.forEach { group ->
                    item(key = "header-${group.title}") {
                        SessionGroupHeader(title = group.title, count = group.conversations.size)
                    }
                    items(
                        items = group.conversations,
                        key = { conversation -> conversation.id },
                    ) { conversation ->
                        SwipeToDeleteSessionItem(
                            conversation = conversation,
                            selected = conversation.id == currentConversationId,
                            generating = conversation.id in generatingIds,
                            now = now,
                            onSelect = { onSelectConversation(conversation) },
                            onRename = {
                                renameTarget = conversation
                                renameDraft = conversation.title
                            },
                            onDelete = { onDeleteConversation(conversation) },
                            onTogglePin = { onTogglePin(conversation) },
                            onStop = onStopConversation?.let { stop -> { stop(conversation) } },
                        )
                    }
                    item(key = "spacer-${group.title}") {
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        if (onOpenSettings != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    renameTarget?.let { target ->
        KujiraTextInputDialog(
            title = "重命名会话",
            value = renameDraft,
            onValueChange = { renameDraft = it },
            onConfirm = {
                val newTitle = renameDraft.trim()
                if (newTitle.isNotEmpty()) onRenameConversation(target, newTitle)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
            label = "会话名称",
        )
    }
}

@Composable
private fun SessionSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索会话…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                KujiraIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = "清空搜索",
                    onClick = { onQueryChange("") },
                    size = 28.dp,
                    iconSize = 16.dp,
                )
            }
        }
    }
}

@Composable
private fun SessionGroupHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count > 0) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteSessionItem(
    conversation: Conversation,
    selected: Boolean,
    generating: Boolean,
    now: Long,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onStop: (() -> Unit)?,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除会话",
                    modifier = Modifier
                        .padding(end = 18.dp)
                        .size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    ) {
        SessionRow(
            conversation = conversation,
            selected = selected,
            generating = generating,
            now = now,
            onSelect = onSelect,
            onRename = onRename,
            onDelete = onDelete,
            onTogglePin = onTogglePin,
            onStop = onStop,
        )
    }
}

/** A small dot that breathes; marks a conversation that is generating right now. */
@Composable
private fun GeneratingDot() {
    val transition = rememberInfiniteTransition(label = "session-generating")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "session-generating-alpha",
    )
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    )
}

@Composable
private fun SessionRow(
    conversation: Conversation,
    selected: Boolean,
    generating: Boolean,
    now: Long,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onStop: (() -> Unit)?,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 1.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        Color.Transparent
                    }
                )
                .clickable(onClick = onSelect)
                .padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.pinned) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = "已置顶",
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(12.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = conversation.title.ifBlank { "未命名会话" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                if (generating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GeneratingDot()
                        Spacer(Modifier.size(5.dp))
                        Text(
                            text = "生成中…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    Text(
                        text = formatSessionTimestamp(updatedAt = conversation.updatedAt, now = now),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }
            Box {
                KujiraIconButton(
                    icon = Icons.Outlined.MoreVert,
                    contentDescription = "会话操作",
                    onClick = { menuExpanded = true },
                    size = 32.dp,
                    iconSize = 17.dp,
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    shape = RoundedCornerShape(14.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    if (generating && onStop != null) {
                        DropdownMenuItem(
                            text = { Text("停止生成") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onStop()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (conversation.pinned) "取消置顶" else "置顶") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.PushPin,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onTogglePin()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

private fun previewConversations(): List<Conversation> {
    val now = System.currentTimeMillis()
    val hour = 60L * 60L * 1000L
    val day = 24L * hour
    return listOf(
        Conversation(
            id = "c1",
            title = "服务器状态检查",
            updatedAt = now - hour,
            pinned = true,
        ),
        Conversation(
            id = "c2",
            title = "nginx 日志里的 502 排查",
            updatedAt = now - 2 * hour,
        ),
        Conversation(
            id = "c3",
            title = "Cloudflare DNS 记录整理",
            updatedAt = now - 20 * hour,
        ),
        Conversation(
            id = "c4",
            title = "给 Kujira 写一个重试函数",
            updatedAt = now - 3 * day,
        ),
        Conversation(
            id = "c5",
            title = "SentinelX 主机列表",
            updatedAt = now - 12 * day,
        ),
    )
}

@Preview(name = "SessionDrawer dark", showBackground = true, widthDp = 340, heightDp = 780)
@Composable
private fun SessionDrawerDarkPreview() {
    KujiraTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            SessionDrawerContent(
                conversations = previewConversations(),
                currentConversationId = "c1",
                onNewConversation = {},
                onSelectConversation = {},
                onRenameConversation = { _, _ -> },
                onDeleteConversation = {},
                onTogglePin = {},
                onOpenSettings = {},
                generatingIds = setOf("c2", "c4"),
                onStopConversation = {},
            )
        }
    }
}

@Preview(name = "SessionDrawer light", showBackground = true, widthDp = 340, heightDp = 780)
@Composable
private fun SessionDrawerLightPreview() {
    KujiraTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            SessionDrawerContent(
                conversations = previewConversations().take(3),
                currentConversationId = "c2",
                onNewConversation = {},
                onSelectConversation = {},
                onRenameConversation = { _, _ -> },
                onDeleteConversation = {},
                onTogglePin = {},
                onOpenSettings = {},
            )
        }
    }
}
