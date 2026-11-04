package app.kujira.android.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `parses headings paragraphs and dividers`() {
        val blocks = MarkdownParser.parse(
            """
            # 标题一

            普通段落，第一行
            第二行

            ## 标题二
            """.trimIndent()
        )
        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Heading(1, "标题一"), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("普通段落，第一行\n第二行"), blocks[1])
        assertEquals(MarkdownBlock.Heading(2, "标题二"), blocks[2])
    }

    @Test
    fun `parses unordered and ordered lists with nesting`() {
        val blocks = MarkdownParser.parse(
            """
            - 苹果
              - 青苹果
            - 香蕉

            1. 第一步
            2. 第二步
            """.trimIndent()
        )
        assertEquals(2, blocks.size)

        val first = blocks[0] as MarkdownBlock.ListBlock
        assertEquals(false, first.ordered)
        assertEquals(3, first.items.size)
        assertEquals("苹果", first.items[0].content)
        assertEquals(1, first.items[1].indent)
        assertEquals("青苹果", first.items[1].content)
        assertEquals("香蕉", first.items[2].content)

        val second = blocks[1] as MarkdownBlock.ListBlock
        assertEquals(true, second.ordered)
        assertEquals(1, second.start)
        assertEquals(2, second.items.size)
        assertEquals(2, second.items[1].number)
    }

    @Test
    fun `parses quote and fenced code with language`() {
        val blocks = MarkdownParser.parse(
            """
            > 引用第一行
            > 引用第二行

            ```kotlin
            val x = 1
            ```
            """.trimIndent()
        )
        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlock.Quote("引用第一行\n引用第二行"), blocks[0])
        val code = blocks[1] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
        assertTrue(code.closed)
    }

    @Test
    fun `unclosed fence still becomes a code block while streaming`() {
        val blocks = MarkdownParser.parse("说明：\n\n```python\nprint(1)")
        assertEquals(2, blocks.size)
        val code = blocks[1] as MarkdownBlock.CodeBlock
        assertEquals("python", code.language)
        assertEquals("print(1)", code.code)
        assertEquals(false, code.closed)
    }

    @Test
    fun `parses table with alignments and padded rows`() {
        val blocks = MarkdownParser.parse(
            """
            | 服务 | 状态 | 延迟 |
            |:---|---:|:---:|
            | nginx | 运行中 | 12ms |
            | redis |
            """.trimIndent()
        )
        assertEquals(1, blocks.size)
        val table = blocks[0] as MarkdownBlock.Table
        assertEquals(listOf("服务", "状态", "延迟"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("nginx", "运行中", "12ms"), table.rows[0])
        assertEquals(listOf("redis", "", ""), table.rows[1])
        assertEquals(
            listOf(TableAlignment.LEFT, TableAlignment.RIGHT, TableAlignment.CENTER),
            table.alignments,
        )
    }

    @Test
    fun `plain pipe text without separator is not a table`() {
        val blocks = MarkdownParser.parse("a | b | c")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `streaming partial markdown stays literal and never throws`() {
        val partials = listOf(
            "**",
            "**bold",
            "`code",
            "```",
            "[link](",
            "| a | b |\n|---|",
            "> ",
            "#",
            "1.",
        )
        partials.forEach { MarkdownParser.parse(it) }
    }
}

class InlineParserTest {

    @Test
    fun `parses bold italic strike inline code and links`() {
        val nodes = InlineParser.parse("**粗** *斜* ~~删~~ `code` [链接](https://sentinelx.app)")
        val text = flatten(nodes)
        assertEquals("粗 斜 删 code 链接", text)
        assertTrue(nodes.any { it is InlineNode.Bold })
        assertTrue(nodes.any { it is InlineNode.Italic })
        assertTrue(nodes.any { it is InlineNode.Strikethrough })
        assertTrue(nodes.any { it is InlineNode.Code })
        val link = nodes.filterIsInstance<InlineNode.Link>().single()
        assertEquals("https://sentinelx.app", link.url)
    }

    @Test
    fun `unclosed emphasis remains literal while streaming`() {
        assertEquals("**bold", flatten(InlineParser.parse("**bold")))
        assertEquals("`code", flatten(InlineParser.parse("`code")))
        assertEquals("*x", flatten(InlineParser.parse("*x")))
    }

    @Test
    fun `bare urls become links`() {
        val nodes = InlineParser.parse("打开 https://sentinelx.app/mcp，就行了")
        val link = nodes.filterIsInstance<InlineNode.Link>().single()
        assertEquals("https://sentinelx.app/mcp", link.url)
    }

    @Test
    fun `underscores inside words are not emphasis`() {
        assertEquals("snake_case_name", flatten(InlineParser.parse("snake_case_name")))
    }
}

private fun flatten(nodes: List<InlineNode>): String = buildString {
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
