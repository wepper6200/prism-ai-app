package ai.prism.data.documents

/**
 * Текст из `word/document.xml` документа DOCX: абзацы — отдельными строками, ячейки таблиц — через табуляцию.
 * Разбор потоковый, без DOM: нужен только текст.
 */
internal object DocxText {
    private val TAG = Regex("<(/?)([A-Za-z0-9]+:)?([A-Za-z0-9]+)([^>]*?)(/?)>")

    fun fromDocumentXml(xml: String): String {
        val out = StringBuilder()
        var inText = false
        var position = 0
        for (match in TAG.findAll(xml)) {
            if (inText && match.range.first > position) out.append(decodeEntities(xml.substring(position, match.range.first)))
            position = match.range.last + 1
            val closing = match.groupValues[1] == "/"
            val selfClosing = match.groupValues[5] == "/"
            when (match.groupValues[3]) {
                "t" -> inText = !closing && !selfClosing
                "tab" -> if (!closing) out.append('\t')
                "br", "cr" -> if (!closing) out.append('\n')
                "p" -> if (closing || selfClosing) out.append('\n')
                "tc" -> if (closing) out.append('\t')
                "tr" -> if (closing) out.append('\n')
            }
        }
        return out.toString()
            .lines()
            .joinToString("\n") { it.trimEnd('\t', ' ') }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        return Regex("&(#x?[0-9A-Fa-f]+|amp|lt|gt|quot|apos);").replace(text) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                    code?.let { runCatching { codePointToString(it) }.getOrNull() } ?: match.value
                }
            }
        }
    }

    private fun codePointToString(code: Int): String = if (code < 0x10000) {
        code.toChar().toString()
    } else {
        val offset = code - 0x10000
        charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
    }
}
