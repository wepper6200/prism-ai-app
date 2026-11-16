package ai.prism.domain.music

import kotlinx.serialization.Serializable

/** Independent fields: supplied lyrics are appended LAST and kept verbatim. */
@Serializable
data class MusicStudioDraft(
    val style: String = "",
    val language: String = "auto",
    val lyrics: String = "",
    val structure: String = "",
    val instrumental: Boolean = false,
) {
    val hasContent: Boolean get() = style.isNotBlank() || lyrics.isNotBlank() || structure.isNotBlank() || instrumental
    val isEmpty: Boolean get() = style.isBlank() && lyrics.isBlank() && structure.isBlank() && !instrumental && language == "auto"
    fun prompt(description: String): String = buildString {
        val header = lyricsHeader.find(description)
        val brief = header?.let { description.substring(0, it.range.first) } ?: description
        val pastedLyrics = header?.let { description.substring(it.range.last + 1).trim() }
        appendLine(brief.trim())
        if (style.isNotBlank()) appendLine("Style: " + style.trim())
        if (structure.isNotBlank()) appendLine("Structure: " + structure.trim())
        if (language in listOf("ru", "en", "es", "fr", "de")) appendLine("language: " + language)
        if (instrumental) appendLine("Instrumental, no vocals.")
        else {
            val words = lyrics.takeIf { it.isNotBlank() } ?: pastedLyrics
            if (!words.isNullOrBlank()) { appendLine("Текст песни:"); append(words) }
        }
    }.trim()

    private companion object {
        val lyricsHeader = Regex("(?m)^\\s*(?:текст(?: песни)?|слова(?: песни)?|lyrics)\\s*:\\s*", RegexOption.IGNORE_CASE)
    }
}
