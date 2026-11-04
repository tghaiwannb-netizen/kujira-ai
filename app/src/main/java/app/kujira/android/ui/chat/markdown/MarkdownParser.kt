package app.kujira.android.ui.chat.markdown

/*
 * A small hand-written Markdown parser tuned for chat streaming.
 *
 * Design goals, in order:
 *  1. Never throw on incomplete input — a token that never closes is emitted as
 *     literal text, and an unclosed code fence still renders as a code block.
 *  2. Emit a flat list of blocks that Compose can render lazily per block, so a
 *     streaming message can grow without re-laying-out the whole transcript.
 *  3. Pure Kotlin. No Compose imports, so the parser can be unit-tested on the
 *     JVM and reused outside the UI layer.
 *
 * Deliberately unsupported: nested block quotes, HTML, footnotes, reference
 * links, setext headings. Those cost a lot of code and buy nothing for chat.
 */

/** Parsed block-level Markdown. */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    data class Paragraph(val text: String) : MarkdownBlock

    data class Quote(val text: String) : MarkdownBlock

    data class CodeBlock(
        val language: String?,
        val code: String,
        val closed: Boolean = true,
    ) : MarkdownBlock

    data class ListBlock(
        val ordered: Boolean,
        val start: Int,
        val items: List<MarkdownListItem>,
    ) : MarkdownBlock

    data object Divider : MarkdownBlock

    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val alignments: List<TableAlignment>,
    ) : MarkdownBlock
}

/** One list item. [indent] is a nesting level (0 = top-level). */
data class MarkdownListItem(
    val content: String,
    val indent: Int = 0,
    val number: Int? = null,
)

enum class TableAlignment { NONE, LEFT, CENTER, RIGHT }

object MarkdownParser {

