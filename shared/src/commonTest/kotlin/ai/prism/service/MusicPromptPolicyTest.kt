package ai.prism.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicPromptPolicyTest {
    @Test
    fun englishLyricsCannotReplaceRussianVocals() {
        assertNull(MusicPromptPolicy.parse("Песня о доме", """{"caption":"Warm acoustic pop","lyrics":"Take me home tonight"}"""))
        val result = MusicPromptPolicy.parse("Песня о доме", """{"caption":"Warm acoustic pop","lyrics":"[Verse]\nМы вернёмся снова домой\nТам родные рядом с тобой"}""")
        assertEquals("ru", result?.vocalLanguage)
    }

    @Test
    fun explicitLyricsAndLanguageArePreserved() {
        val lyrics = "[Verse]\nМы снова вместе, ты и я."
        val source = "Рок-баллада\nТекст песни:\n$lyrics"
        val result = MusicPromptPolicy.parse(source, """{"caption":"Rock ballad","lyrics":"Rewritten English text"}""")
        assertEquals(lyrics, result?.lyrics)
        assertEquals("ru", result?.vocalLanguage)
        assertEquals("en", MusicPromptPolicy.language("Песня на английском"))
        assertEquals("en", MusicPromptPolicy.language("Песня\nLyrics:\nTake me home tonight"))
    }

    @Test
    fun instrumentalDoesNotAcquireRussianVocals() {
        val result = MusicPromptPolicy.parse("Спокойное пианино без слов", """{"caption":"Calm solo piano instrumental","lyrics":"[Instrumental]"}""")
        assertEquals("[Instrumental]", result?.lyrics)
        assertEquals("unknown", result?.vocalLanguage)
        assertNull(MusicPromptPolicy.parse("Песня", "Это просто пояснение"))
    }
}
