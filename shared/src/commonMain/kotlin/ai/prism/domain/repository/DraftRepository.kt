package ai.prism.domain.repository

import ai.prism.domain.model.ImageAttachment
import kotlinx.serialization.Serializable

/** Неотправленное сообщение диалога: текст и приложенные фото. */
@Serializable
data class ChatDraft(
    val text: String = "",
    val images: List<ImageAttachment> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isBlank() && images.isEmpty()
}

/**
 * Черновики по диалогам: остаются, когда пользователь уходит из диалога или закрывает приложение.
 * Файлы (PDF, DOCX…) прикладываются к диалогу сразу и хранятся в его документах, а не здесь.
 */
interface DraftRepository {
    fun draft(chatId: String): ChatDraft

    /** Сохраняет черновик; пустой удаляется. */
    fun save(chatId: String, draft: ChatDraft)

    /** Фото всех черновиков: их нельзя удалять как брошенные. */
    fun referencedImageFiles(): Set<String>

    /** Удаляет черновики диалогов, которых больше нет. */
    fun retainChats(chatIds: Set<String>)

    object None : DraftRepository {
        override fun draft(chatId: String) = ChatDraft()
        override fun save(chatId: String, draft: ChatDraft) = Unit
        override fun referencedImageFiles(): Set<String> = emptySet()
        override fun retainChats(chatIds: Set<String>) = Unit
    }
}
