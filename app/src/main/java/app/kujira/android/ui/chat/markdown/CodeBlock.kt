package app.kujira.android.ui.chat.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kujira.android.ui.components.KujiraPreviewBox
import app.kujira.android.ui.components.StreamingCursor
import app.kujira.android.ui.components.rememberClipboardCopy
import kotlinx.coroutines.delay

/*
 * Code blocks stay dark in both themes on purpose (spec: #0A0A0C) — a dark
 * island reads as "code" instantly and gives the ember accent somewhere to
 * glow. Colors are fixed rather than theme-derived because the container is
 * always dark.
 */
private val CodeBackground = Color(0xFF0A0A0C)
private val CodeHeaderDivider = Color(0xFF1E1F24)
private val CodeTextColor = Color(0xFFE8E8ED)
private val CodeMutedColor = Color(0xFF8B8C96)
private val CodeAccentColor = Color(0xFFEC8E72)

/**
 * Fenced code block: language label, copy button, horizontal scrolling,
 * monospace body, capped height with vertical scroll for long snippets.
 */
@Composable
fun CodeBlockView(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    onCopy: ((String) -> Unit)? = null,
) {
    val clipboardCopy = rememberClipboardCopy()
    val copy: (String) -> Unit = onCopy ?: clipboardCopy
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CodeBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language?.trim()?.takeIf { it.isNotEmpty() } ?: "text",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    letterSpacing = 0.6.sp,
                ),
                color = CodeMutedColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isStreaming) {
                StreamingCursor(
                    modifier = Modifier.padding(end = 10.dp),
                    color = CodeAccentColor,
                    width = 2.dp,
                    height = 12.dp,
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = code.isNotEmpty()) {
                        copy(code)
                        copied = true
                    }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = if (copied) "已复制" else "复制代码",
                    modifier = Modifier.size(13.dp),
                    tint = if (copied) CodeAccentColor else CodeMutedColor,
                )
                Text(
                    text = if (copied) "已复制" else "复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) CodeAccentColor else CodeMutedColor,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(CodeHeaderDivider)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .verticalScroll(verticalScroll),
        ) {
            Box(
                modifier = Modifier
                    .horizontalScroll(horizontalScroll)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(
                    text = code.replace("\t", "    ").trimEnd('\n'),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = CodeTextColor,
                    softWrap = false,
                )
            }
        }
    }
}

@Preview(name = "CodeBlock dark", showBackground = true)
@Composable
private fun CodeBlockDarkPreview() {
    KujiraPreviewBox(dark = true) {
        CodeBlockView(
            code = """
                fun greet(name: String): String {
                    return "你好，${'$'}name"
                }
            """.trimIndent(),
            language = "kotlin",
        )
    }
}

@Preview(name = "CodeBlock light", showBackground = true)
@Composable
private fun CodeBlockLightPreview() {
    KujiraPreviewBox(dark = false) {
        CodeBlockView(
            code = "curl -s https://mcp.sentinelx.app/mcp/mcp \\\n  -H 'Authorization: Bearer ...'",
            language = "bash",
            isStreaming = true,
        )
    }
}
