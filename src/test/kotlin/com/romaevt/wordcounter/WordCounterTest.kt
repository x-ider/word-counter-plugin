package com.romaevt.wordcounter

import org.junit.Assert.assertEquals
import org.junit.Test

class WordCounterTest {
    @Test fun `plain text does not interpret markup or frontmatter`() {
        for (source in listOf(
            "---\ntitle: Hello\n---\nBody",
            "**Hello** [world](https://example.com)",
            "<Widget title=\"Hello\">{value}</Widget> &amp;",
            "import Box from './Box';\n<!-- Hello -->",
            "```text\nHello\n```\n\n    indented text",
        )) {
            assertText(source, source, DocumentFormat.PLAIN_TEXT)
        }
    }

    @Test fun `plain text characters include literal formatting symbols`() {
        assertEquals(TextCounts(1, 9, 9), WordCounter.count("**Hello**", DocumentFormat.PLAIN_TEXT))
        assertEquals(TextCounts(2, 17, 14), WordCounter.count("---\ntitle: Hi\n---", DocumentFormat.PLAIN_TEXT))
        assertEquals(TextCounts(1, 5, 5), WordCounter.count("**Hello**", DocumentFormat.MARKDOWN))
    }

    @Test fun `plain text preserves leading trailing and layout whitespace`() {
        assertEquals(TextCounts(1, 7, 2), WordCounter.count(" \tHi\n\n ", DocumentFormat.PLAIN_TEXT))
        assertEquals(TextCounts(0, 3, 0), WordCounter.count("\n \t", DocumentFormat.PLAIN_TEXT))
        assertEquals(TextCounts(0, 0, 0), WordCounter.count("", DocumentFormat.PLAIN_TEXT))
    }

    @Test fun `plain text normalizes line endings and omits the encoding marker`() {
        val source = "\uFEFFHi\r\n\r\nthere\r"
        assertText("Hi\n\nthere\n", source, DocumentFormat.PLAIN_TEXT)
        assertEquals(TextCounts(2, 10, 7), WordCounter.count(source, DocumentFormat.PLAIN_TEXT))
    }

    @Test fun `plain text uses the same Unicode character and word rules`() {
        val source = "Caf\u00E9 Cafe\u0301 \uD83D\uDC69\u200D\uD83D\uDCBB"
        assertEquals(TextCounts(2, 11, 9), WordCounter.count(source, DocumentFormat.PLAIN_TEXT))
        assertEquals(3, WordCounter.count("don't state-of-the-art snake_case", DocumentFormat.PLAIN_TEXT).words)
    }

    @Test fun `empty documents have zero counts`() {
        for (source in listOf("", " \n\t ", "---\ntitle: Hidden\n---\n", "<!-- hidden -->")) {
            assertEquals(TextCounts(0, 0, 0), WordCounter.count(source))
        }
    }

    @Test fun `counts visible link labels without Markdown syntax`() {
        val source = "**Hello** [world](https://example.com \"Hidden title\")"
        assertText("Hello world", source)
        assertEquals(TextCounts(2, 11, 10), WordCounter.count(source))
    }

    @Test fun `frontmatter is recognized only at the start`() {
        assertText("Hello world", "---\ntitle: Hidden\n---\nHello world")
        assertText("Hello world", "\uFEFF---\r\ntitle: Hidden\r\n...\r\nHello world")
        assertText("Before\nAfter", "Before\n\n---\n\nAfter")
    }

    @Test fun `unfinished frontmatter is not counted while typing`() {
        assertText("", "---\ntitle: Still typing")
        assertText("", "---")
    }

    @Test fun `headings lists and quotes keep text not markers`() {
        assertText("Heading\nFirst\nSecond\nQuoted text", "# Heading\n\n- First\n- Second\n\n> Quoted text")
        assertText("First\nSecond", "1. First\n2. Second")
    }

    @Test fun `formatting inside a word does not split it`() {
        assertText("cooperate", "co**operate**")
        assertEquals(1, WordCounter.count("co**operate**").words)
    }

    @Test fun `reference links and balanced destinations use labels`() {
        assertText("A useful guide", "A [useful **guide**][ref]\n\n[ref]: https://example.com/a_(b) \"Hidden\"")
        assertText("guide", "[guide]((https://example.com/a))")
    }

