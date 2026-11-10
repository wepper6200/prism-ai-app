package ai.prism.service.agent

import ai.prism.domain.agent.WebResult

/** Текст веб-страниц и разбор выдачи DuckDuckGo (HTML-версия без JavaScript). */
object HtmlText {
    private val hidden = Regex("(?is)<(script|style|noscript|svg|template|iframe)\\b[^>]*>.*?</\\1>")
    private val comments = Regex("(?s)<!--.*?-->")
    private val blockTags = Regex("(?i)</?(p|div|br|li|ul|ol|tr|table|section|article|header|footer|h[1-6]|pre|blockquote)\\b[^>]*>")
    private val tags = Regex("<[^>]+>")
    private val spaces = Regex("[ \\t\\u00A0]+")
    private val blankLines = Regex("\\n\\s*\\n\\s*\\n+")
    private val title = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val entity = Regex("&(#x?[0-9A-Fa-f]+|[A-Za-z]+);")
    private val resultLink = Regex("(?is)<a\\b[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*>(.*?)</a>")
    private val snippet = Regex("(?is)class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</(a|div|td)>")
    private val href = Regex("(?i)href=\"([^\"]+)\"")

    fun title(html: String): String? = title.find(html)?.groupValues?.get(1)?.let { decode(it).trim() }?.takeIf { it.isNotEmpty() }

    /** Текст страницы без разметки, скриптов и стилей; абзацы — с новой строки. Длиннее [maxChars] — обрезается. */
    fun toText(html: String, maxChars: Int): String {
        val text = html
            .replace(hidden, " ")
            .replace(comments, " ")
            .replace(blockTags, "\n")
            .replace(tags, " ")
            .let(::decode)
            .lines()
            .joinToString("\n") { it.replace(spaces, " ").trim() }
            .replace(blankLines, "\n\n")
            .trim()
        return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "…"
    }

    /** Результаты поиска DuckDuckGo (без рекламы): заголовок, адрес и описание. */
    fun duckDuckGoResults(html: String, limit: Int): List<WebResult> {
        val links = resultLink.findAll(html).toList()
        return links.mapIndexedNotNull { index, link ->
            val tag = html.substring(link.range.first, html.indexOf('>', link.range.first) + 1)
            val url = href.find(tag)?.groupValues?.get(1)?.let { resultUrl(decode(it)) } ?: return@mapIndexedNotNull null
            if (url.contains("duckduckgo.com/y.js") || !url.startsWith("http")) return@mapIndexedNotNull null
            val blockEnd = links.getOrNull(index + 1)?.range?.first ?: html.length
            val description = snippet.find(html.substring(link.range.last + 1, blockEnd))?.groupValues?.get(1).orEmpty()
            WebResult(
                title = toText(link.groupValues[1], 300),
                url = url,
                snippet = toText(description, 500),
            )
        }.distinctBy { it.url }.take(limit)
    }

    /** Ссылки выдачи ведут через переадресацию `//duckduckgo.com/l/?uddg=<адрес>`. */
    private fun resultUrl(raw: String): String {
        val target = raw.substringAfter("uddg=", missingDelimiterValue = "").substringBefore('&')
        if (target.isNotEmpty()) return percentDecode(target)
        return if (raw.startsWith("//")) "https:$raw" else raw
    }

    fun percentDecode(text: String): String {
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '%' && i + 2 <= text.lastIndex -> {
                    val code = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (code != null) {
                        bytes += code.toByte()
                        i += 3
                        continue
                    }
                    bytes += c.code.toByte()
                }
                c == '+' -> bytes += ' '.code.toByte()
                else -> c.toString().encodeToByteArray().forEach { bytes += it }
            }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    fun decode(text: String): String {
        if ('&' !in text) return text
        return entity.replace(text) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let(::codePoint)
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
                else -> NAMED[name]
            } ?: match.value
        }
    }

    private fun codePoint(code: Int): String? = when {
        code <= 0 || code > 0x10FFFF -> null
        code < 0x10000 -> code.toChar().toString()
        else -> {
            val offset = code - 0x10000
            charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
        }
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»", "hellip" to "…", "copy" to "©",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "times" to "×", "deg" to "°",
    )
}
