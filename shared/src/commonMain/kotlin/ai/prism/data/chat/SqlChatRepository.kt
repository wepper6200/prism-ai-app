package ai.prism.data.chat

import ai.prism.core.currentTimeMillis
import ai.prism.core.newId
import ai.prism.data.db.Message
import ai.prism.data.db.Message_image
import ai.prism.data.db.Message_source
import ai.prism.data.db.Message_step
import ai.prism.data.db.PrismDatabase
import ai.prism.domain.agent.AgentStep
import ai.prism.domain.agent.AgentStepStatus
import ai.prism.domain.agent.AgentTool
import ai.prism.domain.documents.MessageSource
import ai.prism.domain.model.ChatDigest
import ai.prism.domain.model.ChatMessage
import ai.prism.domain.model.ChatSummary
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.ImageAttachment
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.repository.ImageStore
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Диалоги и сообщения в SQLite (SQLDelight). */
class SqlChatRepository(
    private val database: PrismDatabase,
    private val imageStore: ImageStore,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : ChatRepository {

    private val queries get() = database.chatQueries
    private val imageQueries get() = database.messageImageQueries
    private val documentQueries get() = database.documentQueries
    private val stepQueries get() = database.agentStepQueries

    override val chats: Flow<List<ChatSummary>> = queries.selectChats()
        .asFlow()
        .mapToList(ioContext)
        .map { rows -> rows.map { ChatSummary(id = it.id, title = it.title, updatedAt = it.updated_at) } }

    override fun messages(chatId: String): Flow<List<ChatMessage>> = combine(
        queries.selectMessages(chatId).asFlow().mapToList(ioContext),
        imageQueries.selectImagesOfChat(chatId).asFlow().mapToList(ioContext),
        documentQueries.selectSourcesOfChat(chatId).asFlow().mapToList(ioContext),
        stepQueries.selectStepsOfChat(chatId).asFlow().mapToList(ioContext),
    ) { rows, images, sources, steps -> toDomain(rows, images, sources, steps) }

    override suspend fun messagesSnapshot(chatId: String): List<ChatMessage> = withContext(ioContext) {
        toDomain(
            queries.selectMessages(chatId).executeAsList(),
            imageQueries.selectImagesOfChat(chatId).executeAsList(),
            documentQueries.selectSourcesOfChat(chatId).executeAsList(),
            stepQueries.selectStepsOfChat(chatId).executeAsList(),
        )
    }

    override fun workspace(chatId: String): Flow<String?> =
        queries.selectWorkspace(chatId).asFlow().mapToList(ioContext).map { rows -> rows.firstOrNull()?.workspace }

    override suspend fun workspaceOf(chatId: String): String? = withContext(ioContext) {
        queries.selectWorkspace(chatId).executeAsOneOrNull()?.workspace
    }

    override suspend fun setWorkspace(chatId: String, location: String?) = withContext(ioContext) {
        queries.setWorkspace(workspace = location, id = chatId)
        Unit
    }

    override suspend fun summaryOf(chatId: String): ChatDigest? = withContext(ioContext) {
        val row = queries.selectSummary(chatId).executeAsOneOrNull() ?: return@withContext null
        val text = row.summary ?: return@withContext null
        val until = row.summary_until ?: return@withContext null
        ChatDigest(text, until)
    }

    override suspend fun setSummary(chatId: String, digest: ChatDigest?) = withContext(ioContext) {
        queries.setSummary(summary = digest?.text, summary_until = digest?.untilMessageId, id = chatId)
        Unit
    }

    override suspend fun attachSteps(chatId: String, messageId: String, steps: List<AgentStep>) = withContext(ioContext) {
        queries.transaction {
            stepQueries.deleteStepsOfMessage(messageId)
            steps.forEachIndexed { index, step ->
                stepQueries.insertStep(
                    message_id = messageId,
                    chat_id = chatId,
                    position = index.toLong(),
                    tool = step.tool.name,
                    title = step.title,
                    detail = step.detail,
                    status = step.status.name,
                )
            }
        }
    }

    override suspend fun createChat(): ChatSummary = withContext(ioContext) {
        val now = currentTimeMillis()
        val chat = ChatSummary(id = newId(), title = null, updatedAt = now)
        queries.insertChat(id = chat.id, title = null, created_at = now, updated_at = now)
        chat
    }

    override suspend fun deleteChat(chatId: String) {
        val files = withContext(ioContext) {
            queries.transactionWithResult {
                val files = imageQueries.selectFilesOfChat(chatId).executeAsList()
                imageQueries.deleteImagesOfChat(chatId)
                documentQueries.deleteSourcesOfChat(chatId)
                stepQueries.deleteStepsOfChat(chatId)
                documentQueries.deleteChunksOfChat(chatId)
                documentQueries.deleteDocumentsOfChat(chatId)
                queries.deleteMessagesOfChat(chatId)
                queries.deleteChat(chatId)
                files
            }
        }
        if (files.isNotEmpty()) imageStore.delete(files)
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
        images: List<ImageAttachment>,
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
            images = images,
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
            images.forEachIndexed { index, image ->
                imageQueries.insertImage(
                    id = image.id,
                    message_id = message.id,
                    chat_id = chatId,
                    file_name = image.fileName,
                    width = image.width.toLong(),
                    height = image.height.toLong(),
                    position = index.toLong(),
                )
            }
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

    override suspend fun attachImages(chatId: String, messageId: String, images: List<ImageAttachment>) = withContext(ioContext) {
        queries.transaction {
            images.forEachIndexed { index, image ->
                imageQueries.insertImage(
                    id = image.id,
                    message_id = messageId,
                    chat_id = chatId,
                    file_name = image.fileName,
                    width = image.width.toLong(),
                    height = image.height.toLong(),
                    position = index.toLong(),
                )
            }
        }
    }

    override suspend fun attachSources(chatId: String, messageId: String, sources: List<MessageSource>) =
        withContext(ioContext) {
            queries.transaction {
                documentQueries.deleteSourcesOfMessage(messageId)
                sources.forEachIndexed { index, source ->
                    documentQueries.insertSource(
                        message_id = messageId,
                        chat_id = chatId,
                        position = index.toLong(),
                        document_name = source.documentName,
                        page = source.page?.toLong(),
                    )
                }
            }
        }

    override suspend fun deleteMessage(messageId: String) = withContext(ioContext) {
        queries.transaction {
            imageQueries.deleteImagesOfMessage(messageId)
            documentQueries.deleteSourcesOfMessage(messageId)
            stepQueries.deleteStepsOfMessage(messageId)
            queries.deleteMessage(messageId)
        }
    }

    override suspend fun referencedImageFiles(): Set<String> = withContext(ioContext) {
        imageQueries.selectAllFiles().executeAsList().toSet()
    }

    override suspend fun markInterruptedGenerations() = withContext(ioContext) {
        queries.markInterrupted()
        Unit
    }

    private fun toDomain(
        rows: List<Message>,
        images: List<Message_image>,
        sources: List<Message_source>,
        steps: List<Message_step>,
    ): List<ChatMessage> {
        val byMessage = images.groupBy { it.message_id }
        val sourcesByMessage = sources.groupBy { it.message_id }
        val stepsByMessage = steps.groupBy { it.message_id }
        return rows.map { row ->
            row.toDomain(
                images = byMessage[row.id].orEmpty()
                    .sortedBy { it.position }
                    .map { ImageAttachment(it.id, it.file_name, it.width.toInt(), it.height.toInt()) },
                sources = sourcesByMessage[row.id].orEmpty()
                    .sortedBy { it.position }
                    .map { MessageSource(it.document_name, it.page?.toInt()) },
                steps = stepsByMessage[row.id].orEmpty()
                    .sortedBy { it.position }
                    .mapNotNull { step ->
                        val tool = AgentTool.entries.firstOrNull { it.name == step.tool } ?: return@mapNotNull null
                        AgentStep(
                            tool = tool,
                            title = step.title,
                            detail = step.detail,
                            status = AgentStepStatus.entries.firstOrNull { it.name == step.status } ?: AgentStepStatus.DONE,
                        )
                    },
            )
        }
    }

    private fun Message.toDomain(images: List<ImageAttachment>, sources: List<MessageSource>, steps: List<AgentStep>) = ChatMessage(
        id = id,
        chatId = chat_id,
        role = MessageRole.entries.firstOrNull { it.name == role } ?: MessageRole.ASSISTANT,
        content = content,
        status = MessageStatus.entries.firstOrNull { it.name == status } ?: MessageStatus.COMPLETE,
        error = GenerationError.entries.firstOrNull { it.name == error },
        modelId = model_id,
        createdAt = created_at,
        images = images,
        sources = sources,
        steps = steps,
    )
}
