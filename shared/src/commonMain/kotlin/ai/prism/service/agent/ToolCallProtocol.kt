package ai.prism.service.agent

/** Вызов инструмента из ответа модели: имя и аргументы (значения — сырой текст, без экранирования). */
data class ToolCall(val name: String, val args: Map<String, String>)

/**
 * Протокол вызова инструментов для локальных моделей — разметка, которую легко писать без экранирования кода:
 *
 * ```
 * <tool name="write_file">
 * <path>src/main.py</path>
 * <content>
 * print("Привет")
 * </content>
 * </tool>
 * ```
 *
 * Результат возвращается модели сообщением `<result name="…">…</result>`.
 */
object ToolCallProtocol {
    const val OPEN = "<tool"
    const val CLOSE = "</tool>"

    // Имена инструментов MCP-серверов бывают с цифрами, точками и дефисами.
    private val header = Regex("<tool\\s+name\\s*=\\s*[\"']([A-Za-z0-9_.-]+)[\"']\\s*>")
    private val argument = Regex("<([A-Za-z_][A-Za-z0-9_.-]*)>([\\s\\S]*?)</\\1>")
    private val trailingFence = Regex("\\n?```[A-Za-z]*\\s*$")

    /** Модель дописала вызов до конца — генерацию можно остановить. */
    fun isComplete(text: String): Boolean {
        val start = text.indexOf(OPEN)
        return start >= 0 && text.indexOf(CLOSE, start) >= 0
    }

    /** Вызов инструмента или `null`, если в ответе его нет (это окончательный ответ пользователю). */
    fun parse(text: String): ToolCall? {
        val match = header.find(text) ?: return null
        val end = text.indexOf(CLOSE, match.range.last + 1)
        val body = if (end >= 0) text.substring(match.range.last + 1, end) else text.substring(match.range.last + 1)
        val args = argument.findAll(body).associate { it.groupValues[1] to trimValue(it.groupValues[2]) }
        return ToolCall(match.groupValues[1], args)
    }

    /** Текст ответа до вызова инструмента — его видит пользователь (и пока идёт генерация, и после). */
    fun visibleText(text: String): String {
        val start = text.indexOf(OPEN)
        var visible = if (start >= 0) {
            text.substring(0, start)
        } else {
            // Начало «<tool» могло ещё не дописаться — не показываем обрывок разметки.
            val partial = (OPEN.length - 1 downTo 1).firstOrNull { text.endsWith(OPEN.take(it)) } ?: 0
            text.dropLast(partial)
        }
        if (start >= 0) visible = visible.replace(trailingFence, "")
        return visible.trimEnd()
    }

    /** Текст вызова для истории промпта: всё до конца `</tool>` включительно. */
    fun callText(text: String): String {
        val start = text.indexOf(OPEN)
        val end = if (start >= 0) text.indexOf(CLOSE, start) else -1
        return if (end >= 0) text.substring(0, end + CLOSE.length) else text
    }

    fun result(name: String, content: String): String = "<result name=\"$name\">\n${content.trim()}\n</result>"

    /** Значение аргумента без одного перевода строки в начале и в конце (для удобства записи кода). */
    private fun trimValue(raw: String): String = raw.removePrefix("\r").removePrefix("\n").removeSuffix("\n").removeSuffix("\r")
}
