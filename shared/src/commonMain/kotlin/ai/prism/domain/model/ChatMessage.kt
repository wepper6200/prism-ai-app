package ai.prism.domain.model

import kotlinx.serialization.Serializable

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

    /** В сообщении есть изображения, а активная модель их не понимает (или не скачан её модуль зрения). */
    IMAGES_NOT_SUPPORTED,

    /** Изображение не удалось прочитать или обработать моделью. */
    IMAGE_FAILED,

    /** Модуль зрения (mmproj) повреждён или не подходит к модели. */
    VISION_MODULE_CORRUPTED,

    /** Нет скачанной модели изображений для этой платформы. */
    NO_IMAGE_MODEL,

    /** Не удалось создать изображение. */
    IMAGE_GENERATION_FAILED,

    /** Для генерации изображений на Windows нужен драйвер видеокарты с поддержкой Vulkan. */
    GPU_DRIVER_MISSING,

    /** Запрос отклонён безопасным режимом. */
    BLOCKED_BY_SAFE_MODE,
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
    /** Изображения, приложенные к сообщению пользователя, по порядку. */
    val images: List<ImageAttachment> = emptyList(),
)

/** Изображение в сообщении: уменьшенный JPEG в каталоге изображений приложения (`ImageStore`). */
@Serializable
data class ImageAttachment(
    val id: String,
    val fileName: String,
    val width: Int,
    val height: Int,
)
