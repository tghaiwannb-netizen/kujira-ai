package app.kujira.android.ui.chat.markdown

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kujira.android.ui.components.KujiraPreviewBox
import app.kujira.android.ui.components.StreamingCursor

/**
 * Theme-derived text styles for one Markdown block tree. Computed once per
 * (style, color, theme) and passed down so every block stays in sync.
 */
internal data class MarkdownStyles(
    val paragraph: TextStyle,
    val heading1: TextStyle,
    val heading2: TextStyle,
    val heading3: TextStyle,
    val heading4: TextStyle,
    val heading5: TextStyle,
    val heading6: TextStyle,
    val quote: TextStyle,
    val listMarker: TextStyle,
    val listText: TextStyle,
    val tableHeader: TextStyle,
    val tableCell: TextStyle,
    val inlineCode: SpanStyle,
    val linkColor: Color,
    val dividerColor: Color,
    val quoteBarColor: Color,
    val cursorColor: Color,
    val codeFontEnabled: Boolean,
)

@Composable
internal fun rememberMarkdownStyles(
    style: TextStyle,
    color: Color,
    codeFontEnabled: Boolean,
): MarkdownStyles {
    val typography = MaterialTheme.typography
    val scheme = MaterialTheme.colorScheme
    return remember(style, color, codeFontEnabled, typography, scheme) {
        val paragraph = style.copy(color = color)
        val monoFamily = if (codeFontEnabled) FontFamily.Monospace else style.fontFamily
        MarkdownStyles(
            paragraph = paragraph,
            heading1 = typography.headlineMedium.copy(color = color),
            heading2 = typography.headlineSmall.copy(color = color),
            heading3 = typography.titleLarge.copy(color = color),
            heading4 = typography.titleMedium.copy(color = color),
            heading5 = typography.titleSmall.copy(color = color),
            heading6 = typography.titleSmall.copy(color = scheme.onSurfaceVariant),
            quote = paragraph.copy(color = scheme.onSurfaceVariant),
            listMarker = paragraph.copy(color = scheme.onSurfaceVariant),
            listText = paragraph,
            tableHeader = typography.labelLarge.copy(color = color, fontWeight = FontWeight.SemiBold),
            tableCell = typography.bodyMedium.copy(color = color),
            inlineCode = SpanStyle(
                fontFamily = monoFamily,
                fontSize = style.fontSize.times(0.92f),
                background = scheme.surfaceContainerHigh,
                color = color,
            ),
            linkColor = scheme.primary,
            dividerColor = scheme.outlineVariant.copy(alpha = 0.7f),
            quoteBarColor = scheme.primary.copy(alpha = 0.45f),
            cursorColor = scheme.primary,
            codeFontEnabled = codeFontEnabled,
        )
    }
}

