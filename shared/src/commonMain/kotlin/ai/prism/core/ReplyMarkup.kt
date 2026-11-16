package ai.prism.core

sealed interface ReplyBlock {
    data class Prose(val text: String) : ReplyBlock
    data class Code(val language: String, val text: String) : ReplyBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : ReplyBlock
}

/** Keeps unfinished fences readable while tokens are still arriving. Never interprets HTML. */
object ReplyMarkup {
    fun parse(text: String): List<ReplyBlock> {
        val lines = text.lines()
        val blocks = mutableListOf<ReplyBlock>()
        val prose = mutableListOf<String>()
        fun flush() {
            if (prose.isNotEmpty()) { blocks += ReplyBlock.Prose(prose.joinToString("\n").trimEnd()); prose.clear() }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                flush()
                val language = line.trim().removePrefix("```").trim()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) code += lines[i++]
                blocks += ReplyBlock.Code(language, code.joinToString("\n"))
                if (i < lines.size) i++
            } else if (i + 1 < lines.size && line.contains('|') && cells(lines[i + 1]).let { it.isNotEmpty() && it.all { cell -> Regex(":?-{3,}:?").matches(cell) } }) {
                flush()
                val header = cells(line)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += cells(lines[i++]).let { row -> List(header.size) { row.getOrElse(it) { "" } } }
                }
                blocks += ReplyBlock.Table(header, rows)
            } else { prose += line; i++ }
        }
        flush()
        return blocks
    }

    private fun cells(line: String): List<String> {
        val result = mutableListOf<String>()
        val cell = StringBuilder()
        var escaped = false
        for (c in line.trim().removePrefix("|").removeSuffix("|")) {
            when {
                escaped -> { cell.append(c); escaped = false }
                c == '\\' -> escaped = true
                c == '|' -> { result += cell.toString().trim(); cell.clear() }
                else -> cell.append(c)
            }
        }
        if (escaped) cell.append('\\')
        result += cell.toString().trim()
        return result
    }
}
