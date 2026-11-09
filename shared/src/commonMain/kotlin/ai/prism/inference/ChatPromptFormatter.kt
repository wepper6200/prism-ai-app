package ai.prism.inference

import ai.prism.domain.inference.PromptMessage
import ai.prism.domain.model.ChatFormat
import ai.prism.domain.model.MessageRole

/**
 * Превращает диалог в текст промпта в формате, на котором обучена модель.
 * BOS-токен добавляет токенизатор llama.cpp.
 */
object ChatPromptFormatter {

    /** Место изображения в промпте; llama.cpp (libmtmd) заменяет его токенами изображения. */
    const val MEDIA_MARKER = "<__media__>"

    /** Служебные метки диалога, которые нельзя пропускать из текста пользователя. */
    private val controlMarkers = listOf(
        "<|turn>", "<turn|>", "<|channel>", "<channel|>", "<|think|>",
        "<start_of_turn>", "<end_of_turn>", "<bos>", "<eos>",
        "<start_of_image>", "<end_of_image>", "<|image>", "<image|>", MEDIA_MARKER,
    )

    fun format(format: ChatFormat, systemPrompt: String, messages: List<PromptMessage>): String = when (format) {
        ChatFormat.GEMMA4 -> gemma4(systemPrompt, messages)
        ChatFormat.GEMMA3 -> gemma3(systemPrompt, messages)
        ChatFormat.RAW -> raw(systemPrompt, messages)
    }

    /**
     * Дописывает [reminder] к последнему сообщению пользователя: маленькие модели сильнее всего
     * учитывают то, что стоит ближе к концу промпта. В истории чата напоминание не хранится.
     */
    fun withReminder(messages: List<PromptMessage>, reminder: String): List<PromptMessage> {
        val index = messages.indexOfLast { it.role == MessageRole.USER }
        if (reminder.isBlank() || index < 0) return messages
        return messages.toMutableList().also {
            it[index] = it[index].copy(content = it[index].content.trimEnd() + "\n\n" + reminder.trim())
        }
    }

    internal fun sanitize(text: String): String =
        controlMarkers.fold(text) { acc, marker -> acc.replace(marker, "") }.trim()

    // Gemma 4: <|turn>роль\n…<turn|>\n, системная роль поддерживается, «размышления» отключены пустым каналом.
    private fun gemma4(systemPrompt: String, messages: List<PromptMessage>): String = buildString {
        val system = sanitize(systemPrompt)
        if (system.isNotEmpty()) {
            append("<|turn>system\n").append(system).append("<turn|>\n")
        }
        messages.forEach { message ->
            append("<|turn>").append(message.role.gemmaRole()).append('\n')
            append(mediaMarkers(message)).append(sanitize(message.content)).append("<turn|>\n")
        }
        append("<|turn>model\n<|channel>thought\n<channel|>")
    }

    // Gemma 3: системной роли нет — системный промпт добавляется в начало первой реплики пользователя.
    private fun gemma3(systemPrompt: String, messages: List<PromptMessage>): String = buildString {
        val system = sanitize(systemPrompt)
        var systemPending = system.isNotEmpty()
        messages.forEach { message ->
            append("<start_of_turn>").append(message.role.gemmaRole()).append('\n')
            if (systemPending && message.role == MessageRole.USER) {
                append(system).append("\n\n")
                systemPending = false
            }
            append(mediaMarkers(message)).append(sanitize(message.content)).append("<end_of_turn>\n")
        }
        append("<start_of_turn>model\n")
    }

    private fun raw(systemPrompt: String, messages: List<PromptMessage>): String = buildString {
        if (systemPrompt.isNotBlank()) append(systemPrompt.trim()).append("\n\n")
        messages.forEach { append(mediaMarkers(it)).append(it.content.trim()).append('\n') }
    }

    /** Метки изображений сообщения — перед его текстом, каждая с новой строки. */
    private fun mediaMarkers(message: PromptMessage): String =
        message.images.joinToString("") { MEDIA_MARKER + "\n" }

    private fun MessageRole.gemmaRole(): String = when (this) {
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "model"
    }
}
