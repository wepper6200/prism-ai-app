package ai.prism.service

import ai.prism.domain.imagegen.ImageGenerationError
import ai.prism.domain.imagegen.ImageGenerationException
import ai.prism.domain.imagegen.ImageGenerationProgress
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelKind
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.music.MusicGenerator
import ai.prism.domain.music.MusicRequest
import ai.prism.testing.FakeInferenceEngine
import ai.prism.testing.FakeModelRepository
import ai.prism.testing.TestApp
import ai.prism.testing.testModel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicServiceTest {
    private class Generator : MusicGenerator {
        override val isSupported = true
        var prompt: String? = null
        var releases = 0
        var failure: ImageGenerationError? = null
        var duringGeneration: () -> Unit = {}
        override suspend fun generate(files: Map<String, String>, request: MusicRequest, onProgress: (ImageGenerationProgress) -> Unit): ByteArray {
            prompt = request.prompt
            duringGeneration()
            failure?.let { throw ImageGenerationException(it, "test") }
            return byteArrayOf(1, 2, 3)
        }
        override fun cancel() = Unit
        override suspend fun release() { releases++ }
    }

    private fun app(generator: Generator, downloaded: Boolean = true): TestApp = TestApp(
        engine = FakeInferenceEngine(reply = listOf("A calm piano instrumental")),
        models = FakeModelRepository(listOf(
            ModelEntry(testModel(), ModelStatus.Downloaded, true),
            ModelEntry(testModel().copy(id = "music", kind = ModelKind.MUSIC),
                if (downloaded) ModelStatus.Downloaded else ModelStatus.NotDownloaded, true),
        )),
        musicGenerator = generator,
    )

    @Test
    fun translatesStoresAudioAndRetriesWithMusicModel() = runBlocking {
        val generator = Generator()
        val app = app(generator)
        val chat = app.chats.createChat()
        assertTrue(app.generation.composeMusic(chat.id, "Спокойная музыка на пианино"))
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.COMPLETE, reply.status)
        assertEquals("music", reply.modelId)
        assertTrue(reply.images.single().isAudio)
        assertEquals("A calm piano instrumental", generator.prompt)
        assertEquals(1, generator.releases)
        assertNull(app.generation.active.value)
        app.chats.updateMessage(reply.id, "", MessageStatus.STOPPED, null)
        app.generation.retry(chat.id)
        assertEquals(MessageStatus.COMPLETE, app.chats.messagesSnapshot(chat.id).last().status)
        assertEquals(2, generator.releases)
    }

    @Test
    fun missingComponentsDoNotLaunchGenerator() = runBlocking {
        val generator = Generator()
        val app = app(generator, downloaded = false)
        val chat = app.chats.createChat()
        app.generation.composeMusic(chat.id, "piano")
        assertEquals(GenerationError.NO_MUSIC_MODEL, app.chats.messagesSnapshot(chat.id).last().error)
        assertNull(generator.prompt)
    }

    @Test
    fun failureAndStopReleaseWorkerWithoutAttachingPartialAudio() = runBlocking {
        val generator = Generator().apply { failure = ImageGenerationError.NOT_ENOUGH_MEMORY }
        val app = app(generator)
        val chat = app.chats.createChat()
        app.generation.composeMusic(chat.id, "piano")
        assertEquals(GenerationError.NOT_ENOUGH_MEMORY, app.chats.messagesSnapshot(chat.id).last().error)
        generator.failure = null
        generator.duringGeneration = { app.generation.stop() }
        app.generation.composeMusic(chat.id, "piano")
        val stopped = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.STOPPED, stopped.status)
        assertTrue(stopped.images.isEmpty())
        assertEquals(2, generator.releases)
        assertFalse(app.generation.composeMusic(chat.id, " "))
    }
}
