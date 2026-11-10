package ai.prism.domain.repository

import ai.prism.domain.agent.AgentStep
import ai.prism.domain.documents.MessageSource
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

    /** Удаляет диалог вместе с файлами его изображений и приложенными документами. */
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

    /** Папка проекта диалога (адрес для [WorkspaceProvider][ai.prism.domain.agent.WorkspaceProvider]) или `null`. */
    fun workspace(chatId: String): Flow<String?>

    suspend fun workspaceOf(chatId: String): String?

    suspend fun setWorkspace(chatId: String, location: String?)

    /** Запоминает шаги ассистента с инструментами для ответа. */
    suspend fun attachSteps(chatId: String, messageId: String, steps: List<AgentStep>)

    /** Запоминает, на какие фрагменты файлов опирался ответ. */
    suspend fun attachSources(chatId: String, messageId: String, sources: List<MessageSource>)

    suspend fun deleteMessage(messageId: String)

    /** Имена файлов всех изображений, на которые ссылаются сообщения. */
    suspend fun referencedImageFiles(): Set<String>

    /** Ответы, которые генерировались в момент закрытия приложения, помечаются прерванными. */
    suspend fun markInterruptedGenerations()
}
