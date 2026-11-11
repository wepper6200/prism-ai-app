package ai.prism.service

import ai.prism.service.voice.SpeechTextSplitter
import ai.prism.service.voice.speechSentences
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechTextTest {

    @Test
    fun markdownIsReadAsPlainSentences() {
        val text = """
            ## Погода
            **Сегодня** тепло: +20. Возьмите [зонт](https://example.com/umbrella)!
            - Утром ясно
            - Вечером дождь
            1. Подробнее: https://weather.example/msk
        """.trimIndent()

        assertEquals(
            listOf("Погода", "Сегодня тепло: +20.", "Возьмите зонт!", "Утром ясно", "Вечером дождь", "Подробнее:"),
            speechSentences(text),
        )
    }

    @Test
    fun codeBlocksAreSkipped() {
        val text = "Вот пример:\n```kotlin\nfun main() = println(\"hi\")\n```\nГотово."

        assertEquals(listOf("Вот пример:", "Готово."), speechSentences(text))
    }

    @Test
    fun streamingTextGivesEachSentenceOnce() {
        val splitter = SpeechTextSplitter()
        val full = "Привет! Я Prism AI. Чем могу помочь?"
        val spoken = mutableListOf<String>()
        // Текст приходит по кусочкам, как при генерации, с пробелом в начале.
        for (end in 1..full.length) spoken += splitter.update(" " + full.substring(0, end))
        spoken += splitter.finish(full)

        assertEquals(listOf("Привет!", "Я Prism AI.", "Чем могу помочь?"), spoken)
    }

    @Test
    fun unfinishedCodeBlockIsNotSpokenWhileStreaming() {
        val splitter = SpeechTextSplitter()
        assertEquals(listOf("Код:"), splitter.update("Код:\n```\nval x = 1.\n"))
        assertEquals(emptyList(), splitter.update("Код:\n```\nval x = 1.\nval y = 2.\n"))
        assertEquals(listOf("Всё."), splitter.finish("Код:\n```\nval x = 1.\nval y = 2.\n```\nВсё."))
    }

    @Test
    fun tablesAndEmphasisAreCleaned() {
        val text = "| Город | Темп. |\n|---|---|\n| Москва | +5 |\n_Важно_: file_name остаётся."

        assertEquals(listOf("Город, Темп.", "Москва, +5", "Важно: file_name остаётся."), speechSentences(text))
    }
}