/**
 * Renders Markdown as native Compose text.
 *
 * [isStreaming] turns on the blinking cursor and tells the code block that its
 * fence has not closed yet. The parser is remember-keyed on the raw string, so
 * a streaming message only re-parses when a new chunk actually arrives.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    isStreaming: Boolean = false,
    codeFontEnabled: Boolean = true,
    onCodeCopy: ((String) -> Unit)? = null,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    val styles = rememberMarkdownStyles(style = style, color = color, codeFontEnabled = codeFontEnabled)

    var cursorVisible = false
    if (isStreaming) {
        val transition = rememberInfiniteTransition(label = "markdown-cursor")
        val phase by transition.animateFloat(
            initialValue = 0.1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 560),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "markdown-cursor-alpha",
        )
        cursorVisible = phase > 0.4f
    }

    val lastIndex = blocks.lastIndex
    val inlineCursorOnLast = isStreaming && lastIndex >= 0 && blocks[lastIndex].isInlineRenderable()
    val showFallbackCursor = isStreaming && lastIndex >= 0 && !blocks[lastIndex].isInlineRenderable() &&
        blocks[lastIndex] !is MarkdownBlock.CodeBlock

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            MarkdownBlockView(
                block = block,
                styles = styles,
                modifier = Modifier.padding(top = blockTopSpacing(index, block)),
                showCursor = inlineCursorOnLast && index == lastIndex && cursorVisible,
                isStreamingCode = isStreaming && index == lastIndex && block is MarkdownBlock.CodeBlock,
                onCodeCopy = onCodeCopy,
            )
        }
        if (showFallbackCursor) {
            StreamingCursor(modifier = Modifier.padding(top = 8.dp), height = 14.dp)
        }
    }
}

private fun MarkdownBlock.isInlineRenderable(): Boolean = when (this) {
    is MarkdownBlock.Heading,
    is MarkdownBlock.Paragraph,
    is MarkdownBlock.Quote,
    is MarkdownBlock.ListBlock -> true

    is MarkdownBlock.CodeBlock,
    is MarkdownBlock.Table,
    MarkdownBlock.Divider -> false
}

private fun blockTopSpacing(index: Int, block: MarkdownBlock): Dp = when {
    index == 0 -> 0.dp
    block is MarkdownBlock.Heading -> 14.dp
    block is MarkdownBlock.Divider -> 12.dp
    else -> 10.dp
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    styles: MarkdownStyles,
    modifier: Modifier,
    showCursor: Boolean,
    isStreamingCode: Boolean,
    onCodeCopy: ((String) -> Unit)?,
) {
    when (block) {
        is MarkdownBlock.Heading -> MarkdownInlineText(
            text = block.text,
            style = when (block.level) {
                1 -> styles.heading1
                2 -> styles.heading2
                3 -> styles.heading3
                4 -> styles.heading4
                5 -> styles.heading5
                else -> styles.heading6
            },
            styles = styles,
            modifier = modifier,
            showCursor = showCursor,
        )

        is MarkdownBlock.Paragraph -> MarkdownInlineText(
            text = block.text,
            style = styles.paragraph,
            styles = styles,
            modifier = modifier,
            showCursor = showCursor,
        )

        is MarkdownBlock.Quote -> Row(
            modifier = modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(styles.quoteBarColor)
            )
            Spacer(Modifier.width(10.dp))
            MarkdownInlineText(
                text = block.text,
                style = styles.quote,
                styles = styles,
                modifier = Modifier.weight(1f),
                showCursor = showCursor,
            )
        }

        is MarkdownBlock.CodeBlock -> CodeBlockView(
            code = block.code,
            language = block.language,
            modifier = modifier,
            isStreaming = isStreamingCode,
            onCopy = onCodeCopy,
        )

        is MarkdownBlock.ListBlock -> MarkdownList(
            block = block,
            styles = styles,
            modifier = modifier,
            showCursor = showCursor,
        )

        is MarkdownBlock.Table -> MarkdownTable(
            block = block,
            styles = styles,
            modifier = modifier,
        )

        MarkdownBlock.Divider -> HorizontalDivider(
            modifier = modifier.padding(vertical = 2.dp),
            color = styles.dividerColor,
        )
    }
}

@Composable
private fun MarkdownInlineText(
    text: String,
    style: TextStyle,
    styles: MarkdownStyles,
    modifier: Modifier = Modifier,
    showCursor: Boolean = false,
    textAlign: TextAlign? = null,
) {
    val annotated = remember(text, style, styles, showCursor) {
        buildInlineAnnotatedString(text = text, styles = styles, showCursor = showCursor)
    }
    Text(
        text = annotated,
        modifier = modifier.fillMaxWidth(),
        style = style,
        textAlign = textAlign,
    )
}

private fun buildInlineAnnotatedString(
    text: String,
    styles: MarkdownStyles,
    showCursor: Boolean = false,
): AnnotatedString {
    val nodes = InlineParser.parse(text)
    return buildAnnotatedString {
        appendInlineNodes(nodes, styles)
        if (showCursor) {
            withStyle(SpanStyle(color = styles.cursorColor)) { append("▍") }
        }
    }
}

private fun AnnotatedString.Builder.appendInlineNodes(
    nodes: List<InlineNode>,
    styles: MarkdownStyles,
) {
    nodes.forEach { node ->
        when (node) {
            is InlineNode.Text -> append(node.text)
            is InlineNode.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                appendInlineNodes(node.children, styles)
            }

            is InlineNode.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlineNodes(node.children, styles)
            }

            is InlineNode.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendInlineNodes(node.children, styles)
            }

            is InlineNode.Code -> withStyle(styles.inlineCode) {
                append(node.text)
            }

            is InlineNode.Link -> withLink(
                LinkAnnotation.Url(
                    url = node.url,
                    styles = TextLinkStyles(
                        SpanStyle(
                            color = styles.linkColor,
                            textDecoration = TextDecoration.Underline,
                        )
                    ),
                )
            ) {
                appendInlineNodes(node.children, styles)
            }
        }
    }
}

@Composable
private fun MarkdownList(
    block: MarkdownBlock.ListBlock,
    styles: MarkdownStyles,
    modifier: Modifier,
    showCursor: Boolean,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        block.items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (item.indent * 16).dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = if (block.ordered) {
                        "${item.number ?: (block.start + index)}."
                    } else {
                        "•"
                    },
                    style = styles.listMarker,
                    modifier = Modifier.widthIn(min = 22.dp),
                )
                MarkdownInlineText(
                    text = item.content,
                    style = styles.listText,
                    styles = styles,
                    modifier = Modifier.weight(1f),
                    showCursor = showCursor && index == block.items.lastIndex,
                )
            }
        }
    }
}

@Composable
private fun MarkdownTable(
    block: MarkdownBlock.Table,
    styles: MarkdownStyles,
    modifier: Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val columnWidths: List<Dp> = remember(block, styles, textMeasurer, density) {
        val cellWidthsPx = IntArray(block.header.size)
        val minWidthPx = with(density) { 72.dp.roundToPx() }
        val maxWidthPx = with(density) { 260.dp.roundToPx() }
        val paddingPx = with(density) { 20.dp.roundToPx() }

        fun measureRow(row: List<String>, style: TextStyle) {
            row.forEachIndexed { index, cell ->
                if (index >= cellWidthsPx.size) return@forEachIndexed
                val plain = flattenInlineText(InlineParser.parse(cell))
                val width = measureSingleLine(textMeasurer, plain, style)
                cellWidthsPx[index] = maxOf(cellWidthsPx[index], width + paddingPx)
            }
        }

        measureRow(block.header, styles.tableHeader)
        block.rows.forEach { measureRow(it, styles.tableCell) }
        cellWidthsPx.map { px ->
            with(density) { px.coerceIn(minWidthPx, maxWidthPx).toDp() }
        }
    }

    val horizontalScroll = rememberScrollState()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(Modifier.horizontalScroll(horizontalScroll)) {
            Column {
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
                    block.header.forEachIndexed { index, cell ->
                        MarkdownTableCell(
                            text = cell,
                            width = columnWidths.getOrElse(index) { 72.dp },
                            style = styles.tableHeader,
                            styles = styles,
                            alignment = block.alignments.getOrElse(index) { TableAlignment.NONE },
                        )
                    }
                }
                HorizontalDivider(color = styles.dividerColor)
                block.rows.forEachIndexed { rowIndex, row ->
                    Row {
                        row.forEachIndexed { index, cell ->
                            MarkdownTableCell(
                                text = cell,
                                width = columnWidths.getOrElse(index) { 72.dp },
                                style = styles.tableCell,
                                styles = styles,
                                alignment = block.alignments.getOrElse(index) { TableAlignment.NONE },
                            )
                        }
                    }
                    if (rowIndex != block.rows.lastIndex) {
                        HorizontalDivider(color = styles.dividerColor.copy(alpha = 0.45f))
                    }
                }
            }
        }
    }
}

private fun measureSingleLine(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
): Int {
    if (text.isEmpty()) return 0
    val layout = textMeasurer.measure(
        text = AnnotatedString(text),
        style = style,
        softWrap = false,
        maxLines = 1,
    )
    return layout.size.width
}

@Composable
private fun MarkdownTableCell(
    text: String,
    width: Dp,
    style: TextStyle,
    styles: MarkdownStyles,
    alignment: TableAlignment,
) {
    val textAlign = when (alignment) {
        TableAlignment.LEFT, TableAlignment.NONE -> TextAlign.Start
        TableAlignment.CENTER -> TextAlign.Center
        TableAlignment.RIGHT -> TextAlign.End
    }
    Box(
        modifier = Modifier
            .width(width)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        MarkdownInlineText(
            text = text,
            style = style,
            styles = styles,
            textAlign = textAlign,
        )
    }
}

private fun flattenInlineText(nodes: List<InlineNode>): String = buildString {
    fun appendNodes(items: List<InlineNode>) {
        items.forEach { node ->
            when (node) {
                is InlineNode.Text -> append(node.text)
                is InlineNode.Code -> append(node.text)
                is InlineNode.Bold -> appendNodes(node.children)
                is InlineNode.Italic -> appendNodes(node.children)
                is InlineNode.Strikethrough -> appendNodes(node.children)
                is InlineNode.Link -> appendNodes(node.children)
            }
        }
    }
    appendNodes(nodes)
}

private val previewMarkdown = """
    ## 服务器状态

    刚才检查了 **sentinelx** 主机，状态 *正常*。主要指标如下：

    - CPU 使用率：`12%`
    - 磁盘：**已用 68%**
    - 内存：还剩 1.2 GB

    > 提示：nginx 在上次重启后运行稳定，没有发现 5xx。

    ### 下一步

    1. 收紧 `ufw` 规则
    2. 清理旧日志
    3. [查看 SentinelX 文档](https://sentinelx.app)

    ```bash
    ssh admin@example.com 'systemctl status nginx'
    ```

    | 服务 | 状态 | 延迟 |
    |---|---:|---:|
    | nginx | 运行中 | 12ms |
    | postgres | 运行中 | 3ms |

    ---

    需要我继续处理吗？
""".trimIndent()

@Preview(name = "Markdown dark", showBackground = true, widthDp = 400)
@Composable
private fun MarkdownDarkPreview() {
    KujiraPreviewBox(dark = true) {
        MarkdownText(markdown = previewMarkdown)
    }
}

@Preview(name = "Markdown light", showBackground = true, widthDp = 400)
@Composable
private fun MarkdownLightPreview() {
    KujiraPreviewBox(dark = false) {
        MarkdownText(markdown = previewMarkdown)
    }
}

@Preview(name = "Markdown streaming dark", showBackground = true, widthDp = 400)
@Composable
private fun MarkdownStreamingDarkPreview() {
    KujiraPreviewBox(dark = true) {
        MarkdownText(
            markdown = "正在分析日志，稍等……\n\n- 第一条\n- 第二条 **加粗**",
            isStreaming = true,
        )
    }
}
