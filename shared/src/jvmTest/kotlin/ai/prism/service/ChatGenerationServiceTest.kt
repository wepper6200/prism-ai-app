package ai.prism.service

import ai.prism.domain.inference.InferenceError
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.testing.FakeInferenceEngine
import ai.prism.testing.FakeModelRepository
import ai.prism.testing.TestApp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatGenerationServiceTest {

    @Test
    fun replyIsStoredWithSystemPromptAndHistory() = runBlocking {
        val app = TestApp()
        val chat = app.chats.createChat()

        assertTrue(app.generation.send(chat.id, "  Кто ты?  "))

        val messages = app.chats.messagesSnapshot(chat.id)
        assertEquals(listOf("Кто ты?", "Привет! Я Prism AI."), messages.map { it.content })
        assertEquals(MessageStatus.COMPLETE, messages.last().status)
        assertEquals("Ты — Prism AI.", app.engine.lastRequest?.systemPrompt)
        assertEquals("Я Prism AI.", app.engine.lastRequest?.primer?.last()?.content)
        assertEquals(listOf(MessageRole.USER), app.engine.lastRequest?.messages?.map { it.role })
        assertNull(app.generation.active.value)
    }

    @Test
    fun blankMessageIsIgnored() = runBlocking {
        val app = TestApp()
        val chat = app.chats.createChat()
        assertFalse(app.generation.send(chat.id, "   "))
        assertTrue(app.chats.messagesSnapshot(chat.id).isEmpty())
    }

    @Test
    fun missingModelIsReportedInMessage() = runBlocking {
        val app = TestApp(models = FakeModelRepository(entries = emptyList()))
        val chat = app.chats.createChat()

        app.generation.send(chat.id, "Привет")

        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.FAILED, reply.status)
        assertEquals(GenerationError.NO_MODEL, reply.error)
    }

    @Test
    fun engineErrorsAreMappedAndRetryReplacesFailedReply() = runBlocking {
        val engine = FakeInferenceEngine()
        engine.loadError = InferenceException(InferenceError.NOT_ENOUGH_MEMORY, "мало памяти")
        val app = TestApp(engine = engine)
        val chat = app.chats.createChat()

        app.generation.send(chat.id, "Привет")
        val failed = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(GenerationError.NOT_ENOUGH_MEMORY, failed.error)

        engine.loadError = null
        assertTrue(app.generation.retry(chat.id))

        val messages = app.chats.messagesSnapshot(chat.id)
        assertEquals(2, messages.size)
        assertEquals(MessageStatus.COMPLETE, messages.last().status)
    }

    @Test
    fun partialAnswerIsKeptOnContextOverflow() = runBlocking {
        val engine = FakeInferenceEngine(reply = listOf("Начало ответа"))
        engine.generateError = InferenceException(InferenceError.CONTEXT_FULL, "мало места")
        val app = TestApp(engine = engine)
        val chat = app.chats.createChat()

        app.generation.send(chat.id, "Расскажи длинную историю")

        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals("Начало ответа", reply.content)
        assertEquals(GenerationError.CONTEXT_FULL, reply.error)
    }

    @Test
    fun titleIsFirstLineTrimmed() {
        assertEquals("Как настроить VPN дома", ChatGenerationService.titleFrom("\n  Как   настроить VPN дома\nподробно"))
        val long = "а".repeat(80)
        assertEquals("а".repeat(48) + "…", ChatGenerationService.titleFrom(long))
    }
}