    @Test fun `URL-only links are excluded but plain text URLs are not guessed`() {
        assertText("", "<https://example.com>\n\n<me@example.com>")
        assertText("", "[https://example.com](https://example.com)")
        assertText("Visit https://example.com", "Visit https://example.com")
    }

    @Test fun `image alt text is excluded including linked images`() {
        assertText("", "![Hidden **caption**](image.png \"Hidden title\")")
        assertText("", "[![Hidden caption](image.png)](https://example.com)")
    }

    @Test fun `inline code counts literally and fenced code does not`() {
        assertText("Run npm run build.", "Run `npm run build`.")
        assertText("Before\nAfter", "Before\n\n```ts\nconst hidden = 42;\n```\n\nAfter")
        assertText("After", "~~~js\nhidden()\n~~~\n\nAfter")
    }

    @Test fun `indented and unfinished fenced code are excluded`() {
        assertText("Before\nAfter", "Before\n\n    hidden code\n\nAfter")
        assertText("Before", "Before\n\n```ts\nstill typing")
    }

    @Test fun `tables task lists and strikethrough use visible text`() {
        assertText("Name\tState\nArticle\tPublished", "| Name | State |\n| --- | --- |\n| Article | Published |")
        assertText("Done\nLater\nOld text", "- [x] Done\n- [ ] Later\n\n~~Old~~ text")
    }

    @Test fun `entities and escapes count their visible characters`() {
        assertText("Tom & Jerry *literal*", "Tom &amp; Jerry \\*literal\\*")
        assertText("<Widget> {literal}", "\\<Widget> \\{literal\\}", DocumentFormat.MDX)
    }

    @Test fun `layout whitespace is normalized without collapsing text spaces`() {
        assertText("one two\nthree\nfour", "one\ntwo\n\n\nthree  \nfour")
        assertEquals(TextCounts(2, 8, 6), WordCounter.count("one  two"))
        assertEquals(TextCounts(2, 7, 6), WordCounter.count("one\ttwo"))
        assertEquals(TextCounts(2, 7, 6), WordCounter.count("one\u00A0two"))
        assertEquals(TextCounts(2, 7, 6), WordCounter.count("one\r\n\r\ntwo"))
    }

    @Test fun `contractions compounds identifiers and numbers are words`() {
        assertEquals(6, WordCounter.count("don't I\u2019m state-of-the-art snake_case 2026 42").words)
        assertEquals(2, WordCounter.count("one\u2014two").words)
        assertEquals(0, WordCounter.count("... ! ?").words)
    }

    @Test fun `letters in other scripts are counted`() {
        assertEquals(2, WordCounter.count("\u041F\u0440\u0438\u0432\u0435\u0442 \u043C\u0438\u0440").words)
        // No dictionary-based segmentation is attempted for scripts without spaces.
        assertEquals(1, WordCounter.count("\u4F60\u597D\u4E16\u754C").words)
    }

    @Test fun `combining marks and emoji count as grapheme clusters`() {
        assertEquals(TextCounts(2, 9, 8), WordCounter.count("Caf\u00E9 Cafe\u0301"))
        val emoji = "\uD83D\uDC69\u200D\uD83D\uDCBB \uD83D\uDC4D\uD83C\uDFFD \uD83C\uDDEC\uD83C\uDDE7"
        assertEquals(TextCounts(0, 5, 3), WordCounter.count(emoji))
    }

    @Test fun `HTML wrappers preserve literal text and block boundaries`() {
        assertText("Hello world", "<span>Hello **world**</span>")
        assertText("Hello\nworld", "<p>Hello</p><p>world</p>")
        assertText("Hello\nworld", "Hello<br>world")
        assertText("Hello world", "Hello <!-- hidden -->world")
    }

    @Test fun `HTML executable and preformatted blocks are excluded`() {
        assertText("Visible", "<script>hidden()</script>\n<style>p { color: red; }</style>\n<pre>hidden</pre>\nVisible")
        assertText("Visible", "<SCRIPT>hidden()</SCRIPT>\nVisible")
        assertText("Visible", "<script />\nVisible", DocumentFormat.MDX)
    }

