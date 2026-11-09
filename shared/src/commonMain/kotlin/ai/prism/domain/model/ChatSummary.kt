package ai.prism.domain.model

/**
 * Краткие сведения о диалоге для списка чатов.
 *
 * @property title заголовок (начало первого сообщения); `null` — заголовка ещё нет,
 * интерфейс показывает локализованное «Новый чат».
 */
data class ChatSummary(
    val id: String,
    val title: String?,
    val updatedAt: Long,
)
