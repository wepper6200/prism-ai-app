package ai.prism.service

import ai.prism.core.MjpegAvi
import ai.prism.domain.imagegen.ImageGenerationError
import ai.prism.domain.imagegen.ImageGenerationException
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.model.ModelComponent
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelKind
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.model.TextEncoderMode
import ai.prism.domain.model.VideoModelSettings
import ai.prism.testing.FakeInferenceEngine
import ai.prism.testing.FakeModelRepository
import ai.prism.testing.TestApp
import ai.prism.testing.testImageModel
import ai.prism.testing.testModel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoServiceTest {

    private fun videoModel(imageToVideo: Boolean = true, durations: List<Int> = emptyList()) =
        testImageModel(id = "wan-test", name = "Wan 2.2 TI2V 5B").copy(
            kind = ModelKind.VIDEO,
            image = null,
            video = VideoModelSettings(
                width = 832,
                height = 480,
                frames = 49,
                fps = 24,
                steps = 3,
                negativePrompt = "статика",
                imageToVideo = imageToVideo,
                maxFrames = 121,
                durations = durations,
            ),
        )

    private fun app(videoDownloaded: Boolean = true, imageToVideo: Boolean = true, durations: List<Int> = emptyList()) = TestApp(
        engine = FakeInferenceEngine(reply = listOf("A cat running on the grass")),
        models = FakeModelRepository(
            listOf(
                ModelEntry(testModel(), ModelStatus.Downloaded, fitsInMemory = true),
                ModelEntry(
                    videoModel(imageToVideo, durations),
                    if (videoDownloaded) ModelStatus.Downloaded else ModelStatus.NotDownloaded,
                    fitsInMemory = true,
                ),
            ),
        ),
    )

    /** LTX: текстовый энкодер Gemma в bf16 (роль `llm`), который можно сжать. */
    private fun ltxApp(totalRamBytes: Long) = TestApp(
        engine = FakeInferenceEngine(reply = listOf("A cat running on the grass")),
        models = FakeModelRepository(
            listOf(
                ModelEntry(testModel(), ModelStatus.Downloaded, fitsInMemory = true),
                ModelEntry(
                    videoModel().copy(
                        id = "ltx-test",
                        components = listOf(
                            ModelComponent("llm", "https://example.com/gemma.safetensors", "gemma-bf16.safetensors", 26_263_858_182, "5".repeat(64)),
                        ),
                    ),
                    ModelStatus.Downloaded,
                    fitsInMemory = true,
                ),
            ),
        ),
        totalRamBytes = totalRamBytes,
    )

    @Test
    fun textEncoderIsCompressedOnceAndUsedInsteadOfFullFile(): Unit = runBlocking {
        val app = ltxApp(totalRamBytes = 32L * GB)
        val chat = app.chats.createChat()
        assertEquals(TextEncoderMode.Q4, app.generation.textEncoderMode())

        app.generation.animate(chat.id, "Кот бежит по траве")
        app.generation.animate(chat.id, "Кот спит")

        val compressed = "/models/gemma-bf16.safetensors.q4_k.gguf"
        assertEquals(listOf(Triple("/models/gemma-bf16.safetensors", compressed, "q4_k")), app.imageGenerator.compressions)
        assertEquals(compressed, app.imageGenerator.lastFiles?.get("llm"))
        assertEquals(MessageStatus.COMPLETE, app.chats.messagesSnapshot(chat.id).last().status)

        // Выбор в настройках важнее объёма памяти: полный энкодер — без сжатия.
        app.settings.setTextEncoderMode(TextEncoderMode.FULL)
        app.generation.animate(chat.id, "Кот прыгает")
        assertEquals(1, app.imageGenerator.compressions.size)
        assertEquals("/models/gemma-bf16.safetensors", app.imageGenerator.lastFiles?.get("llm"))

        app.settings.setTextEncoderMode(TextEncoderMode.Q8)
        app.generation.animate(chat.id, "Кот бежит")
        assertEquals("q8_0", app.imageGenerator.compressions.last().third)
        assertEquals("/models/gemma-bf16.safetensors.q8_0.gguf", app.imageGenerator.lastFiles?.get("llm"))
    }

    @Test
    fun textEncoderDefaultsToFullOnBigMachinesAndFailedCompressionIsReported(): Unit = runBlocking {
        assertEquals(TextEncoderMode.FULL, ltxApp(totalRamBytes = 64L * GB).generation.textEncoderMode())
        assertEquals(TextEncoderMode.Q8, ltxApp(totalRamBytes = 48L * GB).generation.textEncoderMode())
        assertEquals(TextEncoderMode.FULL, ltxApp(totalRamBytes = 0L).generation.textEncoderMode())

        val app = ltxApp(totalRamBytes = 16L * GB)
        app.imageGenerator.compressError = ImageGenerationException(ImageGenerationError.NOT_ENOUGH_MEMORY, "нет памяти")
        val chat = app.chats.createChat()
        app.generation.animate(chat.id, "Кот бежит по траве")
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.FAILED, reply.status)
        assertEquals(GenerationError.NOT_ENOUGH_MEMORY, reply.error)
        assertNull(app.imageGenerator.lastVideoRequest)
    }

    @Test
    fun videoIsMadeFromTranslatedPromptAndStored(): Unit = runBlocking {
        val app = app()
        val chat = app.chats.createChat()

        app.generation.animate(chat.id, "Кот бежит по траве")

        val request = checkNotNull(app.imageGenerator.lastVideoRequest)
        assertEquals("A cat running on the grass", request.prompt)
        assertEquals("статика", request.negativePrompt)
        assertEquals(49 to 24, request.frames to request.fps)
        assertNull(request.initImagePath)
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.COMPLETE, reply.status)
        assertEquals("wan-test", reply.modelId)
        val video = reply.images.single()
        assertTrue(video.isVideo)
        assertContentEquals(app.imageGenerator.avi, app.images.files[video.fileName])
        assertNull(app.generation.active.value)
    }

    @Test
    fun photoIsAnimatedOnlyByCapableModel(): Unit = runBlocking {
        val app = app(imageToVideo = true)
        val chat = app.chats.createChat()
        val photo = app.images.save(byteArrayOf(1, 2, 3))

        app.generation.animate(chat.id, "make the cat run", photo)
        assertEquals("/images/${photo.fileName}", app.imageGenerator.lastVideoRequest?.initImagePath)

        val other = app(imageToVideo = false)
        val otherChat = other.chats.createChat()
        other.generation.animate(otherChat.id, "make the cat run", other.images.save(byteArrayOf(1)))
        assertNull(other.imageGenerator.lastVideoRequest?.initImagePath)
    }

    @Test
    fun missingModelAndFailuresAreReported(): Unit = runBlocking {
        val missing = app(videoDownloaded = false)
        val chat = missing.chats.createChat()
        missing.generation.animate(chat.id, "a lighthouse")
        assertEquals(GenerationError.NO_VIDEO_MODEL, missing.chats.messagesSnapshot(chat.id).last().error)

        val failing = app()
        failing.imageGenerator.error = ImageGenerationException(ImageGenerationError.FAILED, "сбой")
        val failingChat = failing.chats.createChat()
        failing.generation.animate(failingChat.id, "a lighthouse")
        assertEquals(GenerationError.VIDEO_GENERATION_FAILED, failing.chats.messagesSnapshot(failingChat.id).last().error)

        // Повтор снимает видео заново, а не отвечает текстом.
        failing.imageGenerator.error = null
        failing.generation.retry(failingChat.id)
        val retried = failing.chats.messagesSnapshot(failingChat.id).last()
        assertEquals(MessageStatus.COMPLETE, retried.status)
        assertTrue(retried.images.single().isVideo)
    }

    /** 10 секунд на Wan 2.2: два куска по 121 кадру, второй — от последнего кадра первого; склейка без повтора кадра. */
    @Test
    fun longVideoIsChainedFromTheLastFrame(): Unit = runBlocking {
        val app = app(durations = listOf(2, 5, 10))
        app.imageGenerator.realVideo = true
        app.settings.setVideoSeconds(10)
        val chat = app.chats.createChat()

        app.generation.animate(chat.id, "Кот бежит по траве")

        val requests = app.imageGenerator.videoRequests
        assertEquals(listOf(121, 121), requests.map { it.frames })
        assertNull(requests[0].initImagePath)
        assertTrue(requests[1].initImagePath != null, "второй кусок продолжает первый")
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.COMPLETE, reply.status)
        val video = checkNotNull(MjpegAvi.parse(checkNotNull(app.images.files[reply.images.single().fileName])))
        assertEquals(241, video.frames.size)
        // Кадры первого куска, затем второго без его первого кадра.
        assertContentEquals(byteArrayOf(1, 120), video.frames[120])
        assertContentEquals(byteArrayOf(2, 1), video.frames[121])
        // Временный кадр-продолжение удалён: в хранилище только сам ролик.
        assertEquals(setOf(reply.images.single().fileName), app.images.files.keys)
    }

    @Test
    fun stoppedLongVideoKeepsFinishedParts(): Unit = runBlocking {
        val app = app(durations = listOf(2, 5, 15))
        app.imageGenerator.realVideo = true
        app.imageGenerator.cancelOnSegment = 2
        app.settings.setVideoSeconds(15)
        val chat = app.chats.createChat()

        app.generation.animate(chat.id, "Кот бежит по траве")

        assertEquals(2, app.imageGenerator.videoRequests.size)
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.STOPPED, reply.status)
        val video = checkNotNull(MjpegAvi.parse(checkNotNull(app.images.files[reply.images.single().fileName])))
        assertEquals(121, video.frames.size)
    }

    @Test
    fun durationFollowsSettingsAndModel(): Unit = runBlocking {
        val app = app(durations = listOf(2, 3, 5))
        val model = checkNotNull(app.generation.videoModel())
        assertEquals(2, app.generation.videoSeconds(model))
        app.settings.setVideoSeconds(4)
        // 4 секунд у модели нет — берётся ближайшая длительность.
        assertTrue(app.generation.videoSeconds(model) in setOf(3, 5))
        app.settings.setVideoSeconds(3)
        val chat = app.chats.createChat()
        app.generation.animate(chat.id, "Кот бежит по траве")
        assertEquals(73, app.imageGenerator.lastVideoRequest?.frames)
    }

    private companion object {
        const val GB = 1024L * 1024 * 1024
    }
}
