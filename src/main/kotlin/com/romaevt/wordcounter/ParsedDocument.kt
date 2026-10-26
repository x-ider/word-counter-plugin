package com.romaevt.wordcounter

internal sealed class ParsedDocument {
    abstract val text: String
    abstract val counts: TextCounts
    abstract val mappingStorageBytes: Int
    protected abstract fun selectedText(range: SourceRange): CharSequence

    fun countSelections(selections: List<SourceRange>): TextCounts {
        if (selections.isEmpty()) return counts
        val merged = mutableListOf<SourceRange>()
        for (range in selections.filter { it.start < it.end }.sortedBy { it.start }) {
            val previous = merged.lastOrNull()
            if (previous != null && range.start <= previous.end) {
                merged[merged.lastIndex] = SourceRange(previous.start, maxOf(previous.end, range.end))
            } else merged += range
        }
        var total = TextCounts(0, 0, 0)
        for (range in merged) {
            val count = WordCounter.countText(selectedText(range))
            total = TextCounts(total.words + count.words, total.characters + count.characters,
                total.charactersWithoutWhitespace + count.charactersWithoutWhitespace)
        }
        return total
    }

    // The widget supplies an immutable snapshot, so TXT needs no additional text copy.
    class Plain(private val source: CharSequence) : ParsedDocument() {
        override val text: String get() = normalize(source, true).toString()
        override val counts = WordCounter.countText(normalize(source, true))
        override val mappingStorageBytes: Int get() = 0

        override fun selectedText(range: SourceRange): CharSequence = normalize(
            source.subSequence(range.start.coerceAtMost(source.length), range.end.coerceAtMost(source.length)),
            range.start == 0,
        )

        private fun normalize(value: CharSequence, stripBom: Boolean): CharSequence {
            val body = if (stripBom && value.startsWith('\uFEFF')) value.subSequence(1, value.length) else value
            return if ('\r' in body) body.toString().replace("\r\n", "\n").replace('\r', '\n') else body
        }
    }

    class Markdown(private val prose: MappedText) : ParsedDocument() {
        override val text: String get() = prose.text
        override val counts = WordCounter.countText(text)
        override val mappingStorageBytes: Int get() = prose.mappingStorageBytes
        override fun selectedText(range: SourceRange): String = prose.selected(range)
    }
}
