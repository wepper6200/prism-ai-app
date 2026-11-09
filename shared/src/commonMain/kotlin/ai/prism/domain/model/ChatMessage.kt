package ai.prism.domain.model

enum class MessageRole { USER, ASSISTANT }

enum class MessageStatus {
    /** Ответ полностью получен (или сообщение пользователя). */
    COMPLETE,

    /** Ответ генерируется прямо сейчас. */
    GENERATING,

    /** Генерация остановлена пользователем — текст неполный. */
    STOPPED,

    /** Ответ не получен из-за ошибки — см. [ChatMessage.error]. */
    FAILED,
}

/** Причина, по которой ответ не получен. Хранится в БД по имени. */
enum class GenerationError {
    NO_MODEL,
    MODEL_NOT_DOWNLOADED,
    NOT_ENOUGH_MEMORY,
    MODEL_CORRUPTED,
    NATIVE_LIBRARY_MISSING,
    CONTEXT_FULL,
    INTERRUPTED,
    UNKNOWN,
}

data class ChatMessage(
    val id: String,
    val chatId: String,
    val role: MessageRole,
    val content: String,
    val status: MessageStatus,
    val error: GenerationError?,
    val modelId: String?,
    val createdAt: Long,
)
