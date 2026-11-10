package ai.prism.service

import ai.prism.domain.imagegen.ImageGenerationError
import ai.prism.domain.imagegen.ImageGenerationException
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelKind
import ai.prism.domain.model.ModelStatus
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

    private fun videoModel(imageToVideo: Boolean = true) = testImageModel(id = "wan-test", name = "Wan 2.2 TI2V 5B").copy(
        kind = ModelKind.VIDEO,
        image = null,
        video = VideoModelSettings(width = 832, height = 480, frames = 49, fps = 24, steps = 3, negativePrompt = "статика", imageToVideo = imageToVideo),
    )

    private fun app(videoDownloaded: Boolean = true, imageToVideo: Boolean = true) = TestApp(
        engine = FakeInferenceEngine(reply = listOf("A cat running on the grass")),
        models = FakeModelRepository(
            listOf(
                ModelEntry(testModel(), ModelStatus.Downloaded, fitsInMemory = true),
                ModelEntry(
                    videoModel(imageToVideo),
                    if (videoDownloaded) ModelStatus.Downloaded else ModelStatus.NotDownloaded,
                    fitsInMemory = true,
                ),
            ),
        ),
    )

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
}
