package com.romaevt.wordcounter

import org.junit.Assert.*
import org.junit.Test

class FormatPathTest {
    @Test fun `plain text counts the snapshot without converting or mapping it`() {
        val source = "Hello **world**"
        val snapshot = object : CharSequence {
            override val length: Int get() = source.length
            override fun get(index: Int): Char = source[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = source.subSequence(startIndex, endIndex)
            override fun toString(): String = error("The whole snapshot must not be copied")
        }
        val parsed = WordCounter.parse(snapshot, DocumentFormat.PLAIN_TEXT)
        assertTrue(parsed is ParsedDocument.Plain)
        assertEquals(0, parsed.mappingStorageBytes)
        assertEquals(TextCounts(2, 15, 14), parsed.counts)
        assertEquals(TextCounts(1, 9, 9), parsed.countSelections(listOf(SourceRange(6, 15))))
    }

    @Test fun `plain selections match normalized source ranges including BOM and CRLF boundaries`() {
        for (source in listOf("\uFEFFa\r\nb\r", "a\uFEFFb", "\uFEFF", "\r\n", "\uD83D\uDE00 Cafe\u0301")) {
            val parsed = WordCounter.parse(source, DocumentFormat.PLAIN_TEXT)
            val mapped = MappedText.normalize(source)
            for (start in 0..source.length) for (end in start..source.length) {
                val range = SourceRange(start, end)
                assertEquals(WordCounter.countText(mapped.selected(range)), parsed.countSelections(listOf(range)))
            }
            assertEquals(0, parsed.mappingStorageBytes)
        }
    }

    @Test fun `plain Markdown keeps only compact prose segments`() {
        val source = "A long ordinary sentence. ".repeat(5_000)
        val parsed = WordCounter.parse(source, DocumentFormat.MARKDOWN)
        assertTrue(parsed is ParsedDocument.Markdown)
        assertEquals(12, parsed.mappingStorageBytes)
        assertEquals(24, WordCounter.parse("Hello **world**", DocumentFormat.MARKDOWN).mappingStorageBytes)
    }

    @Test fun `cleanup returns the original object when no text changes`() {
        for (text in listOf("Hello **world** &amp;", "2 < 3", "Visit <https://example.com>", "\\<Tag> \\{literal}")) {
            val source = MappedText.normalize(text)
            assertSame(source, MarkupCleaner.clean(source, false, emptyList()))
            assertSame(source, MarkupCleaner.clean(source, true, emptyList()))
        }
    }

    @Test fun `protected code does not force a cleaned copy`() {
        val source = MappedText.normalize("`<Tag>{value}</Tag>`")
        assertSame(source, MarkupCleaner.clean(source, true, listOf(source.text.indices)))
    }

    @Test fun `actual cleanup preserves positions before between and after removals`() {
        val text = "Before <Note>Hello {value}world</Note> after"
        val source = MappedText.normalize(text)
        val cleaned = MarkupCleaner.clean(source, true, emptyList())
        assertNotSame(source, cleaned)
        assertEquals("Before Hello world after", cleaned.text)
        for (word in listOf("Before", "Hello", "world", "after")) {
            val start = text.indexOf(word)
            assertEquals(word, cleaned.selected(SourceRange(start, start + word.length)))
        }
    }
}
