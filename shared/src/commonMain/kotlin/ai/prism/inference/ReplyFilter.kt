package ai.prism.inference

import ai.prism.domain.model.ChatFormat

/**
 * Потоковая обработка ответа модели перед показом: получает фрагменты по мере генерации,
 * возвращает то, что можно показать пользователю.
 */
interface ReplyFilter {
    fun push(piece: String): String

    /** Остаток после окончания генерации. */
    fun finish(): String

    companion object {
        fun forFormat(format: ChatFormat): ReplyFilter = when (format) {
            ChatFormat.GEMMA4 -> Gemma4ReplyFilter()
            ChatFormat.GEMMA3, ChatFormat.RAW -> PassThrough
        }
    }

    private object PassThrough : ReplyFilter {
        override fun push(piece: String): String = piece

        override fun finish(): String = ""
    }
}

/**
 * Gemma 4 сначала «размышляет», даже когда в промпте канал размышлений закрыт пустым:
 * пишет рассуждение и `<channel|>`, затем ответ. Иногда рассуждение обрамлено `<|channel>thought … <channel|>`.
 * Фильтр скрывает рассуждения (пока они идут, интерфейс показывает «думает…»), служебные теги и пустые каналы.
 * Если `<channel|>` так и не появился, накопленный текст считается ответом: он показывается целиком
 * по окончании генерации или сразу, если превысил [flushLimit] символов.
 */
class Gemma4ReplyFilter(private val flushLimit: Int = 4000) : ReplyFilter {

    private enum class Mode { PREAMBLE, THOUGHT, ANSWER }

    private var mode = Mode.PREAMBLE
    private val buffer = StringBuilder()

    override fun push(piece: String): String {
        buffer.append(piece)
        val out = StringBuilder()
        while (true) {
            when (mode) {
                Mode.PREAMBLE -> {
                    val close = buffer.indexOf(CLOSE)
                    val open = buffer.indexOf(OPEN)
                    when {
                        open == 0 -> {
                            buffer.deleteRange(0, OPEN.length)
                            mode = Mode.THOUGHT
                        }
                        close >= 0 -> {
                            buffer.deleteRange(0, close + CLOSE.length)
                            mode = Mode.ANSWER
                        }
                        buffer.length > flushLimit -> mode = Mode.ANSWER
                        else -> return out.toString()
                    }
                }
                Mode.THOUGHT -> {
                    val close = buffer.indexOf(CLOSE)
                    if (close < 0) {
                        // Оставляем хвост, который может оказаться началом тега.
                        val keep = partialTagLength(buffer)
                        buffer.deleteRange(0, buffer.length - keep)
                        return out.toString()
                    }
                    buffer.deleteRange(0, close + CLOSE.length)
                    mode = Mode.ANSWER
                }
                Mode.ANSWER -> {
                    val open = buffer.indexOf(OPEN)
                    val close = buffer.indexOf(CLOSE)
                    val tag = listOf(open, close).filter { it >= 0 }.minOrNull()
                    if (tag == null) {
                        val keep = partialTagLength(buffer)
                        out.appendRange(buffer, 0, buffer.length - keep)
                        buffer.deleteRange(0, buffer.length - keep)
                        return out.toString()
                    }
                    out.appendRange(buffer, 0, tag)
                    if (tag == open) {
                        buffer.deleteRange(0, tag + OPEN.length)
                        mode = Mode.THOUGHT
                    } else {
                        buffer.deleteRange(0, tag + CLOSE.length)
                    }
                }
            }
        }
    }

    override fun finish(): String {
        val rest = when (mode) {
            Mode.PREAMBLE, Mode.ANSWER -> buffer.toString()
            Mode.THOUGHT -> ""
        }
        buffer.clear()
        return rest
    }

    /** Длина конца [text], который совпадает с началом одного из тегов. */
    private fun partialTagLength(text: CharSequence): Int {
        val max = minOf(text.length, maxOf(OPEN.length, CLOSE.length) - 1)
        for (length in max downTo 1) {
            val tail = text.subSequence(text.length - length, text.length).toString()
            if (OPEN.startsWith(tail) || CLOSE.startsWith(tail)) return length
        }
        return 0
    }

    private companion object {
        const val OPEN = "<|channel>"
        const val CLOSE = "<channel|>"
    }
}
