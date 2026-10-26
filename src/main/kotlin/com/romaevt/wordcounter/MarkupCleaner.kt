package com.romaevt.wordcounter

// This removes literal markup, not rendered component output. It never evaluates MDX.
internal object MarkupCleaner {
    private val tagStart = Regex("</?([A-Za-z][A-Za-z0-9_.:-]*)(?=\\s|/?>|$)")
    private val moduleStart = Regex(" {0,3}(?:import|export)\\s")
    private val blockTags = setOf("p", "div", "section", "article", "header", "footer", "blockquote",
        "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "table", "tr", "td", "th", "br", "hr")
    private val hiddenTags = setOf("script", "style", "pre")

    fun clean(input: MappedText, mdx: Boolean, codeRanges: List<IntRange>): MappedText {
        val source = input.text
        var result: MappedText.Builder? = null
        var unchangedStart = 0
        var position = 0
        var codeIndex = 0

        // Copy whole unchanged spans, and allocate nothing if no cleanup is needed.
        fun replaceThrough(end: Int, replacement: String = "") {
            val target = result ?: MappedText.Builder().also { result = it }
            target.copy(input, unchangedStart, position)
            target.append(replacement)
            position = end
            unchangedStart = end
        }

        while (position < source.length) {
            while (codeIndex < codeRanges.size && codeRanges[codeIndex].last < position) codeIndex++
            val code = codeRanges.getOrNull(codeIndex)
            if (code != null && position in code) {
                position = code.last + 1
                continue
            }
            when {
                source[position] == '\\' -> {
                    position = (position + 2).coerceAtMost(source.length)
                }
                source.startsWith("<!--", position) -> {
                    replaceThrough(source.indexOf("-->", position + 4).let { if (it < 0) source.length else it + 3 })
                }
                mdx && (position == 0 || source[position - 1] == '\n') &&
                    moduleStart.matchAt(source, position) != null -> {
                    replaceThrough(skipJavaScript(source, position, expression = false))
                }
                mdx && source[position] == '{' -> {
                    replaceThrough(skipJavaScript(source, position, expression = true))
                }
                mdx && source.startsWith("<>", position) -> replaceThrough(position + 2)
                mdx && source.startsWith("</>", position) -> replaceThrough(position + 3)
                source[position] == '<' -> {
                    val tag = tagStart.matchAt(source, position)
                    if (tag == null) {
                        position++
                        continue
                    }
                    val name = tag.groupValues[1].let { if (mdx) it else it.lowercase() }
                    val end = skipTag(source, tag.range.last + 1, mdx)
                    val removalEnd = if (name in hiddenTags && !source.startsWith("</", position) && !source.startsWith("/>", end - 2)) {
                        val closing = Regex("</${Regex.escape(name)}\\s*>", RegexOption.IGNORE_CASE).find(source, end)
                        closing?.range?.last?.plus(1) ?: source.length
                    } else {
                        end
                    }
                    replaceThrough(removalEnd, if (name in blockTags || name in hiddenTags) "\n\n" else "")
                }
                else -> position++
            }
        }
        return result?.apply { copy(input, unchangedStart) }?.build() ?: input
    }

    private fun skipTag(source: String, start: Int, mdx: Boolean): Int {
        var position = start
        while (position < source.length) {
            when (source[position]) {
                '>' -> return position + 1
                '\'', '"' -> position = skipQuoted(source, position)
                '{' -> position = if (mdx) skipJavaScript(source, position, expression = true) else position + 1
                else -> position++
            }
        }
        return position
    }

    private fun skipJavaScript(source: String, start: Int, expression: Boolean): Int {
        var position = start
        var depth = 0
        while (position < source.length) {
            when {
                source.startsWith("//", position) -> {
                    position = source.indexOf('\n', position + 2).takeIf { it >= 0 } ?: source.length
                }
                source.startsWith("/*", position) -> {
                    position = source.indexOf("*/", position + 2).let { if (it < 0) source.length else it + 2 }
                }
                else -> when (source[position]) {
                    '\'', '"', '`' -> position = skipQuoted(source, position)
                    '{', '[', '(' -> { depth++; position++ }
                    '}', ']', ')' -> {
                        depth--
                        position++
                        if (expression && depth <= 0) return position
                    }
                    ';', '\n' -> {
                        if (!expression && depth <= 0) {
                            val preceding = source.substring(start, position).trimEnd()
                            val continues = source[position] == '\n' &&
                                (preceding.endsWith(',') || preceding.endsWith('=') || preceding.endsWith(" from"))
                            if (!continues) return position + 1
                        }
                        position++
                    }
                    else -> position++
                }
            }
        }
        return position
    }

    private fun skipQuoted(source: String, start: Int): Int {
        val quote = source[start]
        var position = start + 1
        while (position < source.length) {
            when (source[position]) {
                '\\' -> position = (position + 2).coerceAtMost(source.length)
                quote -> return position + 1
                else -> position++
            }
        }
        return position
    }
}
