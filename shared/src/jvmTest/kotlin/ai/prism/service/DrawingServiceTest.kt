package ai.prism.service

import ai.prism.domain.imagegen.ImageGenerationError
import ai.prism.domain.imagegen.ImageGenerationException
import ai.prism.domain.inference.EngineState
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelStatus
import ai.prism.testing.FakeImageGenerator
import ai.prism.testing.FakeInferenceEngine
import ai.prism.testing.FakeModelRepository
import ai.prism.testing.TestApp
import ai.prism.testing.testImageModel
import ai.prism.testing.testModel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrawingServiceTest {

    private fun app(
        imageDownloaded: Boolean = true,
        engine: FakeInferenceEngine = FakeInferenceEngine(reply = listOf("A ginger ", "cat on a sofa")),
        generator: FakeImageGenerator = FakeImageGenerator(),
    ) = TestApp(
        engine = engine,
        models = FakeModelRepository(
            listOf(
                ModelEntry(testModel(), ModelStatus.Downloaded, fitsInMemory = true),
                ModelEntry(
                    testImageModel(),
                    if (imageDownloaded) ModelStatus.Downloaded else ModelStatus.NotDownloaded,
                    fitsInMemory = true,
                ),
            ),
        ),
        imageGenerator = generator,
    )

    @Test
    fun russianPromptIsTranslatedAndPictureIsStored() = runBlocking {
        val app = app()
        val chat = app.chats.createChat()

        assertTrue(app.generation.draw(chat.id, "  Нарисуй рыжего кота на диване "))

        val messages = app.chats.messagesSnapshot(chat.id)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), messages.map { it.role })
        assertEquals("Нарисуй рыжего кота на диване", messages.first().content)
        val reply = messages.last()
        assertEquals(MessageStatus.COMPLETE, reply.status)
        assertEquals("flux-test", reply.modelId)
        val picture = reply.images.single()
        assertContentEquals(app.imageGenerator.png, app.images.files[picture.fileName])
        // Описание переведено моделью чата, а сама модель чата выгружена перед рисованием.
        assertEquals("Переведи на английский.", app.engine.lastRequest?.systemPrompt)
        assertEquals("A ginger cat on a sofa", app.imageGenerator.lastRequest?.prompt)
        assertIs<EngineState.Idle>(app.engine.state.value)
        assertEquals(
            mapOf("diffusion_model" to "/models/flux-test.gguf", "vae" to "/models/flux-test-ae.safetensors"),
            app.imageGenerator.lastFiles,
        )
        assertNull(app.generation.active.value)
    }

    @Test
    fun englishPromptIsUsedAsIs() = runBlocking {
        val app = app()
        val chat = app.chats.createChat()

        app.generation.draw(chat.id, "a red fox in the snow")

        assertNull(app.engine.lastRequest)
        assertEquals("a red fox in the snow", app.imageGenerator.lastRequest?.prompt)
        assertEquals(4, app.imageGenerator.lastRequest?.steps)
    }

    @Test
    fun missingImageModelIsReported() = runBlocking {
        val app = app(imageDownloaded = false)
        val chat = app.chats.createChat()

        app.generation.draw(chat.id, "кот")

        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.FAILED, reply.status)
        assertEquals(GenerationError.NO_IMAGE_MODEL, reply.error)
    }

    @Test
    fun failedPictureIsRedrawnOnRetry() = runBlocking {
        val generator = FakeImageGenerator().apply {
            error = ImageGenerationException(ImageGenerationError.GPU_DRIVER_MISSING, "нет драйвера")
        }
        val app = app(generator = generator)
        val chat = app.chats.createChat()

        app.generation.draw(chat.id, "a lighthouse")
        val failed = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(GenerationError.GPU_DRIVER_MISSING, failed.error)

        generator.error = null
        assertTrue(app.generation.retry(chat.id))

        val messages = app.chats.messagesSnapshot(chat.id)
        assertEquals(2, messages.size)
        assertEquals(MessageStatus.COMPLETE, messages.last().status)
        assertEquals(1, messages.last().images.size)
        assertEquals("a lighthouse", generator.lastRequest?.prompt)
    }

    @Test
    fun cancelledPictureIsStopped() = runBlocking {
        val generator = FakeImageGenerator().apply {
            error = ImageGenerationException(ImageGenerationError.CANCELLED, "остановлено")
        }
        val app = app(generator = generator)
        val chat = app.chats.createChat()

        app.generation.draw(chat.id, "a lighthouse")

        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.STOPPED, reply.status)
        assertTrue(reply.images.isEmpty())
    }

    @Test
    fun textReplyReleasesImageModelAndIgnoresPictures() = runBlocking {
        val app = app()
        val chat = app.chats.createChat()
        app.generation.draw(chat.id, "a lighthouse")
        val releasesBefore = app.imageGenerator.releases

        app.engine.reply = listOf("Красивый маяк!")
        app.generation.send(chat.id, "Нравится?")

        assertEquals(releasesBefore + 1, app.imageGenerator.releases)
        // Пустой ответ-картинка не попадает в историю для модели чата.
        val history = checkNotNull(app.engine.lastRequest).messages
        assertEquals(listOf("a lighthouse", "Нравится?"), history.map { it.content })
        assertFalse(history.any { it.images.isNotEmpty() })
    }
}
