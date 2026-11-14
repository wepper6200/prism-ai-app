package ai.prism.service

import ai.prism.domain.music.MusicRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Описание звука переводится; слова песни сохраняются на исходном языке. */
internal object MusicPromptPolicy {
    private val lyricsHeader = Regex("(?m)^\\s*(?:текст(?: песни)?|слова(?: песни)?|lyrics)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val section = Regex("\\[[^]\\n]*]")
    private val instrumental = Regex("без слов|без вокала|инструментал|instrumental|no vocals|without vocals", RegexOption.IGNORE_CASE)
    private val vocals = Regex("песн|спой|вокал|куплет|припев|баллад|рэп|\\bsong\\b|\\bsing\\b|\\bvocal|\\brap\\b", RegexOption.IGNORE_CASE)

    fun suppliedLyrics(source: String): String? = lyricsHeader.find(source)
        ?.let { source.substring(it.range.last + 1).trim() }?.takeIf { it.isNotEmpty() }

    fun language(source: String): String {
        val explicit = listOf(
            "en" to "на английском|in english|(?:язык|language)\\s*:\\s*en\\b",
            "ru" to "на русском|in russian|(?:язык|language)\\s*:\\s*ru\\b",
            "es" to "на испанском|in spanish|(?:язык|language)\\s*:\\s*es\\b",
            "fr" to "на французском|in french|(?:язык|language)\\s*:\\s*fr\\b",
            "de" to "на немецком|in german|(?:язык|language)\\s*:\\s*de\\b",
        )
        val description = lyricsHeader.find(source)?.let { source.take(it.range.first) } ?: source
        explicit.firstOrNull { Regex(it.second, RegexOption.IGNORE_CASE).containsMatchIn(description) }?.let { return it.first }
        return if (ImagePromptPolicy.hasCyrillic(suppliedLyrics(source) ?: source)) "ru" else "en"
    }

    fun parse(source: String, answer: String): MusicRequest? {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end < start) return null
        val value = try { Json.parseToJsonElement(answer.substring(start, end + 1)) as? JsonObject }
            catch (_: IllegalArgumentException) { null } ?: return null
        fun field(name: String): String? = (value[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        val caption = field("caption") ?: return null
        if (!ImagePromptPolicy.isUsableTranslation(source, caption)) return null
        val lyrics = suppliedLyrics(source) ?: field("lyrics") ?: return null
        if (lyrics == "[Instrumental]") {
            if (vocals.containsMatchIn(source) && !instrumental.containsMatchIn(source)) return null
            return MusicRequest(caption, lyrics = lyrics)
        }
        if (suppliedLyrics(source) == null && instrumental.containsMatchIn(source)) return null
        val words = section.replace(lyrics, "").filter { it.isLetter() }
        if (words.length < 8 || lyrics.length > 6000) return null
        val language = language(source)
        // Английский текст вместо русского не должен незаметно попадать в синтезатор.
        if (language == "ru" && words.count { it in 'Ѐ'..'ӿ' } * 10 < words.length * 7) return null
        return MusicRequest(caption, lyrics = lyrics, vocalLanguage = language)
    }
}
