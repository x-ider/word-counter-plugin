package com.romaevt.wordcounter

internal data class SourceRange(val start: Int, val end: Int) {
    init { require(start >= 0 && end >= start) }
}

// Each segment stores [textEnd, sourceStart, tokenEnd]. Its text start is the previous end.
// tokenEnd == -1 means a one-to-one span; sourceStart == -1 means generated text.
// Otherwise the entire segment maps to one source token, e.g. a decoded entity.
internal class MappedText private constructor(val text: String, private val segments: IntArray) {
    val mappingStorageBytes: Int get() = segments.size * Int.SIZE_BYTES

    private fun segmentAt(index: Int): Int {
        var low = 0
        var high = segments.size / 3
        while (low < high) {
            val middle = (low + high) ushr 1
            if (segments[middle * 3] <= index) low = middle + 1 else high = middle
        }
        return low * 3
    }

    private fun textStart(segment: Int): Int = if (segment == 0) 0 else segments[segment - 3]

    fun sourceStart(index: Int): Int {
        val segment = segmentAt(index)
        val start = segments[segment + 1]
        return if (start < 0 || segments[segment + 2] >= 0) start else start + index - textStart(segment)
    }

    fun sourceEnd(index: Int): Int {
        val segment = segmentAt(index)
        val start = segments[segment + 1]
        val end = segments[segment + 2]
        return when {
            start < 0 -> -1
            end >= 0 -> end
            else -> start + index - textStart(segment) + 1
        }
    }

    fun slice(start: Int, end: Int = text.length): MappedText =
        if (start == 0 && end == text.length) this else Builder().apply { copy(this@MappedText, start, end) }.build()

    fun selected(range: SourceRange): String {
        if (range.start == range.end) return ""
        val result = StringBuilder()
        val separator = StringBuilder()
        var start = 0
        for (segment in segments.indices step 3) {
            val end = segments[segment]
            val original = segments[segment + 1]
            val tokenEnd = segments[segment + 2]
            if (original < 0) {
                if (result.isNotEmpty()) separator.append(text, start, end)
            } else {
                val originalEnd = if (tokenEnd >= 0) tokenEnd else original + end - start
                if (original < range.end && originalEnd > range.start) {
                    val selectedStart = if (tokenEnd >= 0) start else start + maxOf(0, range.start - original)
                    val selectedEnd = if (tokenEnd >= 0) end else end - maxOf(0, originalEnd - range.end)
                    if (selectedStart > start) separator.setLength(0)
                    result.append(separator).append(text, selectedStart, selectedEnd)
                }
                separator.setLength(0)
            }
            start = end
        }
        return result.toString()
    }

    class Builder {
        private val text = StringBuilder()
        private var segments = IntArray(24)
        private var size = 0
        val length: Int get() = text.length
        fun last(): Char = text.last()

        private fun addSegment(end: Int, sourceStart: Int, tokenEnd: Int) {
            if (size > 0) {
                val previous = size - 3
                val previousStart = if (previous == 0) 0 else segments[previous - 3]
                val adjacent = sourceStart >= 0 && tokenEnd < 0 && segments[previous + 2] < 0 &&
                    segments[previous + 1] >= 0 &&
                    sourceStart == segments[previous + 1] + segments[previous] - previousStart
                val sameToken = (sourceStart < 0 || tokenEnd >= 0) &&
                    sourceStart == segments[previous + 1] && tokenEnd == segments[previous + 2]
                if (adjacent || sameToken) {
                    segments[previous] = end
                    return
                }
            }
            if (size == segments.size) segments = segments.copyOf(segments.size * 2)
            segments[size++] = end
            segments[size++] = sourceStart
            segments[size++] = tokenEnd
        }

        fun append(value: String, start: Int = -1, end: Int = -1) {
            if (value.isEmpty()) return
            val tokenEnd = if (start < 0 || (value.length == 1 && end == start + 1)) -1 else end
            addSegment(length + value.length, start, tokenEnd)
            text.append(value)
        }

        fun append(value: Char, start: Int, end: Int) {
            addSegment(length + 1, start, if (start < 0 || end == start + 1) -1 else end)
            text.append(value)
        }

        fun appendOriginal(source: String, start: Int, end: Int) {
            if (start == end) return
            addSegment(length + end - start, start, -1)
            text.append(source, start, end)
        }

        fun copy(source: MappedText, start: Int = 0, end: Int = source.text.length) {
            if (start == end) return
            var segment = source.segmentAt(start)
            while (segment < source.segments.size && source.textStart(segment) < end) {
                val from = maxOf(start, source.textStart(segment))
                val to = minOf(end, source.segments[segment])
                val original = source.segments[segment + 1]
                val tokenEnd = source.segments[segment + 2]
                val offset = if (original >= 0 && tokenEnd < 0) from - source.textStart(segment) else 0
                addSegment(length + to - start, original + offset, tokenEnd)
                segment += 3
            }
            text.append(source.text, start, end)
        }

        fun removeLast() {
            text.setLength(length - 1)
            val lastStart = if (size == 3) 0 else segments[size - 6]
            if (length == lastStart) size -= 3 else segments[size - 3] = length
        }

        fun build(trim: Boolean = false): MappedText {
            val result = MappedText(text.toString(), segments.copyOf(size))
            if (!trim) return result
            var start = 0
            var end = length
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
            return result.slice(start, end)
        }
    }

    companion object {
        fun normalize(source: String): MappedText {
            var start = if (source.startsWith('\uFEFF')) 1 else 0
            var carriageReturn = source.indexOf('\r', start)
            if (carriageReturn < 0) {
                val text = source.substring(start)
                return MappedText(text, if (text.isEmpty()) IntArray(0) else intArrayOf(text.length, start, -1))
            }
            val result = Builder()
            while (carriageReturn >= 0) {
                result.appendOriginal(source, start, carriageReturn)
                start = carriageReturn + if (source.getOrNull(carriageReturn + 1) == '\n') 2 else 1
                result.append('\n', carriageReturn, start)
                carriageReturn = source.indexOf('\r', start)
            }
            result.appendOriginal(source, start, source.length)
            return result.build()
        }
    }
}
