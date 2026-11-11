package ai.prism.service

import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.SpeechEngineType
import ai.prism.domain.voice.VoiceError
import ai.prism.domain.voice.VoiceException
import ai.prism.service.voice.VoicePhase
import ai.prism.testing.FakeAudioInput
import ai.prism.testing.FakeModelRepository
import ai.prism.testing.TestApp
import ai.prism.testing.testModel
import ai.prism.testing.testRecognizer
import ai.prism.testing.testVoice
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceServiceTest {

    private fun entry(model: ai.prism.domain.model.CatalogModel, status: ModelStatus = ModelStatus.Downloaded) =
        ModelEntry(model, status, fitsInMemory = true)

    private fun voiceApp(
        recognizer: ModelStatus = ModelStatus.Downloaded,
        voice: ModelStatus = ModelStatus.Downloaded,
        input: FakeAudioInput = FakeAudioInput(),
    ) = TestApp(
        models = FakeModelRepository(
            entries = listOf(entry(testModel()), entry(testRecognizer(), recognizer), entry(testVoice(), voice)),
        ),
        audioInput = input,
    )

    @Test
    fun availabilityFollowsDownloadedModels() {
        val app = voiceApp(voice = ModelStatus.NotDownloaded)
        val available = app.voice.availability.value

        assertTrue(available.supported)
        assertTrue(available.canListen)
        assertFalse(available.canSpeak)
        assertEquals("piper-test", available.suggestedVoice?.id)
        assertEquals("gigaam-test", available.recognizer?.id)
    }

    @Test
    fun noSpeechModelsInCatalogMeansNoVoice() {
        val app = TestApp()
        assertFalse(app.voice.availability.value.supported)
    }

    @Test
    fun dictationRecognizesThePhrase() = runBlocking {
        val app = voiceApp()
        val chat = app.chats.createChat()
        var text: String? = null

        assertTrue(app.voice.dictate(chat.id) { text = it })

        assertEquals("Какая сегодня погода?", text)
        val spec = app.speechEngine.recognized.single()
        assertEquals(SpeechEngineType.NEMO_CTC, spec.engine)
        assertEquals("ru", spec.language)
        assertEquals("/models/gigaam-test-tokens.txt", spec.files["tokens"])
        assertEquals(VoicePhase.IDLE, app.voice.state.value.phase)
        assertNull(app.voice.state.value.error)
        // Модели речи выгружаются, когда голос не нужен.
        assertEquals(1, app.speechEngine.releases)
    }

    @Test
    fun silenceIsReportedAsNothingHeard() = runBlocking {
        val app = voiceApp(input = FakeAudioInput(listOf(FakeAudioInput.silence(FakeAudioInput.LONG_SILENCE_CHUNKS))))
        val chat = app.chats.createChat()
        var text: String? = null

        app.voice.dictate(chat.id) { text = it }

        assertNull(text)
        assertTrue(app.speechEngine.recognized.isEmpty())
        assertEquals(VoiceError.NOTHING_HEARD, app.voice.state.value.error)
        app.voice.dismissError()
        assertNull(app.voice.state.value.error)
    }

    @Test
    fun missingRecognizerAndMicrophonePermissionAreErrors() = runBlocking {
        val app = voiceApp(recognizer = ModelStatus.NotDownloaded)
        val chat = app.chats.createChat()
        app.voice.dictate(chat.id) {}
        assertEquals(VoiceError.NO_RECOGNIZER, app.voice.state.value.error)

        val denied = voiceApp().apply {
            audioInput.error = VoiceException(VoiceError.PERMISSION_DENIED, "denied")
        }
        denied.voice.dictate(chat.id) {}
        assertEquals(VoiceError.PERMISSION_DENIED, denied.voice.state.value.error)
    }

    @Test
    fun replyIsSpokenSentenceBySentenceWithoutMarkdown() = runBlocking {
        val app = voiceApp()

        assertTrue(app.voice.speak("message-1", "**Привет!** Вот код:\n```\nx = 1\n```\nГотово."))

        assertEquals(listOf("Привет!", "Вот код:", "Готово."), app.speechEngine.spoken)
        assertEquals(3, app.audioOutput.played.size)
        assertEquals("piper-test", app.speechEngine.voices.first().modelId)
        assertEquals(VoicePhase.IDLE, app.voice.state.value.phase)
        assertNull(app.voice.state.value.speakingMessageId)
    }

    @Test
    fun speakingWithoutVoiceIsAnError() = runBlocking {
        val app = voiceApp(voice = ModelStatus.NotDownloaded)

        app.voice.speak("message-1", "Привет!")

        assertEquals(VoiceError.NO_VOICE, app.voice.state.value.error)
        assertTrue(app.audioOutput.played.isEmpty())
    }

    @Test
    fun conversationSendsTheQuestionAndSpeaksTheAnswerUntilSilence() = runBlocking {
        val app = voiceApp()
        val chat = app.chats.createChat()

        assertTrue(app.voice.startConversation(chat.id))

        val messages = app.chats.messagesSnapshot(chat.id)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), messages.map { it.role })
        assertEquals("Какая сегодня погода?", messages.first().content)
        assertEquals(listOf("Привет!", "Я Призм эй-ай."), app.speechEngine.spoken)
        // Второй раз никто не заговорил — разговор закончился сам.
        assertEquals(2, app.audioInput.started)
        assertNull(app.voice.state.value.conversationChatId)
        assertNull(app.voice.state.value.error)
    }

    @Test
    fun conversationStopsWhenTheAnswerFails() = runBlocking {
        val app = voiceApp(input = FakeAudioInput(listOf(FakeAudioInput.phrase(), FakeAudioInput.phrase())))
        app.models.activeModelId.value = null
        val chat = app.chats.createChat()

        app.voice.startConversation(chat.id)

        assertEquals(1, app.audioInput.started)
        assertTrue(app.speechEngine.spoken.isEmpty())
        assertNull(app.voice.state.value.conversationChatId)
    }
}
