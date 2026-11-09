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
        chatModel: ai.prism.domain.model.CatalogModel = testModel(),
    ) = TestApp(
        engine = engine,
        models = FakeModelRepository(
            listOf(
                ModelEntry(chatModel, ModelStatus.Downloaded, fitsInMemory = true),
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

    @Test
    fun refusedTranslationIsReportedInsteadOfDrawingRussianText() = runBlocking {
        val app = app(engine = FakeInferenceEngine(reply = listOf("I'm sorry, but I can't help with that.")))
        val chat = app.chats.createChat()

        app.generation.draw(chat.id, "старинный замок в тумане")

        // Генератор понимает только английский: русский текст дал бы картинку не по описанию.
        assertNull(app.imageGenerator.lastRequest)
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.FAILED, reply.status)
        assertEquals(GenerationError.TRANSLATION_FAILED, reply.error)
        assertIs<EngineState.Idle>(app.engine.state.value)
    }

    @Test
    fun smallestDownloadedChatModelTranslatesWhenNoneIsActive() = runBlocking {
        val small = testModel(id = "small", name = "Gemma 3 1B").copy(fileName = "small.gguf", sizeBytes = 800_000_000)
        val large = testModel(id = "large", name = "Gemma 4 E4B").copy(fileName = "large.gguf", sizeBytes = 5_000_000_000)
        val app = TestApp(
            engine = FakeInferenceEngine(reply = listOf("An anime girl with blue hair")),
            models = FakeModelRepository(
                listOf(
                    ModelEntry(large, ModelStatus.Downloaded, fitsInMemory = true),
                    ModelEntry(small, ModelStatus.Downloaded, fitsInMemory = true),
                    ModelEntry(testImageModel(), ModelStatus.Downloaded, fitsInMemory = true),
                ),
                activeId = null,
            ),
        )
        val chat = app.chats.createChat()

        app.generation.draw(chat.id, "аниме девушка с голубыми волосами")

        assertEquals(listOf("/models/small.gguf"), app.engine.loadedPaths)
        assertEquals("An anime girl with blue hair", app.imageGenerator.lastRequest?.prompt)
        assertEquals(MessageStatus.COMPLETE, app.chats.messagesSnapshot(chat.id).last().status)
    }

    @Test
    fun russianPromptWithoutChatModelAsksForTranslator() = runBlocking {
        val app = TestApp(
            models = FakeModelRepository(listOf(ModelEntry(testImageModel(), ModelStatus.Downloaded, fitsInMemory = true))),
        )
        val chat = app.chats.createChat()

        app.generation.draw(chat.id, "аниме девушка")

        assertNull(app.imageGenerator.lastRequest)
        assertEquals(GenerationError.TRANSLATOR_REQUIRED, app.chats.messagesSnapshot(chat.id).last().error)

        // Английское описание рисуется и без модели чата.
        app.generation.draw(chat.id, "an anime girl")
        assertEquals("an anime girl", app.imageGenerator.lastRequest?.prompt)
        assertEquals(MessageStatus.COMPLETE, app.chats.messagesSnapshot(chat.id).last().status)
    }

    @Test
    fun photoIsRedrawnWithItsProportions() = runBlocking {
        val app = app()
        val chat = app.chats.createChat()
        val photo = app.images.save(byteArrayOf(1, 2, 3))

        app.generation.draw(chat.id, "make it winter", photo)

        val request = checkNotNull(app.imageGenerator.lastRequest)
        assertEquals("/images/${photo.fileName}", request.initImagePath)
        assertEquals(1024 to 768, request.width to request.height)
        assertEquals(0.7f, request.strength)
        assertEquals(listOf(photo), app.chats.messagesSnapshot(chat.id).first().images)

        // Повтор перерисовывает то же фото.
        app.imageGenerator.lastRequest = null
        app.imageGenerator.error = null
        app.chats.messagesSnapshot(chat.id).last().let { app.chats.updateMessage(it.id, "", MessageStatus.STOPPED, null) }
        app.generation.retry(chat.id)
        assertEquals("/images/${photo.fileName}", app.imageGenerator.lastRequest?.initImagePath)
    }

    @Test
    fun safeModeBlocksExplicitWordsWithoutDrawing() = runBlocking {
        val app = app()
        app.settings.setSafeMode(true)
        val chat = app.chats.createChat()

        app.generation.draw(chat.id, "голая девушка на пляже")

        assertEquals(GenerationError.BLOCKED_BY_SAFE_MODE, app.chats.messagesSnapshot(chat.id).last().error)
        assertNull(app.imageGenerator.lastRequest)
    }

    @Test
    fun safeModeAsksChatModelAndRespectsVerdict() = runBlocking {
        val app = app(engine = FakeInferenceEngine(reply = listOf("BLOCKED")))
        app.settings.setSafeMode(true)
        val chat = app.chats.createChat()

        app.generation.draw(chat.id, "a scary scene")

        assertEquals("Проверь и переведи.", app.engine.lastRequest?.systemPrompt)
        assertEquals(GenerationError.BLOCKED_BY_SAFE_MODE, app.chats.messagesSnapshot(chat.id).last().error)
        assertNull(app.imageGenerator.lastRequest)
    }

    @Test
    fun safeModeReviewsPictureWithVisionModel() = runBlocking {
        val engine = FakeInferenceEngine().apply {
            queuedReplies += listOf("A cat in a garden")
            queuedReplies += listOf("YES")
        }
        val app = app(engine = engine, chatModel = testModel(vision = ai.prism.testing.testVision()))
        app.settings.setSafeMode(true)
        val chat = app.chats.createChat()

        app.generation.draw(chat.id, "кот в саду")

        assertEquals("A cat in a garden", app.imageGenerator.lastRequest?.prompt)
        val review = engine.requests.last().messages.single()
        assertEquals("Есть недопустимое?", review.content)
        assertEquals(1, review.images.size)
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(GenerationError.BLOCKED_BY_SAFE_MODE, reply.error)
        assertTrue(reply.images.isEmpty())
        assertTrue(app.images.files.isEmpty(), "запрещённая картинка удалена")
    }

    @Test
    fun safeModeAddsRuleToChat() = runBlocking {
        val app = app()
        app.settings.setSafeMode(true)
        val chat = app.chats.createChat()

        app.generation.send(chat.id, "Привет")

        assertTrue(checkNotNull(app.engine.lastRequest).systemPrompt.endsWith("Безопасный режим."))
    }
}
