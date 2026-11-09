package ai.prism.service

import ai.prism.domain.inference.InferenceError
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.testing.FakeInferenceEngine
import ai.prism.testing.FakeModelRepository
import ai.prism.testing.TestApp
import ai.prism.testing.testModel
import ai.prism.testing.testVision
import kotlinx.coroutines.flow.first
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

    private fun visionApp(visionDownloaded: Boolean): TestApp {
        val model = testModel(vision = testVision())
        val status = if (visionDownloaded) ModelStatus.Downloaded else ModelStatus.Paused(model.sizeBytes, model.totalSizeBytes, null)
        return TestApp(models = FakeModelRepository(listOf(ModelEntry(model, status, fitsInMemory = true, chatReady = true)), model.id))
    }

    @Test
    fun imagesReachTheEngineWithVisionModule() = runBlocking {
        val app = visionApp(visionDownloaded = true)
        val chat = app.chats.createChat()
        val photo = app.images.save(byteArrayOf(1, 2, 3))

        assertTrue(app.generation.send(chat.id, "Что на фото?", listOf(photo)))

        val stored = app.chats.messagesSnapshot(chat.id)
        assertEquals(listOf(photo), stored.first().images)
        assertEquals(MessageStatus.COMPLETE, stored.last().status)
        assertEquals("/models/mmproj-test-model.gguf", app.engine.lastProjectorPath)
        val prompt = app.engine.lastRequest?.messages?.single()
        assertEquals(listOf(photo.id), prompt?.images?.map { it.id })
        assertEquals("/images/${photo.fileName}", prompt?.images?.single()?.path)
    }

    @Test
    fun imageOnlyMessageIsSentWithoutTitle() = runBlocking {
        val app = visionApp(visionDownloaded = true)
        val chat = app.chats.createChat()
        val photo = app.images.save(byteArrayOf(1))

        assertTrue(app.generation.send(chat.id, "  ", listOf(photo)))

        assertEquals("", app.chats.messagesSnapshot(chat.id).first().content)
        assertNull(app.chats.chats.first().single { it.id == chat.id }.title)
        assertEquals(1, app.engine.lastRequest?.messages?.single()?.images?.size)
    }

    @Test
    fun imagesWithoutVisionModuleAreReportedAndOldOnesDropped() = runBlocking {
        val app = visionApp(visionDownloaded = false)
        val chat = app.chats.createChat()
        val photo = app.images.save(byteArrayOf(1))

        app.generation.send(chat.id, "Что на фото?", listOf(photo))

        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.FAILED, reply.status)
        assertEquals(GenerationError.IMAGES_NOT_SUPPORTED, reply.error)

        // Следующий текстовый вопрос работает: старое изображение просто не передаётся модели.
        app.generation.send(chat.id, "Тогда просто привет")
        val request = checkNotNull(app.engine.lastRequest)
        assertTrue(request.messages.all { it.images.isEmpty() })
        assertEquals(listOf("Что на фото?", "Тогда просто привет"), request.messages.map { it.content })
        assertNull(app.engine.lastProjectorPath)
    }

    @Test
    fun deletingChatDeletesItsImageFiles() = runBlocking {
        val app = visionApp(visionDownloaded = true)
        val chat = app.chats.createChat()
        val photo = app.images.save(byteArrayOf(1))
        app.generation.send(chat.id, "Фото", listOf(photo))

        assertEquals(setOf(photo.fileName), app.chats.referencedImageFiles())
        app.chats.deleteChat(chat.id)

        assertEquals(listOf(photo.fileName), app.images.deleted)
        assertTrue(app.chats.referencedImageFiles().isEmpty())
    }
}
