package ai.prism.inference

import kotlin.test.Test
import kotlin.test.assertEquals

class Gemma4ReplyFilterTest {

    /** Прогоняет текст через фильтр фрагментами по [size] символов. */
    private fun run(text: String, size: Int, filter: Gemma4ReplyFilter = Gemma4ReplyFilter()): String {
        val out = StringBuilder()
        text.chunked(size).forEach { out.append(filter.push(it)) }
        return out.append(filter.finish()).toString()
    }

    @Test
    fun bareReasoningBeforeCloseTagIsHidden() {
        val raw = "The user is asking for the capital of France.\nThe capital is Paris.<channel|>Paris"
        for (size in listOf(1, 3, 7, raw.length)) assertEquals("Paris", run(raw, size), "фрагменты по $size")
    }

    @Test
    fun explicitThoughtBlocksAndStrayTagsAreRemoved() {
        val raw = "<|channel>thought\nДумаю…<channel|>Это красный квадрат.<|channel>thought\n<channel|><channel|>"
        for (size in listOf(1, 2, 5, raw.length)) assertEquals("Это красный квадрат.", run(raw, size), "фрагменты по $size")
    }

    @Test
    fun answerWithoutTagsIsShownAtTheEndOrAfterLimit() {
        assertEquals("Просто ответ.", run("Просто ответ.", 2))

        val filter = Gemma4ReplyFilter(flushLimit = 10)
        assertEquals("", filter.push("Длинный "))
        assertEquals("Длинный ответ", filter.push("ответ"))
        assertEquals(" дальше", filter.push(" дальше"))
        assertEquals("", filter.finish())
    }

    @Test
    fun answerIsStreamedAfterReasoning() {
        val filter = Gemma4ReplyFilter()
        assertEquals("", filter.push("Рассуждение"))
        assertEquals("", filter.push("<chan"))
        assertEquals("Пр", filter.push("nel|>Пр"))
        assertEquals("ивет", filter.push("ивет<"))
        assertEquals("<b>", filter.push("b>"))
        assertEquals("", filter.finish())
    }
}
