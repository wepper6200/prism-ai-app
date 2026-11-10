package ai.prism.service

import ai.prism.service.agent.HtmlText
import ai.prism.service.agent.LineDiff
import ai.prism.service.agent.ToolCallProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentProtocolTest {

    @Test
    fun toolCallWithRawCodeIsParsed() {
        val reply = """Создам файл.
            |<tool name="write_file">
            |<path>src/main.py</path>
            |<content>
            |if a < b and "x" != 'y':
            |    print("Привет")
            |</content>
            |</tool>""".trimMargin()

        assertTrue(ToolCallProtocol.isComplete(reply))
        val call = ToolCallProtocol.parse(reply)!!
        assertEquals("write_file", call.name)
        assertEquals("src/main.py", call.args["path"])
        assertEquals("if a < b and \"x\" != 'y':\n    print(\"Привет\")", call.args["content"])
        assertEquals("Создам файл.", ToolCallProtocol.visibleText(reply))
        assertTrue(ToolCallProtocol.callText(reply + "\nлишнее").endsWith("</tool>"))
    }

    @Test
    fun plainAnswerHasNoCall() {
        assertNull(ToolCallProtocol.parse("Готово: файл создан."))
        assertFalse(ToolCallProtocol.isComplete("Сейчас <tool name=\"read_file\"><path>a"))
    }

    @Test
    fun partialMarkupIsHiddenWhileStreaming() {
        assertEquals("Смотрю", ToolCallProtocol.visibleText("Смотрю\n<to"))
        assertEquals("Смотрю", ToolCallProtocol.visibleText("Смотрю\n```xml\n<tool name=\"list_files\">"))
        assertEquals("a < b", ToolCallProtocol.visibleText("a < b"))
    }

    @Test
    fun duckDuckGoResultsAreParsedWithoutAds() {
        val html = """
            <div class="result result--ad"><a class="result__a" href="https://duckduckgo.com/y.js?ad_domain=shop.example">Реклама</a></div>
            <div class="result results_links"><h2 class="result__title">
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fru.wikipedia.org%2Fwiki%2F%D0%9A%D0%BE%D1%82&amp;rut=abc">Кот — <b>Википедия</b></a></h2>
            <a class="result__snippet" href="x">Домашняя <b>кошка</b> &mdash; млекопитающее.</a></div>
            <div class="result"><a class="result__a" href="https://example.com/cats">Cats &amp; dogs</a>
            <div class="result__snippet">About cats</div></div>
        """

        val results = HtmlText.duckDuckGoResults(html, limit = 5)

        assertEquals(listOf("https://ru.wikipedia.org/wiki/Кот", "https://example.com/cats"), results.map { it.url })
        assertEquals("Кот — Википедия", results[0].title)
        assertEquals("Домашняя кошка — млекопитающее.", results[0].snippet)
        assertEquals("Cats & dogs", results[1].title)
        assertEquals("About cats", results[1].snippet)
    }

    @Test
    fun pageTextDropsScriptsAndKeepsParagraphs() {
        val html = "<html><head><title>Погода</title><style>p{}</style></head><body><script>alert(1)</script>" +
            "<h1>Москва</h1><p>Сегодня&nbsp;+5&#176;</p><!-- реклама --><p>Ветер 3 м/с</p></body></html>"

        assertEquals("Погода", HtmlText.title(html))
        assertEquals("Погода\nМосква\n\nСегодня +5°\n\nВетер 3 м/с", HtmlText.toText(html, 1_000).replace("\n\n\n", "\n\n"))
        assertTrue(HtmlText.toText(html, 10).endsWith("…"))
    }

    @Test
    fun diffShowsOnlyChangedLines() {
        val diff = LineDiff.of("a\nb\nc\nd", "a\nB\nc\nd")
        assertEquals("@@ line 2\n- b\n+ B", diff)
        assertEquals("+ x\n+ y", LineDiff.added("x\ny"))
    }
}
