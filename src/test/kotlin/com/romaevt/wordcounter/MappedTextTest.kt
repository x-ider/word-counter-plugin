package com.romaevt.wordcounter

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class MappedTextTest {
    @Test fun `unchanged text uses its original string and one segment`() {
        val source = "Long plain paragraph. ".repeat(20_000)
        val mapped = MappedText.normalize(source)
        assertSame(source, mapped.text)
        assertSame(mapped, mapped.slice(0))
        assertEquals(12, mapped.mappingStorageBytes)
        assertEquals(source.substring(200, 250), mapped.selected(SourceRange(200, 250)))
        assertEquals(0, MappedText.normalize("").mappingStorageBytes)
    }

    @Test fun `adjacent copies and characters merge into one segment`() {
        val source = MappedText.normalize("abcdefgh")
        val builder = MappedText.Builder()
        builder.copy(source, 0, 3)
        builder.copy(source, 3, 6)
        builder.append('g', 6, 7)
        builder.append('h', 7, 8)
        val mapped = builder.build()
        assertEquals("abcdefgh", mapped.text)
        assertEquals(12, mapped.mappingStorageBytes)
        assertEquals("cdef", mapped.selected(SourceRange(2, 6)))
    }

    @Test fun `decoded token slices preserve the entire original token range`() {
        val builder = MappedText.Builder()
        builder.append("\uD83D\uDE00", 10, 19)
        val mapped = builder.build()
        assertEquals(12, mapped.mappingStorageBytes)
        assertEquals(mapped.text, mapped.selected(SourceRange(13, 14)))
        assertEquals("", mapped.selected(SourceRange(13, 13)))
        val slice = mapped.slice(1)
        assertEquals(10, slice.sourceStart(0))
        assertEquals(19, slice.sourceEnd(0))
    }

    @Test fun `trimming and removing a last segment keep source positions`() {
        val builder = MappedText.Builder()
        builder.appendOriginal("  Hello  ", 0, 9)
        builder.append("\n\n")
        builder.removeLast()
        builder.removeLast()
        val result = builder.build(trim = true)
        assertEquals("Hello", result.text)
        assertEquals(2, result.sourceStart(0))
        assertEquals(7, result.sourceEnd(4))
        assertEquals(12, result.mappingStorageBytes)
        assertEquals("  Hello  ", builder.build().text)
    }

    @Test fun `normalization maps CRLF as a token and ordinary spans linearly`() {
        val mapped = MappedText.normalize("\uFEFFa\r\nb\rc")
        assertEquals("a\nb\nc", mapped.text)
        assertEquals(listOf(1, 2, 4, 5, 6), mapped.text.indices.map(mapped::sourceStart))
        assertEquals(listOf(2, 4, 5, 6, 7), mapped.text.indices.map(mapped::sourceEnd))
        assertEquals("\n", mapped.selected(SourceRange(2, 3)))
        assertEquals("\n", mapped.selected(SourceRange(3, 4)))
        assertEquals(36, mapped.mappingStorageBytes)
    }

    @Test fun `segment operations match the original per-character mapping model`() {
        val random = Random(42)
        repeat(40) {
            val builder = MappedText.Builder()
            val text = StringBuilder()
            val starts = mutableListOf<Int>()
            val ends = mutableListOf<Int>()
            repeat(80) {
                when (random.nextInt(5)) {
                    0 -> {
                        val source = "abcdefghijklmnopqrstuvwxyz"
                        val start = random.nextInt(source.length)
                        val end = random.nextInt(start + 1, source.length + 1)
                        builder.appendOriginal(source, start, end)
                        text.append(source, start, end)
                        for (index in start until end) { starts += index; ends += index + 1 }
                    }
                    1 -> {
                        val value = listOf("&", "xy", "\uD83D\uDE00").random(random)
                        val start = random.nextInt(30)
                        val end = start + random.nextInt(1, 10)
                        builder.append(value, start, end)
                        text.append(value)
                        repeat(value.length) { starts += start; ends += end }
                    }
                    2 -> {
                        builder.append("\n\t")
                        text.append("\n\t")
                        repeat(2) { starts += -1; ends += -1 }
                    }
                    3 -> if (text.isNotEmpty()) {
                        builder.removeLast()
                        text.setLength(text.length - 1)
                        starts.removeAt(starts.lastIndex)
                        ends.removeAt(ends.lastIndex)
                    }
                    4 -> if (text.isNotEmpty()) {
                        val start = random.nextInt(text.length)
                        val end = minOf(start + 12, text.length)
                        val copy = builder.build().slice(start, end)
                        builder.copy(copy)
                        text.append(copy.text)
                        starts.addAll(starts.subList(start, end).toList())
                        ends.addAll(ends.subList(start, end).toList())
                    }
                }
            }
            val mapped = builder.build()
            assertEquals(text.toString(), mapped.text)
            for (index in text.indices) {
                assertEquals(starts[index], mapped.sourceStart(index))
                assertEquals(ends[index], mapped.sourceEnd(index))
            }
            repeat(30) {
                val start = random.nextInt(40)
                val end = random.nextInt(start + 1, 42)
                val expected = StringBuilder()
                val separator = StringBuilder()
                for (index in text.indices) {
                    if (starts[index] < 0) {
                        if (expected.isNotEmpty()) separator.append(text[index])
                    } else if (starts[index] < end && ends[index] > start) {
                        expected.append(separator).append(text[index])
                        separator.setLength(0)
                    } else separator.setLength(0)
                }
                assertEquals(expected.toString(), mapped.selected(SourceRange(start, end)))
            }
        }
    }
}
