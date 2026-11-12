package ai.prism.inference

import kotlin.test.Test
import kotlin.test.assertEquals

class ThinkReplyFilterTest {

    /** Прогоняет текст через фильтр фрагментами по [size] символов. */
    private fun run(text: String, size: Int): String {
        val filter = ThinkReplyFilter()
        val out = StringBuilder()
        text.chunked(size).forEach { out.append(filter.push(it)) }
        return out.append(filter.finish()).toString()
    }

    @Test
    fun thinkingBlockIsHidden() {
        val raw = "<think>\nПользователь спрашивает о столице.\n</think>\n\nСтолица Франции — Париж."
        for (size in listOf(1, 3, 8, raw.length)) assertEquals("Столица Франции — Париж.", run(raw, size), "фрагменты по $size")
    }

    @Test
    fun answerWithoutThinkingIsShownAsIs() {
        for (size in listOf(1, 4, 100)) assertEquals("Привет! Я Prism AI.", run("\n\nПривет! Я Prism AI.", size))
    }

    @Test
    fun strayCloseTagAndUnfinishedThinkingAreDropped() {
        assertEquals("Ответ.", run("Ответ.</think>", 2))
        assertEquals("Ответ.", run("Ответ.<think>ещё думаю", 3))
    }

    @Test
    fun textStreamsBeforeTheEnd() {
        val filter = ThinkReplyFilter()
        assertEquals("", filter.push("<think>x</think>\n"))
        assertEquals("Пар", filter.push("Пар"))
        assertEquals("иж", filter.push("иж"))
        assertEquals("", filter.finish())
    }
}
