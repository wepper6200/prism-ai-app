package ai.prism.service.voice

/**
 * Готовит ответ модели к озвучке: убирает разметку Markdown, блоки кода и адреса ссылок и делит текст на фразы.
 *
 * Работает по мере генерации: [update] получает весь текущий текст ответа и возвращает только новые
 * законченные фразы (до конца предложения или строки), [finish] — остаток после окончания ответа.
 * Начальные пробелы ответа не учитываются, поэтому окончательный текст (`trim()` от генерируемого)
 * продолжает уже озвученную часть.
 */
class SpeechTextSplitter {
    private var consumed = 0
    private var inCode = false
    private var atLineStart = true

    /** Новые законченные фразы из растущего текста [text]. */
    fun update(text: String): List<String> {
        val normalized = text.trimStart()
        val end = safeEnd(normalized)
        if (end <= consumed) return emptyList()
        return take(normalized, end)
    }

    /** Всё, что осталось неозвученным в окончательном тексте [text]. */
    fun finish(text: String): List<String> {
        val normalized = text.trimStart()
        if (normalized.length <= consumed) return emptyList()
        return take(normalized, normalized.length)
    }

    private fun take(text: String, end: Int): List<String> {
        val chunk = text.substring(consumed, end)
        consumed = end
        return sentences(clean(chunk))
    }

    /** Граница, до которой текст уже не изменится: после конца строки или знака конца предложения с пробелом. */
    private fun safeEnd(text: String): Int {
        for (i in text.length - 1 downTo consumed) {
            val c = text[i]
            if (c == '\n') return i + 1
            if (c.isWhitespace() && i > 0 && text[i - 1] in SENTENCE_END) return i + 1
        }
        return consumed
    }

    /** Текст без разметки; блоки кода между ``` пропускаются целиком. */
    private fun clean(chunk: String): String {
        val out = StringBuilder()
        var start = 0
        while (start < chunk.length) {
            val newline = chunk.indexOf('\n', start)
            val end = if (newline < 0) chunk.length else newline + 1
            val piece = chunk.substring(start, end)
            if (atLineStart && piece.trimStart().startsWith("```")) {
                inCode = !inCode
                out.append('\n')
            } else if (!inCode) {
                out.append(cleanInline(piece, atLineStart))
            }
            atLineStart = piece.endsWith('\n')
            start = end
        }
        return out.toString()
    }

    private fun cleanInline(piece: String, lineStart: Boolean): String {
        var text = piece
        if (lineStart) text = text.replace(LINE_MARKUP, "")
        text = text.replace(LINK, "$1")
            .replace(URL, "")
            .replace(EMPHASIS, "")
            .replace('`', ' ')
            .replace(TABLE_RULE, "")
            .replace('|', ',')
        return text
    }

    private fun sentences(text: String): List<String> =
        text.split(SENTENCE_SPLIT)
            .map { it.replace(SPACES, " ").replace(" ,", ",").trim().trim(',').trim() }
            .filter { sentence -> sentence.any { it.isLetterOrDigit() } }

    private companion object {
        val SENTENCE_END = setOf('.', '!', '?', '…', ':', ';')
        val SENTENCE_SPLIT = Regex("(?<=[.!?…])\\s+|\\n+")
        /** Заголовки, цитаты, маркеры и номера списков в начале строки. */
        val LINE_MARKUP = Regex("^\\s*(#{1,6}\\s+|>\\s*|[-*+•]\\s+|\\d{1,3}[.)]\\s+)")
        val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
        val URL = Regex("https?://\\S+")
        val EMPHASIS = Regex("\\*+|(?<![\\p{L}\\p{N}])_+|_+(?![\\p{L}\\p{N}])|~~")
        val TABLE_RULE = Regex("^\\s*\\|?\\s*:?-{3,}.*$", RegexOption.MULTILINE)
        val SPACES = Regex("\\s+")
    }
}

/** Все фразы готового текста для озвучки. */
fun speechSentences(text: String): List<String> = SpeechTextSplitter().finish(text)
