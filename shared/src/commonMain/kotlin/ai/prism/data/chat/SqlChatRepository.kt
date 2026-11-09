package ai.prism.data.chat

import ai.prism.core.currentTimeMillis
import ai.prism.core.newId
import ai.prism.data.db.Message
import ai.prism.data.db.PrismDatabase
import ai.prism.domain.model.ChatMessage
import ai.prism.domain.model.ChatSummary
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.repository.ChatRepository
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Диалоги и сообщения в SQLite (SQLDelight). */
class SqlChatRepository(
    private val database: PrismDatabase,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : ChatRepository {

    private val queries get() = database.chatQueries

    override val chats: Flow<List<ChatSummary>> = queries.selectChats()
        .asFlow()
        .mapToList(ioContext)
        .map { rows -> rows.map { ChatSummary(id = it.id, title = it.title, updatedAt = it.updated_at) } }

    override fun messages(chatId: String): Flow<List<ChatMessage>> = queries.selectMessages(chatId)
        .asFlow()
        .mapToList(ioContext)
        .map { rows -> rows.map { it.toDomain() } }

    override suspend fun messagesSnapshot(chatId: String): List<ChatMessage> = withContext(ioContext) {
        queries.selectMessages(chatId).executeAsList().map { it.toDomain() }
    }

    override suspend fun createChat(): ChatSummary = withContext(ioContext) {
        val now = currentTimeMillis()
        val chat = ChatSummary(id = newId(), title = null, updatedAt = now)
        queries.insertChat(id = chat.id, title = null, created_at = now, updated_at = now)
        chat
    }

    override suspend fun deleteChat(chatId: String) = withContext(ioContext) {
        queries.transaction {
            queries.deleteMessagesOfChat(chatId)
            queries.deleteChat(chatId)
        }
    }

    override suspend fun setTitleIfEmpty(chatId: String, title: String) = withContext(ioContext) {
        queries.setTitleIfEmpty(title = title, id = chatId)
        Unit
    }

    override suspend fun addMessage(
        chatId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus,
        modelId: String?,
    ): ChatMessage = withContext(ioContext) {
        val message = ChatMessage(
            id = newId(),
            chatId = chatId,
            role = role,
            content = content,
            status = status,
            error = null,
            modelId = modelId,
            createdAt = currentTimeMillis(),
        )
        queries.transaction {
            queries.insertMessage(
                id = message.id,
                chat_id = chatId,
                role = role.name,
                content = content,
                status = status.name,
                error = null,
                model_id = modelId,
                created_at = message.createdAt,
            )
            queries.touchChat(updated_at = message.createdAt, id = chatId)
        }
        message
    }

    override suspend fun updateMessage(
        messageId: String,
        content: String,
        status: MessageStatus,
        error: GenerationError?,
    ) = withContext(ioContext) {
        queries.updateMessage(content = content, status = status.name, error = error?.name, id = messageId)
        Unit
    }

    override suspend fun deleteMessage(messageId: String) = withContext(ioContext) {
        queries.deleteMessage(messageId)
        Unit
    }

    override suspend fun markInterruptedGenerations() = withContext(ioContext) {
        queries.markInterrupted()
        Unit
    }

    private fun Message.toDomain() = ChatMessage(
        id = id,
        chatId = chat_id,
        role = MessageRole.entries.firstOrNull { it.name == role } ?: MessageRole.ASSISTANT,
        content = content,
        status = MessageStatus.entries.firstOrNull { it.name == status } ?: MessageStatus.COMPLETE,
        error = GenerationError.entries.firstOrNull { it.name == error },
        modelId = model_id,
        createdAt = created_at,
    )
}
