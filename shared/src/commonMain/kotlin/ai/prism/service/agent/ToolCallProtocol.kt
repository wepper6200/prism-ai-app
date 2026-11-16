package ai.prism.service.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

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
 * Также поддерживается родной формат Qwen3-Coder (`<tool_call><function=…><parameter=…>…</parameter></function></tool_call>`).
 * Результат возвращается модели сообщением `<result name="…">…</result>` (или `<tool_response>…</tool_response>`).
 */
object ToolCallProtocol {
    const val OPEN = "<tool"
    const val CLOSE = "</tool>"
    private const val TOOL_CALL_OPEN = "<tool_call>"
    private const val TOOL_CALL_CLOSE = "</tool_call>"
    private const val FUNCTION_OPEN = "<function="
    private const val FUNCTION_CLOSE = "</function>"

    // Имена инструментов MCP-серверов бывают с цифрами, точками и дефисами.
    private val header = Regex("<tool\\s+name\\s*=\\s*[\"']([A-Za-z0-9_.-]+)[\"']\\s*>")
    private val functionHeader = Regex("<function\\s*=\\s*([A-Za-z0-9_.-]+)\\s*>")
    private val toolCallBlock = Regex("<tool_call>([\\s\\S]*?)(?:</tool_call>|$)")
    private val anyOpen = Regex("<tool(?:\\s|>|_call\\b)|<function\\s*=")
    private val argument = Regex("<([A-Za-z_][A-Za-z0-9_.-]*)>([\\s\\S]*?)</\\1>")
    private val parameterTag = Regex("<parameter(?:\\s+name\\s*=\\s*[\"']([A-Za-z_][A-Za-z0-9_.-]*)[\"']|\\s*=\\s*([A-Za-z_][A-Za-z0-9_.-]*))\\s*>([\\s\\S]*?)</parameter>")
    private val trailingFence = Regex("\\n?```[A-Za-z]*\\s*$")
    private val partialPrefixes = listOf("<tool ", "<tool>", "<tool_call>", "<function=")

    /** Модель дописала вызов до конца — генерацию можно остановить. */
    fun isComplete(text: String): Boolean {
        val toolMatch = header.find(text)
        if (toolMatch != null) return text.indexOf(CLOSE, toolMatch.range.last + 1) >= 0
        val callStart = text.indexOf(TOOL_CALL_OPEN)
        if (callStart >= 0) return text.indexOf(TOOL_CALL_CLOSE, callStart + TOOL_CALL_OPEN.length) >= 0
        val fnMatch = functionHeader.find(text)
        if (fnMatch != null) return text.indexOf(FUNCTION_CLOSE, fnMatch.range.last + 1) >= 0
        return false
    }

    /** Вызов инструмента или `null`, если в ответе его нет (это окончательный ответ пользователю). */
    fun parse(text: String): ToolCall? {
        header.find(text)?.let { match ->
            val end = text.indexOf(CLOSE, match.range.last + 1)
            val body = if (end >= 0) text.substring(match.range.last + 1, end) else text.substring(match.range.last + 1)
            return ToolCall(match.groupValues[1], parseXmlArgs(body))
        }
        functionHeader.find(text)?.let { match ->
            val fnEnd = text.indexOf(FUNCTION_CLOSE, match.range.last + 1)
            val callEnd = text.indexOf(TOOL_CALL_CLOSE, match.range.last + 1)
            val end = listOf(fnEnd, callEnd).filter { it >= 0 }.minOrNull() ?: text.length
            val body = text.substring(match.range.last + 1, end)
            return ToolCall(match.groupValues[1], parseXmlArgs(body))
        }
        toolCallBlock.find(text)?.let { match ->
            parseJsonToolCall(match.groupValues[1])?.let { return it }
        }
        return null
    }

    /** Текст ответа до вызова инструмента — его видит пользователь (и пока идёт генерация, и после). */
    fun visibleText(text: String): String {
        val start = anyOpen.find(text)?.range?.first ?: -1
        var visible = if (start >= 0) {
            text.substring(0, start)
        } else {
            // Начало тега могло ещё не дописаться — не показываем обрывок разметки.
            val maxPartial = partialPrefixes.maxOf { it.length - 1 }
            val partial = (minOf(text.length, maxPartial) downTo 1).firstOrNull { len ->
                val tail = text.takeLast(len)
                partialPrefixes.any { it.startsWith(tail) }
            } ?: 0
            text.dropLast(partial)
        }
        if (start >= 0) visible = visible.replace(trailingFence, "")
        return visible.trimEnd()
    }

    /** Текст вызова для истории промпта: всё до конца закрывающего тега включительно. */
    fun callText(text: String): String {
        header.find(text)?.let { match ->
            val end = text.indexOf(CLOSE, match.range.last + 1)
            return if (end >= 0) text.substring(0, end + CLOSE.length) else text
        }
        val callStart = text.indexOf(TOOL_CALL_OPEN)
        if (callStart >= 0) {
            val end = text.indexOf(TOOL_CALL_CLOSE, callStart + TOOL_CALL_OPEN.length)
            return if (end >= 0) text.substring(0, end + TOOL_CALL_CLOSE.length) else text
        }
        functionHeader.find(text)?.let { match ->
            val end = text.indexOf(FUNCTION_CLOSE, match.range.last + 1)
            return if (end >= 0) text.substring(0, end + FUNCTION_CLOSE.length) else text
        }
        return text
    }

    fun result(name: String, content: String, qwenCoderFormat: Boolean = false): String =
        if (qwenCoderFormat) {
            "<tool_response>\n${content.trim()}\n</tool_response>"
        } else {
            "<result name=\"$name\">\n${content.trim()}\n</result>"
        }

    private fun parseXmlArgs(body: String): Map<String, String> = buildMap {
        argument.findAll(body).forEach { match ->
            val key = match.groupValues[1]
            if (key != "parameter" && key != "function" && key != "tool_call") {
                put(key, trimValue(match.groupValues[2]))
            }
        }
        parameterTag.findAll(body).forEach { match ->
            val key = match.groupValues[1].ifEmpty { match.groupValues[2] }
            putIfAbsent(key, trimValue(match.groupValues[3]))
        }
    }

    private fun parseJsonToolCall(raw: String): ToolCall? = try {
        val obj = Json.parseToJsonElement(raw.trim()).jsonObject
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val argsObj = obj["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val args = argsObj.mapValues { (_, value) ->
            (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
        }
        ToolCall(name, args)
    } catch (_: Exception) {
        null
    }

    /** Значение аргумента без одного перевода строки в начале и в конце (для удобства записи кода). */
    private fun trimValue(raw: String): String = raw.removePrefix("\r").removePrefix("\n").removeSuffix("\n").removeSuffix("\r")
}
