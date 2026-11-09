package ai.prism.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImagePromptPolicyTest {

    @Test
    fun refusalsAreNotTranslations() {
        assertTrue(ImagePromptPolicy.isRefusal("I can't help with that request."))
        assertTrue(ImagePromptPolicy.isRefusal("«Извините, я не могу это перевести»"))
        assertFalse(ImagePromptPolicy.isUsableTranslation("рыжий кот", "I'm sorry, but I cannot do that."))
        assertFalse(ImagePromptPolicy.isUsableTranslation("рыжий кот", "рыжий кот"))
        assertFalse(ImagePromptPolicy.isUsableTranslation("рыжий кот", "BLOCKED"))
        assertTrue(ImagePromptPolicy.isUsableTranslation("рыжий кот на диване", "A ginger cat on a sofa"))
    }

    @Test
    fun blockedWordsDoNotCatchInnocentOnes() {
        assertTrue(ImagePromptPolicy.containsBlockedWords("Голая девушка на пляже"))
        assertTrue(ImagePromptPolicy.containsBlockedWords("a NSFW picture"))
        assertTrue(ImagePromptPolicy.containsBlockedWords("лужа крови"))
        assertFalse(ImagePromptPolicy.containsBlockedWords("кот спит на кровати под голубым пледом"))
        assertFalse(ImagePromptPolicy.containsBlockedWords("a castle in Sussex"))
    }

    @Test
    fun verdictsAreParsed() {
        assertTrue(ImagePromptPolicy.isBlockedVerdict(" BLOCKED."))
        assertFalse(ImagePromptPolicy.isBlockedVerdict("A blocked road in the mountains"))
        assertTrue(ImagePromptPolicy.isUnsafeReview("Yes"))
        assertTrue(ImagePromptPolicy.isUnsafeReview("Да."))
        assertFalse(ImagePromptPolicy.isUnsafeReview("NO"))
    }

    @Test
    fun photoSizeKeepsProportionsInStepsOf64() {
        assertEquals(1024 to 768, ChatGenerationService.fitImageSize(640, 480, 1024))
        assertEquals(448 to 768, ChatGenerationService.fitImageSize(720, 1280, 768))
        assertEquals(1024 to 256, ChatGenerationService.fitImageSize(4000, 100, 1024))
    }
}
