package ai.prism.service

import ai.prism.service.voice.SpeechNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechNormalizerTest {

    private fun ru(text: String) = SpeechNormalizer.normalize(text, "ru")

    @Test
    fun techTermsAndAcronymsAreSpokenInRussian() {
        assertEquals("В 2026 году вышел андроид 17, его уровень эй-пи-ай — 37.", ru("В 2026 году вышел Android 17, его уровень API — 37."))
        assertEquals("Я Призм эй-ай, работаю на джи-пи-ю.", ru("Я Prism AI, работаю на GPU."))
        assertEquals("Скачайте эс-ди-кей с гитхаб.", ru("Скачайте SDK с GitHub."))
        assertEquals("Пишем на котлин и джаваскрипт.", ru("Пишем на Kotlin и JavaScript."))
    }

    @Test
    fun percentAndDegreesAgreeWithTheNumber() {
        assertEquals("Скидка 21 процент, налог 3 процента, рост 15 процентов.", ru("Скидка 21%, налог 3 %, рост 15%."))
        assertEquals("На улице плюс 5 градусов по Цельсию, вчера было 2 градуса.", ru("На улице +5 °C, вчера было 2°."))
        assertEquals("Заказ номер 7 и счёт.", ru("Заказ № 7 & счёт."))
    }

    @Test
    fun ordinaryWordsAndOtherLanguagesStay() {
        assertEquals("Привет, как дела?", ru("Привет, как дела?"))
        assertEquals("Hello API world", SpeechNormalizer.normalize("Hello API world", "en"))
        // Слова, где аббревиатура — часть слова, не трогаются.
        assertEquals("файл readme и APIs", ru("файл readme и APIs"))
    }
}
