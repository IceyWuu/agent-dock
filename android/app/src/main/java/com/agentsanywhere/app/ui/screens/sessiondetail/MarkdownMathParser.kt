package com.agentsanywhere.app.ui.screens.sessiondetail

import org.commonmark.node.CustomNode
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Scanner

internal class MarkdownMathNode(
    val latex: String,
    val display: Boolean,
    val source: String,
) : CustomNode()

/** Parse TeX before CommonMark consumes its escapes, emphasis and line breaks.
 * Code spans, fenced/indented code and link destinations bypass this parser.
 */
internal class MarkdownMathParserFactory : InlineContentParserFactory {
    override fun getTriggerCharacters(): Set<Char> = setOf('$', '\\')

    override fun create(): InlineContentParser = MathParser()

    private class MathParser : InlineContentParser {
        override fun tryParse(state: InlineParserState): ParsedInline? {
            val scanner = state.scanner()
            val start = scanner.position()
            val dollar = scanner.peek() == '$'
            scanner.next()
            val display: Boolean
            val closing: String
            if (dollar) {
                display = scanner.next('$')
                closing = if (display) "$$" else "$"
                // Avoid interpreting prices or whitespace-delimited prose as math.
                if (!display && (scanner.peek().isWhitespace() || scanner.peek() == Scanner.END)) return null
            } else {
                closing = when (scanner.peek()) {
                    '(' -> "\\)"
                    '[' -> "\\]"
                    else -> return null
                }
                display = scanner.peek() == '['
                scanner.next()
            }
            val contentStart = scanner.position()
            var previous = Scanner.END
            while (scanner.hasNext()) {
                val end = scanner.position()
                val char = scanner.peek()
                if (!display && char == '\n') return null
                if (scanner.next(closing)) {
                    if (dollar && !display && (previous.isWhitespace() || scanner.peek().isDigit())) {
                        return null
                    }
                    val latex = scanner.getSource(contentStart, end).content
                    if (latex.isBlank()) return null
                    return ParsedInline.of(
                        MarkdownMathNode(latex.trim(), display, scanner.getSource(start, scanner.position()).content),
                        scanner.position(),
                    )
                }
                scanner.next()
                // Preserve escaped dollars and paired backslashes inside TeX.
                if (char == '\\' && scanner.hasNext()) {
                    previous = scanner.peek()
                    scanner.next()
                } else {
                    previous = char
                }
            }
            // The closing delimiter has not arrived yet in a streaming response.
            return null
        }
    }
}