    @Test fun `MDX imports and exports are not prose`() {
        assertText("Visible", "import Callout from './Callout.astro';\n\nVisible", DocumentFormat.MDX)
        assertText("Visible", "import {\n  One,\n  Two,\n} from './components';\n\nexport const metadata = {\n  title: 'Hidden',\n};\n\nVisible", DocumentFormat.MDX)
        assertText("Visible", "import './side-effect';\n\nVisible", DocumentFormat.MDX)
        assertText("Visible", "import Default,\n{ Named } from\n'./components';\n\nVisible", DocumentFormat.MDX)
        assertText("Visible", "  import Callout from './Callout.astro';\n\nVisible", DocumentFormat.MDX)
    }

    @Test fun `MDX wrappers keep children but omit props and expressions`() {
        val source = "<Callout\n  title=\"Hidden > title\"\n  value={{ label: '} hidden', nested: { number: 1 } }}\n>\nHello **world**\n</Callout>\n\n<Chart value={42} />"
        assertText("Hello world", source, DocumentFormat.MDX)
        assertText("Hello world", "<>Hello <Em>world</Em></>", DocumentFormat.MDX)
    }

    @Test fun `MDX comments nested expressions and template strings are omitted`() {
        assertText("Hello world", "Hello {/* hidden } */}world", DocumentFormat.MDX)
        assertText("Visible", "{items.map(item => ({ label: item.name }))}\nVisible", DocumentFormat.MDX)
        assertText("Visible", "{`hidden } template`}\nVisible", DocumentFormat.MDX)
        assertText("Visible", "{// hidden }\nvalue}\nVisible", DocumentFormat.MDX)
    }

    @Test fun `code is protected from MDX cleanup`() {
        assertText("<Widget> {value} import x", "`<Widget> {value} import x`", DocumentFormat.MDX)
        assertText("{literal}", "<Callout>`{literal}`</Callout>", DocumentFormat.MDX)
        assertText("Visible", "```mdx\n<Callout>\n{unfinished\n```\n\nVisible", DocumentFormat.MDX)
    }

    @Test fun `Markdown keeps literal braces and import sentences`() {
        assertText("{hello} world", "{hello} world")
        assertText("import something useful", "import something useful")
    }

    @Test fun `ordinary comparisons are not HTML tags`() {
        assertText("2 < 3 and 5 > 4", "2 < 3 and 5 > 4", DocumentFormat.MDX)
    }

    @Test fun `unfinished markup remains safe while typing`() {
        for (tail in listOf("<Callout title=\"unfinished", "{unfinished", "<!-- unfinished", "import {\n  unfinished")) {
            assertText("Visible", "Visible\n\n$tail", DocumentFormat.MDX)
        }
        assertText("Hello [world", "Hello [world")
    }

    @Test fun `blog fixture matches readable content`() {
        val source = javaClass.getResource("/article.mdx")!!.readText()
        assertText("A static site\nThis is a useful guide.\nBuild once\nRun npm run build.\nDone\nLater\nContent\tProduction\nArticle\tIncluded", source, DocumentFormat.MDX)
        assertEquals(20, WordCounter.count(source, DocumentFormat.MDX).words)
    }

    @Test fun `large documents and repeated calls do not retain previous totals`() {
        val source = "Hello **world**.\n\n".repeat(5_000)
        assertEquals(TextCounts(10_000, 64_999, 55_000), WordCounter.count(source))
        assertEquals(TextCounts(1, 5, 5), WordCounter.count("Hello"))
        assertEquals(TextCounts(10_000, 64_999, 55_000), WordCounter.count(source))
    }

    @Test fun `long compound words do not require recursive regex backtracking`() {
        assertEquals(1, WordCounter.count("part-".repeat(5_000) + "end").words)
    }

    private fun assertText(expected: String, source: String, format: DocumentFormat = DocumentFormat.MARKDOWN) {
        val parsed = WordCounter.parse(source, format)
        assertEquals(expected, parsed.text)
        assertEquals(parsed.counts, parsed.countSelections(listOf(SourceRange(0, source.length))))
    }
}
