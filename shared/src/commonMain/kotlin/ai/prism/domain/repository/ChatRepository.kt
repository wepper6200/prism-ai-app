package ai.prism.domain.repository

import ai.prism.domain.model.ChatMessage
import ai.prism.domain.model.ChatSummary
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.ImageAttachment
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    /** Диалоги, недавние — первыми. */
    val chats: Flow<List<ChatSummary>>

    fun messages(chatId: String): Flow<List<ChatMessage>>

    suspend fun messagesSnapshot(chatId: String): List<ChatMessage>

    suspend fun createChat(): ChatSummary

    /** Удаляет диалог вместе с файлами его изображений. */
    suspend fun deleteChat(chatId: String)

    /** Ставит заголовок, если у диалога его ещё нет. */
    suspend fun setTitleIfEmpty(chatId: String, title: String)

    suspend fun addMessage(
        chatId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus,
        modelId: String?,
        images: List<ImageAttachment> = emptyList(),
    ): ChatMessage

    suspend fun updateMessage(messageId: String, content: String, status: MessageStatus, error: GenerationError?)

    /** Добавляет изображения к сообщению (созданные моделью изображений). */
    suspend fun attachImages(chatId: String, messageId: String, images: List<ImageAttachment>)

    suspend fun deleteMessage(messageId: String)

    /** Имена файлов всех изображений, на которые ссылаются сообщения. */
    suspend fun referencedImageFiles(): Set<String>

    /** Ответы, которые генерировались в момент закрытия приложения, помечаются прерванными. */
    suspend fun markInterruptedGenerations()
}
