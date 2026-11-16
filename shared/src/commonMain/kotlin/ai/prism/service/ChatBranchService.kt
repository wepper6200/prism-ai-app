package ai.prism.service

import ai.prism.domain.model.MessageStatus
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.repository.DocumentRepository
import ai.prism.domain.repository.ImageStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Independent attachment copies: deleting either chat cannot break the other. */
class ChatBranchService(private val chats: ChatRepository, private val images: ImageStore,
    private val documents: DocumentRepository? = null) {
    suspend fun fork(chatId: String, throughMessageId: String, title: String): String {
        val history = chats.messagesSnapshot(chatId)
        val end = history.indexOfFirst { it.id == throughMessageId }
        require(end >= 0 && history.take(end + 1).none { it.status == MessageStatus.GENERATING })
        val created = chats.createChat()
        val files = mutableListOf<String>()
        try {
            chats.setTitle(created.id, title.take(120))
            chats.setWorkspace(created.id, chats.workspaceOf(chatId))
            for (message in history.take(end + 1)) {
                val attachments = message.images.map { image ->
                    val bytes = images.read(image.fileName) ?: error("Attachment unavailable")
                    val copied = when {
                        image.isAudio -> images.saveAudio(bytes)
                        image.isVideo -> images.saveVideo(bytes, image.width, image.height)
                        else -> images.save(bytes)
                    }
                    files += copied.fileName
                    copied
                }
                val copied = chats.addMessage(created.id, message.role, message.content, message.status, message.modelId, attachments)
                if (message.error != null) chats.updateMessage(copied.id, copied.content, copied.status, message.error)
                // Tool history is context, not a new execution or a new undo journal.
                chats.attachSteps(created.id, copied.id, message.steps)
                chats.attachSources(created.id, copied.id, message.sources)
            }
            documents?.copyToChat(chatId, created.id, history[end].createdAt)
            return created.id
        } catch (failure: Throwable) {
            withContext(NonCancellable) { chats.deleteChat(created.id); images.delete(files) }
            throw failure
        }
    }
}