    private val headingRegex = Regex("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?[ \\t]*$")
    private val headingClosingHashes = Regex("\\s+#+\\s*$")
    private val listItemRegex = Regex("^(\\s*)([-*+]|\\d{1,3}[.)])\\s+(.*)$")
    private val orderedMarkerRegex = Regex("^\\d{1,3}[.)]$")
    private val quoteRegex = Regex("^\\s*>\\s?(.*)$")
    private val fenceRegex = Regex("^\\s*(`{3,}|~{3,})\\s*([^\\s`]*)\\s*$")
    private val dividerRegex = Regex("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$")
    private val tableSeparatorRegex = Regex("^\\s*\\|?\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    private val separatorCellRegex = Regex("^:?-+:?$")

    /** Parses [source] into renderable blocks. Tolerates truncated input. */
    fun parse(source: String): List<MarkdownBlock> {
        if (source.isEmpty()) return emptyList()
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            val text = paragraph.joinToString("\n").trim()
            paragraph.clear()
            if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(text)
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                flushParagraph()
                i++
                continue
            }

            // ---- fenced code -------------------------------------------------
            val fence = fenceRegex.matchEntire(line)
            if (fence != null) {
                flushParagraph()
                val fenceToken = fence.groupValues[1]
                val fenceChar = fenceToken[0]
                val fenceLength = fenceToken.length
                val language = fence.groupValues[2].ifBlank { null }
                val code = StringBuilder()
                var closed = false
                i++
                while (i < lines.size) {
                    if (isClosingFence(lines[i], fenceChar, fenceLength)) {
                        closed = true
                        i++
                        break
                    }
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks += MarkdownBlock.CodeBlock(
                    language = language,
                    code = code.toString(),
                    closed = closed,
                )
                continue
            }

            // ---- heading -----------------------------------------------------
            val heading = headingRegex.matchEntire(line)
            if (heading != null) {
                flushParagraph()
                val text = heading.groupValues[2]
                    .trim()
                    .replace(headingClosingHashes, "")
                blocks += MarkdownBlock.Heading(level = heading.groupValues[1].length, text = text)
                i++
                continue
            }

            // ---- thematic break ---------------------------------------------
            if (dividerRegex.matches(line)) {
                flushParagraph()
                blocks += MarkdownBlock.Divider
                i++
                continue
            }

            // ---- block quote -------------------------------------------------
            if (quoteRegex.matches(line)) {
                flushParagraph()
                val quoted = mutableListOf<String>()
                while (i < lines.size) {
                    val match = quoteRegex.matchEntire(lines[i]) ?: break
                    quoted += match.groupValues[1].trimEnd()
                    i++
                }
                blocks += MarkdownBlock.Quote(text = quoted.joinToString("\n").trim())
                continue
            }

            // ---- table -------------------------------------------------------
            if (line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
                flushParagraph()
                val header = parseTableRow(line)
                if (header.isNotEmpty()) {
                    val alignments = parseTableAlignments(lines[i + 1])
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size) {
                        val row = lines[i]
                        if (row.isBlank() || !row.contains('|')) break
                        rows += padRow(parseTableRow(row), header.size)
                        i++
                    }
                    blocks += MarkdownBlock.Table(
                        header = header,
                        rows = rows,
                        alignments = List(header.size) { index ->
                            alignments.getOrElse(index) { TableAlignment.NONE }
                        },
                    )
                    continue
                }
            }

            // ---- list --------------------------------------------------------
            val listItem = listItemRegex.matchEntire(line)
            if (listItem != null) {
                flushParagraph()
                val ordered = orderedMarkerRegex.matches(listItem.groupValues[2])
                val start = if (ordered) {
                    listItem.groupValues[2].dropLast(1).toIntOrNull() ?: 1
                } else {
                    1
                }
                val items = mutableListOf<MarkdownListItem>()
                while (i < lines.size) {
                    val current = lines[i]
                    val itemMatch = listItemRegex.matchEntire(current)
                    if (itemMatch != null) {
                        val currentOrdered = orderedMarkerRegex.matches(itemMatch.groupValues[2])
                        if (currentOrdered != ordered) break
                        val leading = itemMatch.groupValues[1].replace("\t", "  ")
                        val indent = (leading.length / 2).coerceIn(0, 3)
                        val number = if (ordered) {
                            itemMatch.groupValues[2].dropLast(1).toIntOrNull()
                        } else {
                            null
                        }
                        items += MarkdownListItem(
                            content = itemMatch.groupValues[3].trimEnd(),
                            indent = indent,
                            number = number,
                        )
                        i++
                    } else if (
                        current.isNotBlank() &&
                        current.first().isWhitespace() &&
                        items.isNotEmpty() &&
                        fenceRegex.matchEntire(current) == null
                    ) {
                        // Indented continuation line of the previous item.
                        val last = items.removeAt(items.lastIndex)
                        items += last.copy(content = last.content + "\n" + current.trim())
                        i++
                    } else {
                        break
                    }
                }
                if (items.isNotEmpty()) {
                    blocks += MarkdownBlock.ListBlock(
                        ordered = ordered,
                        start = start,
                        items = items,
                    )
                    continue
                }
            }

            // ---- paragraph ----------------------------------------------------
            paragraph += trimmed
            i++
        }
        flushParagraph()
        return blocks
    }

    private fun isClosingFence(line: String, char: Char, minLength: Int): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < minLength) return false
        var index = 0
        while (index < trimmed.length && trimmed[index] == char) index++
        return index >= minLength && index == trimmed.length
    }

    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('|') || !trimmed.contains('-')) return false
        if (!tableSeparatorRegex.matches(trimmed)) return false
        val cells = trimmed.removePrefix("|").removeSuffix("|").split('|')
        return cells.isNotEmpty() && cells.all { separatorCellRegex.matches(it.trim()) }
    }

    private fun parseTableAlignments(line: String): List<TableAlignment> {
        val cells = line.trim().removePrefix("|").removeSuffix("|").split('|')
        return cells.map { cell ->
            val trimmed = cell.trim()
            when {
                trimmed.startsWith(":") && trimmed.endsWith(":") -> TableAlignment.CENTER
                trimmed.startsWith(":") -> TableAlignment.LEFT
                trimmed.endsWith(":") -> TableAlignment.RIGHT
                else -> TableAlignment.NONE
            }
        }
    }

    private fun parseTableRow(line: String): List<String> {
        var text = line.trim()
        if (text.startsWith("|")) text = text.substring(1)
        if (text.endsWith("|")) text = text.dropLast(1)
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (ch in text) {
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }

                ch == '\\' -> escaped = true
                ch == '|' -> {
                    cells += current.toString().trim()
                    current.clear()
                }

                else -> current.append(ch)
            }
        }
        cells += current.toString().trim()
        return cells
    }

    private fun padRow(cells: List<String>, count: Int): List<String> = when {
        cells.size == count -> cells
        cells.size > count -> cells.take(count)
        else -> cells + List(count - cells.size) { "" }
    }
}
