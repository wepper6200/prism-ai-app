package ai.prism.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReplyMarkupTest {
    @Test
    fun preservesUnfinishedCodeWhileStreaming() {
        val fence = "\u0060".repeat(3)
        val blocks = ReplyMarkup.parse("Ответ\n" + fence + "kotlin\nval n = 1\n")
        assertEquals("Ответ", assertIs<ReplyBlock.Prose>(blocks[0]).text)
        val code = assertIs<ReplyBlock.Code>(blocks[1])
        assertEquals("kotlin", code.language)
        assertEquals("val n = 1\n", code.text)
    }

    @Test
    fun tableHandlesEscapedPipesAndMissingCells() {
        val table = assertIs<ReplyBlock.Table>(ReplyMarkup.parse("| Имя | Значение |\n| :--- | ---: |\n| A \\| B | 42 |\n| C |").single())
        assertEquals(listOf("Имя", "Значение"), table.header)
        assertEquals(listOf("A | B", "42"), table.rows[0])
        assertEquals(listOf("C", ""), table.rows[1])
    }

    @Test
    fun proseAfterFenceIsNotLost() {
        val fence = "\u0060".repeat(3)
        val blocks = ReplyMarkup.parse(fence + "\nx\n" + fence + "\nГотово")
        assertEquals("x", assertIs<ReplyBlock.Code>(blocks[0]).text)
        assertEquals("Готово", assertIs<ReplyBlock.Prose>(blocks[1]).text)
    }
}
