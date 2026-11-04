package app.kujira.android.ui.chat.markdown

/*
 * Inline Markdown parser. Produces a small tree of styled spans which the
 * renderer turns into an AnnotatedString. Any delimiter that does not close is
 * emitted as literal text, which is what makes streaming feel stable: a lone
 * "**" at the end of a partial message simply shows up as "**" until the model
 * finishes the bold run.
 */

sealed interface InlineNode {
    data class Text(val text: String) : InlineNode
    data class Bold(val children: List<InlineNode>) : InlineNode
    data class Italic(val children: List<InlineNode>) : InlineNode
    data class Strikethrough(val children: List<InlineNode>) : InlineNode
    data class Code(val text: String) : InlineNode
    data class Link(val children: List<InlineNode>, val url: String) : InlineNode
}

object InlineParser {

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!~|>"

    /** Parse the inline content of one line / paragraph. */
    fun parse(source: String): List<InlineNode> {
        if (source.isEmpty()) return emptyList()
        return parseRange(source, 0, source.length)
    }

    private fun parseRange(source: String, start: Int, end: Int): List<InlineNode> {
        val nodes = mutableListOf<InlineNode>()
        val plain = StringBuilder()
        var i = start

        fun flushText() {
            if (plain.isNotEmpty()) {
                nodes += InlineNode.Text(plain.toString())
                plain.clear()
            }
        }

        while (i < end) {
            val c = source[i]
            when {
                c == '\\' && i + 1 < end && ESCAPABLE.indexOf(source[i + 1]) >= 0 -> {
                    plain.append(source[i + 1])
                    i += 2
                }

                c == '`' -> {
                    val run = countRun(source, i, end, '`')
                    val fence = "`".repeat(run)
                    val close = findDelimiter(source, fence, i + run, end)
                    if (close > i + run) {
                        flushText()
                        nodes += InlineNode.Code(source.substring(i + run, close))
                        i = close + run
                    } else {
                        plain.append(fence)
                        i += run
                    }
                }

                c == '*' || c == '_' || c == '~' -> {
                    val doubled = c != '~' && i + 1 < end && source[i + 1] == c
                    val isStrike = c == '~' && i + 1 < end && source[i + 1] == '~'
                    val delimiter = when {
                        isStrike -> "~~"
                        doubled -> "$c$c"
                        c == '~' -> ""
                        else -> c.toString()
                    }
                    if (delimiter.isEmpty()) {
                        plain.append(c)
                        i++
                    } else {
                        val contentStart = i + delimiter.length
                        val boundaryOk = c != '_' || i == start || !source[i - 1].isLetterOrDigit()
                        val opens = boundaryOk &&
                            contentStart < end &&
                            !source[contentStart].isWhitespace()
                        val close = if (opens) {
                            findEmphasisClose(source, contentStart, end, c, delimiter.length == 2)
                        } else {
                            -1
                        }
                        if (close > contentStart) {
                            flushText()
                            val children = parseRange(source, contentStart, close)
                            nodes += when {
                                isStrike -> InlineNode.Strikethrough(children)
                                delimiter.length == 2 -> InlineNode.Bold(children)
                                else -> InlineNode.Italic(children)
                            }
                            i = close + delimiter.length
                        } else {
                            plain.append(delimiter)
                            i += delimiter.length
                        }
                    }
                }

                c == '[' -> {
                    val link = parseLink(source, i, end)
                    if (link != null) {
                        flushText()
                        nodes += link.first
                        i = link.second
                    } else {
                        plain.append(c)
                        i++
                    }
                }

                c == '<' -> {
                    val close = indexOfChar(source, '>', i + 1, end)
                    val candidate = if (close > i + 1) source.substring(i + 1, close) else ""
                    if (close > i + 1 && isAutoLink(candidate)) {
                        flushText()
                        nodes += InlineNode.Link(
                            children = listOf(InlineNode.Text(candidate.removePrefix("mailto:"))),
                            url = candidate,
                        )
                        i = close + 1
                    } else {
                        plain.append(c)
                        i++
                    }
                }

                (source.startsWith("https://", i) || source.startsWith("http://", i)) &&
                    (i == start || !source[i - 1].isLetterOrDigit()) -> {
                    var urlEnd = i
                    while (urlEnd < end && isAsciiUrlChar(source[urlEnd])) urlEnd++
                    val raw = source.substring(i, urlEnd)
                    val url = raw.trimTrailingUrlPunctuation()
                    if (url.length > "https://".length) {
                        flushText()
                        nodes += InlineNode.Link(
                            children = listOf(InlineNode.Text(url)),
                            url = url,
                        )
                        i += url.length
                    } else {
                        plain.append(c)
                        i++
                    }
                }

                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        flushText()
        return nodes
    }

    private fun countRun(source: String, start: Int, end: Int, char: Char): Int {
        var index = start
        while (index < end && source[index] == char) index++
        return index - start
    }

    private fun findDelimiter(source: String, delimiter: String, from: Int, end: Int): Int {
        if (from >= end) return -1
        val lastStart = end - delimiter.length
        var index = from
        while (index <= lastStart) {
            if (source[index] == '\\') {
                index += 2
                continue
            }
            if (source.startsWith(delimiter, index)) return index
            index++
        }
        return -1
    }

    private fun findEmphasisClose(
        source: String,
        from: Int,
        end: Int,
        char: Char,
        double: Boolean,
    ): Int {
        var index = from
        while (index < end) {
            val c = source[index]
            if (c == '\\') {
                index += 2
                continue
            }
            if (c == char) {
                val isDouble = index + 1 < end && source[index + 1] == char
                if (double) {
                    if (isDouble && index > from) return index
                    if (isDouble) {
                        index += 2
                        continue
                    }
                } else {
                    if (!isDouble && index > from && !source[index - 1].isWhitespace()) return index
                    if (isDouble) {
                        index += 2
                        continue
                    }
                }
            }
            index++
        }
        return -1
    }

    private fun indexOfChar(source: String, char: Char, from: Int, end: Int): Int {
        var index = from
        while (index < end) {
            if (source[index] == '\\') {
                index += 2
                continue
            }
            if (source[index] == char) return index
            index++
        }
        return -1
    }

    /** ASCII URL characters only: CJK punctuation must not be swallowed by a bare link. */
    private fun isAsciiUrlChar(char: Char): Boolean =
        char.code < 128 && (char.isLetterOrDigit() || char in "-._~:/?#[]@!$&'()*+,;=%")

    private fun isAutoLink(candidate: String): Boolean =
        candidate.startsWith("https://") ||
            candidate.startsWith("http://") ||
            candidate.startsWith("mailto:")

    private fun parseLink(source: String, bracketStart: Int, end: Int): Pair<InlineNode.Link, Int>? {
        var depth = 0
        var closeBracket = -1
        var index = bracketStart
        while (index < end) {
            val c = source[index]
            if (c == '\\') {
                index += 2
                continue
            }
            if (c == '[') depth++
            if (c == ']') {
                depth--
                if (depth == 0) {
                    closeBracket = index
                    break
                }
            }
            index++
        }
        if (closeBracket < 0 || closeBracket + 1 >= end || source[closeBracket + 1] != '(') return null

        var parenDepth = 1
        var cursor = closeBracket + 2
        var closeParen = -1
        while (cursor < end) {
            val c = source[cursor]
            if (c == '\\') {
                cursor += 2
                continue
            }
            if (c == '(') parenDepth++
            if (c == ')') {
                parenDepth--
                if (parenDepth == 0) {
                    closeParen = cursor
                    break
                }
            }
            cursor++
        }
        if (closeParen < 0) return null

        var url = source.substring(closeBracket + 2, closeParen).trim()
        if (url.isEmpty()) return null
        val space = url.indexOfFirst { it.isWhitespace() }
        if (space > 0) url = url.substring(0, space)
        url = url.removeSurrounding("<", ">")
        if (url.isEmpty()) return null

        return InlineNode.Link(
            children = parseRange(source, bracketStart + 1, closeBracket),
            url = url,
        ) to (closeParen + 1)
    }

    private fun String.trimTrailingUrlPunctuation(): String {
        var result = this
        while (result.isNotEmpty() && result.last() in ".,;:!?)]}>。，；：！？）】」』”’\"'") {
            result = result.dropLast(1)
        }
        return result
    }
}
