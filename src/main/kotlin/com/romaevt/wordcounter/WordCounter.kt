package com.romaevt.wordcounter

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.internal.util.Escaping
import java.util.regex.Pattern

enum class DocumentFormat { PLAIN_TEXT, MARKDOWN, MDX }

data class TextCounts(
    val words: Int,
    val characters: Int,
    val charactersWithoutWhitespace: Int,
)

object WordCounter {
    private val parser by lazy {
        Parser.builder()
            .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create()))
            // Expose the text inside HTML/MDX wrappers instead of treating it as an opaque HTML block.
            .enabledBlockTypes(setOf(
                Heading::class.java, ThematicBreak::class.java, FencedCodeBlock::class.java,
                IndentedCodeBlock::class.java, BlockQuote::class.java, ListBlock::class.java,
            ))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build()
    }
    private val words = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*+(?:['_\u2019-][\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*+)*+")
    private val characters = Pattern.compile("\\X")
    // Reuse the pinned parser's decoding rules only when entities or escapes occur.
    private val escapes by lazy {
        Pattern.compile("\\\\" + Escaping.ESCAPABLE + "|" + Escaping.ENTITY, Pattern.CASE_INSENSITIVE)
    }

    fun count(source: String, format: DocumentFormat = DocumentFormat.MARKDOWN): TextCounts = parse(source, format).counts

    internal fun countText(text: CharSequence): TextCounts {
        val wordMatcher = words.matcher(text)
        var wordCount = 0
        while (wordMatcher.find()) wordCount++

        val characterMatcher = characters.matcher(text)
        var characterCount = 0
        var withoutWhitespace = 0
        while (characterMatcher.find()) {
            characterCount++
            var offset = characterMatcher.start()
            while (offset < characterMatcher.end()) {
                val codePoint = Character.codePointAt(text, offset)
                if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                    withoutWhitespace++
                    break
                }
                offset += Character.charCount(codePoint)
            }
        }
        return TextCounts(wordCount, characterCount, withoutWhitespace)
    }

    internal fun extractText(source: String, format: DocumentFormat = DocumentFormat.MARKDOWN): String = parse(source, format).text

    internal fun parse(source: CharSequence, format: DocumentFormat): ParsedDocument {
        if (format == DocumentFormat.PLAIN_TEXT) return ParsedDocument.Plain(source)
        val normalized = MappedText.normalize(source.toString())
        val body = normalized.slice(frontmatterEnd(normalized.text))
        val parsed = parser.parse(body.text)
        val cleaned = if (format == DocumentFormat.MARKDOWN && '<' !in body.text) body else {
            val protectedRanges = mutableListOf<IntRange>()
            visitCode(parsed, body.text, protectedRanges)
            MarkupCleaner.clean(body, format == DocumentFormat.MDX, protectedRanges)
        }
        val document = if (cleaned.text == body.text) parsed else parser.parse(cleaned.text)
        return ParsedDocument.Markdown(PlainText(cleaned).apply { append(document) }.build())
    }

    private fun frontmatterEnd(source: String): Int {
        if (source != "---" && !source.startsWith("---\n")) return 0
        var start = 4
        while (start < source.length) {
            val end = source.indexOf('\n', start).takeIf { it >= 0 } ?: source.length
            if (source.substring(start, end).trimEnd() in setOf("---", "...")) {
                return (end + 1).coerceAtMost(source.length)
            }
            start = end + 1
        }
        return source.length
    }

    private fun visitCode(node: Node, source: String, ranges: MutableList<IntRange>) {
        if (node is Code || node is FencedCodeBlock || node is IndentedCodeBlock) {
            val spans = node.sourceSpans
            if (spans.isNotEmpty()) {
                ranges += inlineRange(node, spans.first(), source).start until inlineRange(node, spans.last(), source).end
            }
            return
        }
        var child = node.firstChild
        while (child != null) {
            visitCode(child, source, ranges)
            child = child.next
        }
    }

    private fun tableCell(node: Node): TableCell? {
        var parent = node.parent
        while (parent != null) {
            if (parent is TableCell) return parent
            parent = parent.parent
        }
        return null
    }

    private fun inlineRange(node: Node, span: SourceSpan, source: String): SourceRange {
        val cell = tableCell(node)?.sourceSpans?.firstOrNull()
            ?: return SourceRange(span.inputIndex, span.inputIndex + span.length)
        // GFM unescapes pipes before inline parsing, but its child spans use the original cell start.
        fun originalOffset(offset: Int): Int {
            var index = cell.inputIndex
            var remaining = offset - cell.inputIndex
            while (remaining > 0 && index < cell.inputIndex + cell.length) {
                index += if (source[index] == '\\' && source.getOrNull(index + 1) == '|') 2 else 1
                remaining--
            }
            return index
        }
        return SourceRange(originalOffset(span.inputIndex), originalOffset(span.inputIndex + span.length))
    }

    private class PlainText(private val source: MappedText) {
        private val text = MappedText.Builder()

        fun append(node: Node) {
            when (node) {
                is Text -> appendText(node)
                is Code -> appendCode(node)
                is SoftLineBreak -> appendBreak(node, " ")
                is HardLineBreak -> boundary(node)
                is Image, is FencedCodeBlock, is IndentedCodeBlock, is HtmlInline, is HtmlBlock -> Unit
                is ThematicBreak -> boundary()
                is Paragraph, is Heading, is TableRow -> {
                    boundary()
                    children(node)
                    boundary()
                }
                is TableCell -> {
                    if (text.length > 0 && text.last() != '\n') text.append("\t")
                    children(node)
                }
                is Link -> {
                    val label = node.firstChild
                    val urlOnly = label is Text && label.next == null &&
                        (label.literal == node.destination || "mailto:${label.literal}" == node.destination)
                    if (!urlOnly) children(node)
                }
                else -> children(node)
            }
        }

        private fun children(node: Node) {
            var child = node.firstChild
            while (child != null) {
                append(child)
                child = child.next
            }
        }

        private fun boundary(node: Node? = null) {
            while (text.length > 0 && (text.last() == ' ' || text.last() == '\t')) text.removeLast()
            if (text.length > 0 && text.last() != '\n') {
                if (node == null) text.append("\n") else appendBreak(node, "\n")
            }
        }

        private fun appendBreak(node: Node, value: String) {
            val before = node.previous?.sourceSpans?.lastOrNull()
            val start = node.sourceSpans.firstOrNull()?.inputIndex ?: before?.let { it.inputIndex + it.length }
            val end = node.next?.sourceSpans?.firstOrNull()?.inputIndex ?: source.text.length
            val newline = if (start == null) -1 else source.text.indexOf('\n', start)
            if (newline >= 0 && newline < end) text.append(value, source.sourceStart(newline), source.sourceEnd(newline))
            else text.append(value)
        }

        private fun raw(node: Node): MappedText {
            val singleSpan = node.sourceSpans.singleOrNull()
            if (singleSpan != null && tableCell(node) == null) {
                return source.slice(singleSpan.inputIndex, singleSpan.inputIndex + singleSpan.length)
            }
            val result = MappedText.Builder()
            var previous: SourceSpan? = null
            for (span in node.sourceSpans) {
                val last = previous
                if (last != null && span.lineIndex > last.lineIndex) {
                    val newline = source.text.indexOf('\n', last.inputIndex + last.length)
                    if (newline >= 0) result.copy(source, newline, newline + 1)
                }
                val range = inlineRange(node, span, source.text)
                result.copy(source, range.start, range.end)
                previous = span
            }
            val raw = result.build()
            if (tableCell(node) == null || !raw.text.contains("\\|")) return raw
            val unescaped = MappedText.Builder()
            var index = 0
            while (index < raw.text.length) {
                if (raw.text.startsWith("\\|", index)) {
                    unescaped.append("|", raw.sourceStart(index), raw.sourceEnd(index + 1))
                    index += 2
                } else {
                    unescaped.copy(raw, index, index + 1)
                    index++
                }
            }
            return unescaped.build()
        }

        private fun appendText(node: Text) {
            val raw = raw(node)
            if ('\\' !in raw.text && '&' !in raw.text) {
                appendDecodedText(node, raw)
                return
            }
            val decodedText = MappedText.Builder()
            val matcher = escapes.matcher(raw.text)
            var start = 0
            while (matcher.find()) {
                decodedText.copy(raw, start, matcher.start())
                val decoded = Escaping.unescapeString(matcher.group())
                if (decoded == matcher.group()) decodedText.copy(raw, matcher.start(), matcher.end())
                else decodedText.append(decoded, raw.sourceStart(matcher.start()), raw.sourceEnd(matcher.end() - 1))
                start = matcher.end()
            }
            decodedText.copy(raw, start)
            appendDecodedText(node, decodedText.build())
        }

        private fun appendDecodedText(node: Text, decoded: MappedText) {
            // The task-list extension removes a prefix without updating the Text node's spans.
            val prefix = if (decoded.text.endsWith(node.literal)) decoded.text.length - node.literal.length else 0
            text.copy(decoded, prefix)
        }

        private fun appendCode(node: Code) {
            val raw = raw(node)
            val ticks = raw.text.takeWhile { it == '`' }.length
            var start = ticks
            var end = raw.text.length - ticks
            val content = raw.text.substring(start, end).replace('\n', ' ')
            if (content.startsWith(' ') && content.endsWith(' ') && content.any { it != ' ' }) {
                start++
                end--
            }
            for (index in start until end) {
                text.append(if (raw.text[index] == '\n') ' ' else raw.text[index], raw.sourceStart(index), raw.sourceEnd(index))
            }
        }

        fun build(): MappedText = text.build(trim = true)
    }
}
