package com.romaevt.wordcounter

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionCounterTest {
    @Test fun `selection retains frontmatter and link context`() {
        val source = "---\ntitle: Secret\n---\nA [useful guide](https://example.com) today."
        assertSelection(source, "Secret", "")
        assertSelection(source, "https://example.com", "")
        assertSelection(source, "useful guide", "useful guide")
        assertSelection(source, "useful guide](https://example.com) today", "useful guide today")
    }

    @Test fun `selection retains code and image context`() {
        val source = "Before\n\n```md\nhidden code\n```\n\n![hidden alt](image.png)\n\nUse `npm run build`."
        assertSelection(source, "hidden code", "")
        assertSelection(source, "hidden alt", "")
        assertSelection(source, "image.png", "")
        assertSelection(source, "npm run", "npm run")
        assertSelection(source, "`", "")
    }

    @Test fun `selection ignores only the syntax around formatted words`() {
        assertSelection("A **beautiful** day", "**beautiful**", "beautiful")
        assertSelection("A **beautiful** day", "**", "")
        assertSelection("A **beautiful** day", "auti", "auti")
        assertSelection("co**operate**", "co**operate**", "cooperate")
    }

    @Test fun `MDX cleanup preserves original selection offsets`() {
        val source = "---\ntitle: Hidden\n---\nimport Box from './Box';\n\n<Box title=\"Secret\">Hello **world** {dynamic}</Box>"
        for (hidden in listOf("Hidden", "import Box", "Secret", "dynamic", "</Box>")) {
            assertSelection(source, hidden, "", DocumentFormat.MDX)
        }
        assertSelection(source, "Hello **world**", "Hello world", DocumentFormat.MDX)
    }

    @Test fun `decoded entities and escaped punctuation keep their source ranges`() {
        assertSelection("Tom &amp; Jerry", "&amp;", "&")
        assertSelection("Tom &amp; Jerry", "amp", "&")
        assertSelection("Tom &amp; Jerry", "Jerry", "Jerry")
        assertSelection("\\*literal\\*", "\\*literal\\*", "*literal*")
        assertSelection("&#x1F600; grin", "&#x1F600;", "\uD83D\uDE00")
    }

    @Test fun `normalization preserves BOM and CRLF source positions`() {
        assertSelection("\uFEFF---\r\ntitle: Hidden\r\n---\r\nHello\r\nworld", "world", "world")
        assertSelection("\uFEFFHello\r\nworld", "\r\nworld", "\nworld", DocumentFormat.PLAIN_TEXT)
    }

    @Test fun `paragraph separators are retained only between selected prose`() {
        assertSelection("First\n\nSecond", "First\n\nSecond", "First\nSecond")
        assertSelection("First\n\nSecond", "Second", "Second")
        assertSelection("First\n\nSecond", "\n\n", "")
    }

    @Test fun `task markers and table delimiters are excluded`() {
        assertSelection("- [x] Done", "[x]", "")
        assertSelection("- [x] Done", "Done", "Done")
        val table = "| Name | State |\n| --- | --- |\n| Item | Done |"
        assertSelection(table, "Item | Done", "Item\tDone")
        assertSelection(table, "---", "")
    }

    @Test fun `plain text selections preserve symbols and whitespace`() {
        assertSelection(" **Hello** \n", "**Hello** ", "**Hello** ", DocumentFormat.PLAIN_TEXT)
        assertSelection("Hello world", " ", " ", DocumentFormat.PLAIN_TEXT)
    }

    @Test fun `source line breaks inside prose retain selection positions`() {
        assertSelection("one\ntwo", "\n", " ")
        assertSelection("one  \ntwo", "\n", "\n")
        assertSelection("one\\\ntwo", "\n", "\n")
        assertSelection("`one\ntwo`", "\n", " ")
    }

    @Test fun `escaped table content retains positions after decoding`() {
        val source = "| A | B |\n| --- | --- |\n| a\\|b | **bold** &amp; |"
        assertSelection(source, "a\\|b", "a|b")
        assertSelection(source, "**bold**", "bold")
        assertSelection(source, "&amp;", "&")
        val mdx = "| A | B |\n| --- | --- |\n| `a\\|b {literal}` | **after** |"
        assertSelection(mdx, "{literal}", "{literal}", DocumentFormat.MDX)
        assertSelection(mdx, "after", "after", DocumentFormat.MDX)
    }

    @Test fun `disjoint selections do not create words or artificial characters`() {
        val parsed = WordCounter.parse("one middle two", DocumentFormat.PLAIN_TEXT)
        assertEquals(TextCounts(2, 6, 6), parsed.countSelections(listOf(SourceRange(0, 3), SourceRange(11, 14))))
        assertEquals(TextCounts(1, 3, 3), parsed.countSelections(listOf(SourceRange(0, 2), SourceRange(1, 3))))
        assertEquals(parsed.counts, parsed.countSelections(emptyList()))
    }

    @Test fun `full selections match document counts including multiline inline code`() {
        for (source in listOf("`hello\nworld`", "` padded `", "`` a ` b ``", "one\ntwo", "one  \ntwo")) {
            val parsed = WordCounter.parse(source, DocumentFormat.MARKDOWN)
            assertEquals(parsed.counts, parsed.countSelections(listOf(SourceRange(0, source.length))))
        }
    }

    private fun assertSelection(source: String, selected: String, visible: String, format: DocumentFormat = DocumentFormat.MARKDOWN) {
        val start = source.indexOf(selected)
        require(start >= 0)
        assertEquals(WordCounter.countText(visible), WordCounter.parse(source, format)
            .countSelections(listOf(SourceRange(start, start + selected.length))))
    }
}
