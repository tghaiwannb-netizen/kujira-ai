package app.kujira.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kujira.android.ui.components.KujiraChip
import app.kujira.android.ui.components.KujiraPreviewBox

/**
 * Chat input: model chip row on top, a soft 24dp composer surface with a
 * self-growing multiline text field (up to [maxLines]) and a circular
 * send/stop button.
 *
 * Pure UI: the draft is hoisted so the Lead can store it in a ViewModel if
 * wanted. Haptic feedback fires on send and stop.
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    isGenerating: Boolean = false,
    onStop: () -> Unit = {},
    modelLabel: String = "DeepSeek V3.2",
    onModelClick: (() -> Unit)? = null,
    pluginsLabel: String? = null,
    pluginsHealthy: Boolean = true,
    onPluginsClick: (() -> Unit)? = null,
    placeholder: String = "给 Kujira 发消息…",
    maxLines: Int = 6,
    showDisclaimer: Boolean = true,
    hapticsEnabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val canSend = value.isNotBlank() && !isGenerating

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 2.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KujiraChip(
                label = modelLabel,
                leadingIcon = Icons.Outlined.Bolt,
                trailingIcon = if (onModelClick != null) Icons.Outlined.ArrowDropDown else null,
                onClick = onModelClick,
            )
            if (pluginsLabel != null) {
                KujiraChip(
                    label = pluginsLabel,
                    leadingDotColor = if (pluginsHealthy) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    onClick = onPluginsClick,
                )
            }
        }

        Spacer(Modifier.size(6.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 36.dp)
                        .padding(vertical = 8.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = maxLines,
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                ComposerActionButton(
                    isGenerating = isGenerating,
                    canSend = canSend,
                    onSend = {
                        if (hapticsEnabled) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        onSend()
                    },
                    onStop = {
                        if (hapticsEnabled) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        onStop()
                    },
                )
            }
        }

        if (showDisclaimer) {
            Text(
                text = "Kujira 可能会出错，重要信息请核对。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ComposerActionButton(
    isGenerating: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val enabled = isGenerating || canSend
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(enabled = enabled) {
                if (isGenerating) onStop() else onSend()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isGenerating) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
            contentDescription = if (isGenerating) "停止生成" else "发送",
            modifier = Modifier.size(if (isGenerating) 18.dp else 20.dp),
            tint = contentColor,
        )
    }
}

@Preview(name = "Composer dark", showBackground = true, widthDp = 400)
@Composable
private fun ComposerDarkPreview() {
    KujiraPreviewBox(dark = true) {
        Composer(
            value = "帮我看看服务器状态，列出 CPU、内存和磁盘占用",
            onValueChange = {},
            onSend = {},
            modelLabel = "DeepSeek V3.2",
            onModelClick = {},
            pluginsLabel = "2 个插件已连接",
            onPluginsClick = {},
        )
    }
}

@Preview(name = "Composer light", showBackground = true, widthDp = 400)
@Composable
private fun ComposerLightPreview() {
    KujiraPreviewBox(dark = false) {
        Composer(
            value = "",
            onValueChange = {},
            onSend = {},
            modelLabel = "Claude Sonnet 4.5",
            onModelClick = {},
        )
    }
}

@Preview(name = "Composer generating dark", showBackground = true, widthDp = 400)
@Composable
private fun ComposerGeneratingDarkPreview() {
    KujiraPreviewBox(dark = true) {
        Composer(
            value = "正在生成中的草稿",
            onValueChange = {},
            onSend = {},
            isGenerating = true,
            onStop = {},
            modelLabel = "DeepSeek R1",
            onModelClick = {},
        )
    }
}
