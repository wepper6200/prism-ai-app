package ai.prism.service

import ai.prism.domain.documents.DocumentChunk
import ai.prism.domain.documents.DocumentFormat
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.testing.TestApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlin.test.*

class ChatBranchServiceTest {
    @Test
    fun historyIsCutAtSelectedMessageAndAttachmentsAndDocumentsAreIndependent(): Unit = runBlocking {
        val app = TestApp()
        val chat = app.chats.createChat()
        app.chats.setWorkspace(chat.id, "project-folder")
        val doc = app.documentRepository.add(chat.id, "guide.txt", DocumentFormat.TEXT, 12)
        app.documentRepository.saveChunks(doc.id, "embedding", listOf(DocumentChunk(doc.id, doc.name, 0, null, "project text", floatArrayOf(1f, 0f))))
        val photo = app.images.save(byteArrayOf(1, 2, 3))
        val first = app.chats.addMessage(chat.id, MessageRole.USER, "first", MessageStatus.COMPLETE, null, listOf(photo))
        app.chats.addMessage(chat.id, MessageRole.ASSISTANT, "future answer", MessageStatus.COMPLETE, "model")
        val fork = ChatBranchService(app.chats, app.images, app.documentRepository).fork(chat.id, first.id, "branch")
        val messages = app.chats.messagesSnapshot(fork)
        assertEquals(listOf("first"), messages.map { it.content })
        assertEquals("project-folder", app.chats.workspaceOf(fork))
        assertNotEquals(photo.fileName, messages.single().images.single().fileName)
        assertNotEquals(photo.id, messages.single().images.single().id)
        assertEquals(listOf("project text"), app.documentRepository.chunks(fork, "embedding").map { it.text })
        app.chats.deleteChat(chat.id)
        assertNotNull(app.images.read(messages.single().images.single().fileName))
        assertEquals("project text", app.documentRepository.chunks(fork, "embedding").single().text)
    }

    @Test
    fun invalidBranchDoesNotLeaveAnEmptyChat(): Unit = runBlocking {
        val app = TestApp()
        val chat = app.chats.createChat()
        assertFailsWith<IllegalArgumentException> { ChatBranchService(app.chats, app.images).fork(chat.id, "missing", "branch") }
        assertEquals(1, app.chats.chats.first().size)
    }
}
