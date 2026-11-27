package com.romaevt.wordcounter

// Line-based cleanup for AsciiDoc. Only syntax-only lines and code-like blocks are omitted;
// complex inline markup is counted as written.
internal object AsciiDocCleaner {
    private val hiddenBlocks = setOf("----", "....", "++++", "////")
    private val delimiter = Regex("^(?:-{4,}|\\.{4,}|\\+{4,}|/{4,})\\s*$")
    private val attribute = Regex("^:[^:\\s]+:.*$")
    private val blockAttribute = Regex("^\\[[^]]*]\\s*$")
    private val comment = Regex("^//.*$")
    private val heading = Regex("^=+\\s+")

    fun clean(input: MappedText): MappedText {
        val result = MappedText.Builder()
        var hiddenBlock: String? = null
        var offset = 0
        while (offset < input.text.length) {
            val end = input.text.indexOf('\n', offset).let { if (it < 0) input.text.length else it + 1 }
            val line = input.text.substring(offset, end).trimEnd('\n', ' ', '\t')
            val block = delimiter.matchEntire(line)?.value?.trim()
            if (hiddenBlock != null) {
                if (block == hiddenBlock) hiddenBlock = null
            } else if (block != null && block in hiddenBlocks) {
                hiddenBlock = block
            } else if (line.isNotBlank() && !attribute.matches(line) &&
                !blockAttribute.matches(line) && !comment.matches(line)) {
                val start = offset + (heading.find(line)?.value?.length ?: 0)
                result.copy(input, start, end)
            }
            offset = end
        }
        return result.build(trim = true)
    }
}
