package ai.prism.service.documents

import ai.prism.domain.documents.TextSection

/** Фрагмент текста файла до вычисления эмбеддинга. */
data class TextPiece(val text: String, val page: Int?)

/**
 * Делит текст файла на фрагменты для поиска: примерно по [targetChars] символов, по границам абзацев и предложений.
 * Соседние фрагменты одной страницы перекрываются на [overlapChars] символов, чтобы мысль на границе не потерялась.
 */
class TextChunker(
    private val targetChars: Int = DEFAULT_TARGET_CHARS,
    private val overlapChars: Int = DEFAULT_OVERLAP_CHARS,
) {
    fun chunk(sections: List<TextSection>): List<TextPiece> {
        val units = sections.flatMap { section -> units(section.text).map { TextPiece(it, section.page) } }
        val result = mutableListOf<TextPiece>()
        val current = StringBuilder()
        var page: Int? = null
        var hasNewText = false
        fun flush() {
            if (hasNewText) result += TextPiece(current.toString().trim(), page)
            hasNewText = false
        }
        for (unit in units) {
            // Фрагмент не переходит на другую страницу: в ответе можно сослаться на точную страницу.
            if (current.isNotEmpty() && unit.page != page) {
                flush()
                current.clear()
            }
            if (current.isNotEmpty() && current.length + unit.text.length + 1 > targetChars) {
                flush()
                val overlap = tail(current.toString())
                current.clear()
                current.append(overlap)
            }
            if (current.isEmpty()) page = unit.page
            if (current.isNotEmpty()) current.append(if (unit.text.startsWith(PARAGRAPH_MARK)) "\n" else " ")
            current.append(unit.text.removePrefix(PARAGRAPH_MARK))
            hasNewText = true
        }
        flush()
        return result.filter { it.text.isNotBlank() }
    }

    /** Абзацы, а слишком длинные — предложения и куски по словам. Начало абзаца помечено [PARAGRAPH_MARK]. */
    private fun units(text: String): List<String> = normalize(text)
        .split(PARAGRAPH_BREAK)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .flatMap { paragraph ->
            val parts = if (paragraph.length <= targetChars) {
                listOf(paragraph)
            } else {
                paragraph.split(SENTENCE_END).flatMap { splitByWords(it.trim()) }.filter { it.isNotEmpty() }
            }
            parts.mapIndexed { index, part -> if (index == 0) PARAGRAPH_MARK + part else part }
        }

    private fun splitByWords(sentence: String): List<String> {
        if (sentence.length <= targetChars) return listOf(sentence)
        val pieces = mutableListOf<String>()
        var rest = sentence
        while (rest.length > targetChars) {
            val cut = rest.lastIndexOf(' ', targetChars).takeIf { it > targetChars / 2 } ?: targetChars
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }

    /** Конец фрагмента для перекрытия — с начала слова. */
    private fun tail(text: String): String {
        if (overlapChars <= 0 || text.length <= overlapChars) return ""
        val start = text.length - overlapChars
        val wordStart = text.indexOf(' ', start).takeIf { it in start until text.length } ?: start
        return text.substring(wordStart).trim()
    }

    private fun normalize(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(' ', ' ')
        .lines()
        .joinToString("\n") { it.replace(SPACES, " ").trim() }

    companion object {
        const val DEFAULT_TARGET_CHARS = 900
        const val DEFAULT_OVERLAP_CHARS = 150
        private const val PARAGRAPH_MARK = "\u0000"
        private val PARAGRAPH_BREAK = Regex("\n\\s*\n+")
        private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
        private val SPACES = Regex("[ \\t\\u000B\\f]+")
    }
}
