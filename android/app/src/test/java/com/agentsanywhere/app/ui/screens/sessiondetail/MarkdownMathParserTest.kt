package com.agentsanywhere.app.ui.screens.sessiondetail

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.StrongEmphasis
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMathParserTest {
    private val parser = Parser.builder()
        .extensions(listOf(TablesExtension.create()))
        .customInlineContentParserFactory(MarkdownMathParserFactory())
        .build()

    private fun nodes(source: String): List<Node> = descendants(parser.parse(source))

    private fun descendants(node: Node): List<Node> = buildList {
        add(node)
        var child = node.firstChild
        while (child != null) {
            addAll(descendants(child))
            child = child.next
        }
    }

    @Test fun parsesAllFourDelimitersBeforeMarkdownCanConsumeTex() {
        val latex = "\\frac{x_1 * y_2}{\\{z\\}}"
        for ((opening, closing, display) in listOf(
            Triple("\$", "\$", false), Triple("\$\$", "\$\$", true),
            Triple("\\(", "\\)", false), Triple("\\[", "\\]", true),
        )) {
            val source = opening + latex + closing
            val math = nodes(source).filterIsInstance<MarkdownMathNode>().single()
            assertEquals(latex, math.latex)
            assertEquals(display, math.display)
            assertEquals(source, math.source)
        }
    }

    @Test fun preservesMultilineAlignmentAndMatrixLineBreaks() {
        val latex = "\\begin{aligned}\nx_1 &= a \\\\\nx_2 &= b\n\\end{aligned}"
        for (source in listOf("\$\$\n$latex\n\$\$", "\\[\n$latex\n\\]")) {
            val math = nodes(source).filterIsInstance<MarkdownMathNode>().single()
            assertEquals(latex, math.latex)
            assertTrue(math.display)
        }
    }

    @Test fun leavesCodeEscapedDelimitersPricesAndLinkDestinationsAlone() {
        for (source in listOf(
            "`\$x_1\$`", "``\\(x\\)``", "```tex\n\\[x\\]\n```",
            "~~~tex\n\$\$\nx\n\$\$\n~~~", "    \$x\$",
            "\\\$5 and \\\$10", "\$5 and \$10", "\\\\(x\\\\)",
            "[file](path/\\(x\\).txt)", "> ```tex\n> \\(x\\)",
        )) {
            assertTrue(source, nodes(source).none { it is MarkdownMathNode })
        }
        assertEquals("\$x_1\$", nodes("`\$x_1\$`").filterIsInstance<Code>().single().literal)
        assertEquals("\\[x\\]\n", nodes("```tex\n\\[x\\]\n```").filterIsInstance<FencedCodeBlock>().single().literal)
    }

    @Test fun incompleteStreamingMathBecomesAFormulaWhenClosed() {
        for ((partial, closing) in listOf(
            Pair("\$x_1", "\$"), Pair("\$\$\nx_1", "\n\$\$"),
            Pair("\\(x_1", "\\)"), Pair("\\[\nx_1", "\n\\]"),
        )) {
            assertTrue(nodes(partial).none { it is MarkdownMathNode })
            assertEquals(1, nodes(partial + closing).filterIsInstance<MarkdownMathNode>().size)
        }
        assertTrue(nodes("\\(x\ny\\)").none { it is MarkdownMathNode })
        assertTrue(nodes("\\(\\)").none { it is MarkdownMathNode })
    }

    @Test fun keepsAdjacentMathAndSurroundingMarkdownStructure() {
        val parsed = nodes("**\\(a_1\\)\\(b_2\\)** and \$c_3\$\n\n- \\[d\\]\n\n> \\(e\\)")
        assertEquals(listOf("a_1", "b_2", "c_3", "d", "e"), parsed.filterIsInstance<MarkdownMathNode>().map { it.latex })
        assertEquals(2, descendants(parsed.filterIsInstance<StrongEmphasis>().single()).filterIsInstance<MarkdownMathNode>().size)
    }

    @Test fun supportsMathInsideTableCellsAndKeepsEscapedDollarsInTex() {
        val math = nodes("| Formula | Meaning |\n| - | - |\n| \$x_1\$ | first |")
            .filterIsInstance<MarkdownMathNode>().single()
        assertEquals("x_1", math.latex)
        val escaped = nodes("\$\\text{cost \\\$5}\$").filterIsInstance<MarkdownMathNode>().single()
        assertEquals("\\text{cost \\\$5}", escaped.latex)
        assertFalse(escaped.display)
    }
}
