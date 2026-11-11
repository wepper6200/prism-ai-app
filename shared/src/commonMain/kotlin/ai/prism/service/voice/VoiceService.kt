package ai.prism.service.voice

import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelKind
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.model.SpeechEngineType
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.repository.ModelRepository
import ai.prism.domain.repository.SettingsRepository
import ai.prism.domain.voice.AudioInput
import ai.prism.domain.voice.AudioOutput
import ai.prism.domain.voice.RecognizerSpec
import ai.prism.domain.voice.SPEECH_SAMPLE_RATE
import ai.prism.domain.voice.SpeechAudio
import ai.prism.domain.voice.SpeechEngine
import ai.prism.domain.voice.VoiceError
import ai.prism.domain.voice.VoiceException
import ai.prism.domain.voice.VoiceSpec
import ai.prism.service.ChatGenerationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.sqrt

/** Чем сейчас занят голос. */
enum class VoicePhase {
    IDLE,

    /** Микрофон записывает. */
    LISTENING,

    /** Речь распознаётся. */
    RECOGNIZING,

    /** Режим разговора: ассистент готовит ответ. */
    THINKING,

    /** Ответ озвучивается. */
    SPEAKING,
}

data class VoiceState(
    val phase: VoicePhase = VoicePhase.IDLE,
    /** Громкость микрофона (0–1) для индикатора записи. */
    val level: Float = 0f,
    /** Ответ, который сейчас озвучивается. */
    val speakingMessageId: String? = null,
    /** Диалог, в котором идёт разговор голосом. */
    val conversationChatId: String? = null,
    /** Диалог, для которого идёт диктовка. */
    val dictationChatId: String? = null,
    /** Последняя ошибка; экран показывает её и сбрасывает через [VoiceService.dismissError]. */
    val error: VoiceError? = null,
)

/** Какие модели речи есть и какие скачаны. */
data class VoiceAvailability(
    /** Голос есть на этой платформе (движок и модели в каталоге). */
    val supported: Boolean = false,
    /** Модель распознавания, которой пользуется приложение, или `null`, если ни одна не скачана. */
    val recognizer: CatalogModel? = null,
    /** Голос для озвучки или `null`, если ни один не скачан. */
    val voice: CatalogModel? = null,
    /** Что предложить скачать для распознавания (рекомендованная для платформы модель). */
    val suggestedRecognizer: CatalogModel? = null,
    /** Какой голос предложить скачать. */
    val suggestedVoice: CatalogModel? = null,
) {
    val canListen: Boolean get() = supported && recognizer != null
    val canSpeak: Boolean get() = supported && voice != null
}

/**
 * Голос в области приложения: диктовка в поле ввода, озвучка ответов и режим разговора
 * (слушает → отвечает → озвучивает → снова слушает). Одновременно идёт одно голосовое действие.
 */
class VoiceService(
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val engine: SpeechEngine,
    private val input: AudioInput,
    private val output: AudioOutput,
    private val generation: ChatGenerationService,
    private val chats: ChatRepository,
    private val scope: CoroutineScope,
    /** `android` или `desktop`: какую модель рекомендовать для загрузки. */
    private val platform: String? = null,
    /** Через сколько миллисекунд без голосовых действий выгрузить модели речи из памяти. */
    private val idleReleaseMs: Long = IDLE_RELEASE_MS,
) {
    private val mutableState = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = mutableState.asStateFlow()

    val availability: StateFlow<VoiceAvailability> = combine(models.models, settings.settings) { entries, current ->
        val recognizers = entries.filter { it.model.kind == ModelKind.SPEECH_RECOGNITION }
        val voices = entries.filter { it.model.kind == ModelKind.SPEECH_SYNTHESIS }
        VoiceAvailability(
            supported = engine.isSupported && recognizers.isNotEmpty(),
            recognizer = chosen(recognizers, current.speechModelId),
            voice = chosen(voices, current.voiceModelId),
            suggestedRecognizer = suggested(recognizers),
            suggestedVoice = suggested(voices),
        )
    }.stateIn(scope, SharingStarted.Eagerly, VoiceAvailability())

    @Volatile
    private var job: Job? = null
    private var releaseJob: Job? = null

    @Volatile
    private var stopListeningRequested = false

    /**
     * Диктовка: слушает до паузы после фразы (или до [stopListening]) и передаёт распознанный текст в [onText].
     * `false` — уже идёт другое голосовое действие.
     */
    fun dictate(chatId: String, onText: (String) -> Unit): Boolean = start(VoiceState(dictationChatId = chatId)) {
        val recognizer = recognizerSpec()
        val segments = listen(recognizer.vad, DICTATION_SILENCE_S, waitForSpeechMs = DICTATION_WAIT_MS)
        if (segments.isEmpty()) {
            if (!stopListeningRequested) throw VoiceException(VoiceError.NOTHING_HEARD, "No speech")
            return@start
        }
        setPhase(VoicePhase.RECOGNIZING)
        val text = transcribe(recognizer.spec, segments)
        if (text.isBlank()) throw VoiceException(VoiceError.NOTHING_HEARD, "Nothing recognized")
        onText(text)
    }

    /** Заканчивает запись: уже сказанное распознаётся (диктовка) или отправляется (разговор). */
    fun stopListening() {
        stopListeningRequested = true
    }

    /** Озвучивает готовый ответ [messageId]. Повторный вызов для того же ответа останавливает озвучку. */
    fun speak(messageId: String, text: String): Boolean {
        val current = mutableState.value
        if (current.speakingMessageId == messageId && current.conversationChatId == null) {
            stop()
            return true
        }
        if (current.conversationChatId == null) cancelCurrent()
        return start(VoiceState(phase = VoicePhase.SPEAKING, speakingMessageId = messageId)) {
            val voice = voiceSpec()
            val sentences = Channel<String>(Channel.UNLIMITED)
            speechSentences(text).forEach { sentences.send(it) }
            sentences.close()
            speakSentences(voice, sentences)
        }
    }

    /**
     * Режим разговора в диалоге [chatId]: слушает вопрос, отправляет его, озвучивает ответ по мере генерации
     * и снова слушает. Заканчивается кнопкой, ошибкой или долгой тишиной.
     */
    fun startConversation(chatId: String): Boolean {
        cancelCurrent()
        return start(VoiceState(conversationChatId = chatId)) {
            val recognizer = recognizerSpec()
            val voice = voiceSpec()
            while (true) {
                setPhase(VoicePhase.LISTENING)
                val segments = listen(recognizer.vad, CONVERSATION_SILENCE_S, waitForSpeechMs = CONVERSATION_WAIT_MS)
                if (segments.isEmpty()) return@start
                setPhase(VoicePhase.RECOGNIZING)
                val text = transcribe(recognizer.spec, segments)
                if (text.isBlank()) continue
                setPhase(VoicePhase.THINKING)
                if (!generation.send(chatId, text)) return@start
                // Ответ не удался или остановлен — разговор заканчивается, причина видна в чате.
                if (!speakReply(chatId, voice)) return@start
            }
        }
    }

    /** Останавливает любое голосовое действие: запись, озвучку или разговор. */
    fun stop() {
        cancelCurrent()
    }

    /** Показывает ошибку, найденную до начала записи (например, пользователь не дал доступ к микрофону). */
    fun reportError(error: VoiceError) {
        mutableState.update { it.copy(error = error) }
    }

    fun dismissError() {
        mutableState.update { it.copy(error = null) }
    }

    private fun cancelCurrent() {
        job?.cancel()
        job = null
        mutableState.update { VoiceState(error = it.error) }
    }

    private fun start(initial: VoiceState, block: suspend () -> Unit): Boolean {
        if (job?.isActive == true) return false
        releaseJob?.cancel()
        stopListeningRequested = false
        mutableState.value = initial
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                block()
            } catch (e: VoiceException) {
                if (job === self) mutableState.update { it.copy(error = e.error) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Prism AI: голос: ${e.message}")
                if (job === self) mutableState.update { it.copy(error = VoiceError.ENGINE) }
            } finally {
                // Остановленное действие могло уже смениться новым: его состояние не трогаем.
                if (job === self) {
                    job = null
                    mutableState.update { VoiceState(error = it.error) }
                }
                scheduleRelease()
            }
        }
        job = launched
        launched.start()
        return true
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(idleReleaseMs)
            if (job?.isActive != true) engine.release()
        }
    }

    private fun setPhase(phase: VoicePhase) {
        mutableState.update { it.copy(phase = phase, level = 0f) }
    }

    /** Модель распознавания с детектором речи. */
    private class Recognizer(val spec: RecognizerSpec, val vad: String)

    private suspend fun recognizerSpec(): Recognizer {
        val model = availability.value.recognizer?.takeIf { engine.isSupported }
            ?: throw VoiceException(VoiceError.NO_RECOGNIZER, "No speech recognition model")
        val speech = model.speech ?: throw VoiceException(VoiceError.ENGINE, "No speech settings for ${model.id}")
        val files = models.speechModelFiles(model.id)
            ?: throw VoiceException(VoiceError.NO_RECOGNIZER, "Model ${model.id} is not downloaded")
        val vad = files[ROLE_VAD] ?: throw VoiceException(VoiceError.ENGINE, "No voice detector for ${model.id}")
        return Recognizer(RecognizerSpec(model.id, speech.engine, files, speech.language), vad)
    }

    private suspend fun voiceSpec(): VoiceSpec {
        val model = availability.value.voice?.takeIf { engine.isSupported }
            ?: throw VoiceException(VoiceError.NO_VOICE, "No voice")
        val files = models.speechModelFiles(model.id) ?: throw VoiceException(VoiceError.NO_VOICE, "Voice ${model.id} is not downloaded")
        val dir = files[ROLE_VOICE] ?: throw VoiceException(VoiceError.ENGINE, "No voice files for ${model.id}")
        val speech = model.speech
        val speakers = speech?.speakers.orEmpty()
        return VoiceSpec(
            modelId = model.id,
            directory = dir,
            engine = speech?.engine ?: SpeechEngineType.PIPER,
            speaker = settings.settings.value.voiceSpeaker.takeIf { it in speakers.indices } ?: 0,
            language = speech?.language.orEmpty(),
            steps = speech?.steps ?: 0,
            speed = speech?.speed ?: 1.0f,
        )
    }

    /**
     * Записывает речь до паузы после фразы: возвращает фрагменты речи (без тишины).
     * Пусто — за [waitForSpeechMs] никто не заговорил или запись остановлена до начала речи.
     */
    private suspend fun listen(vadPath: String, minSilenceSeconds: Float, waitForSpeechMs: Long): List<FloatArray> {
        setPhase(VoicePhase.LISTENING)
        stopListeningRequested = false
        val detector = engine.voiceDetector(vadPath, minSilenceSeconds)
        val segments = mutableListOf<FloatArray>()
        var recordedSamples = 0L
        try {
            input.record().takeWhile { chunk ->
                detector.accept(chunk)
                segments += detector.takeSegments()
                recordedSamples += chunk.size
                mutableState.update { it.copy(level = level(chunk)) }
                val elapsedMs = recordedSamples * 1000 / SPEECH_SAMPLE_RATE
                val finished = stopListeningRequested ||
                    (segments.isNotEmpty() && !detector.isSpeaking) ||
                    (segments.isEmpty() && !detector.isSpeaking && elapsedMs > waitForSpeechMs) ||
                    elapsedMs > MAX_LISTEN_MS
                !finished
            }.collect()
            detector.flush()
            segments += detector.takeSegments()
        } finally {
            detector.close()
        }
        mutableState.update { it.copy(level = 0f) }
        return segments.filter { it.size >= MIN_SEGMENT_SAMPLES }
    }

    private suspend fun transcribe(spec: RecognizerSpec, segments: List<FloatArray>): String =
        segments.map { engine.transcribe(spec, SpeechAudio(it, SPEECH_SAMPLE_RATE)).trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** Озвучивает фразы по очереди: следующая синтезируется, пока играет текущая. */
    private suspend fun speakSentences(voice: VoiceSpec, sentences: ReceiveChannel<String>) = coroutineScope {
        val audio = Channel<SpeechAudio>(capacity = 1)
        launch {
            try {
                for (sentence in sentences) {
                    val spoken = SpeechNormalizer.normalize(sentence, voice.language)
                    audio.send(engine.synthesize(voice, spoken, voice.speed * settings.settings.value.speechRate))
                }
            } finally {
                audio.close()
            }
        }
        for (clip in audio) {
            mutableState.update { it.copy(phase = VoicePhase.SPEAKING) }
            output.play(clip)
        }
    }

    /**
     * Озвучивает ответ в диалоге [chatId] по мере генерации, пока он не закончится.
     * `true` — ответ готов и озвучен целиком.
     */
    private suspend fun speakReply(chatId: String, voice: VoiceSpec): Boolean = coroutineScope {
        val sentences = Channel<String>(Channel.UNLIMITED)
        val splitter = SpeechTextSplitter()
        var completed = false
        launch {
            try {
                var messageId: String? = null
                generation.active.takeWhile { it != null }.collect { active ->
                    if (active != null && active.chatId == chatId && active.messageId.isNotEmpty()) {
                        if (messageId == null) {
                            messageId = active.messageId
                            mutableState.update { it.copy(speakingMessageId = active.messageId) }
                        }
                        splitter.update(active.text).forEach { sentences.send(it) }
                    }
                }
                val reply = chats.messagesSnapshot(chatId).lastOrNull { message ->
                    message.role == MessageRole.ASSISTANT && (messageId == null || message.id == messageId)
                }
                if (reply != null && reply.status == MessageStatus.COMPLETE) {
                    completed = true
                    mutableState.update { it.copy(speakingMessageId = reply.id) }
                    splitter.finish(reply.content).forEach { sentences.send(it) }
                }
            } finally {
                sentences.close()
            }
        }
        speakSentences(voice, sentences)
        mutableState.update { it.copy(speakingMessageId = null) }
        completed
    }

    private fun chosen(entries: List<ModelEntry>, selectedId: String): CatalogModel? {
        val ready = entries.filter { it.status == ModelStatus.Downloaded }
        return (ready.firstOrNull { it.model.id == selectedId } ?: ready.firstOrNull())?.model
    }

    private fun suggested(entries: List<ModelEntry>): CatalogModel? =
        (entries.firstOrNull { platform != null && platform in it.model.recommendedFor } ?: entries.firstOrNull())?.model

    private fun level(chunk: FloatArray): Float {
        if (chunk.isEmpty()) return 0f
        var sum = 0.0
        for (sample in chunk) sum += sample * sample
        return (sqrt(sum / chunk.size) * LEVEL_GAIN).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val ROLE_VAD = "vad"
        const val ROLE_VOICE = "voice"

        /** Пауза, после которой фраза считается законченной. */
        const val DICTATION_SILENCE_S = 1.2f
        const val CONVERSATION_SILENCE_S = 0.9f

        /** Сколько ждать начала речи. */
        const val DICTATION_WAIT_MS = 8_000L
        const val CONVERSATION_WAIT_MS = 20_000L
        const val MAX_LISTEN_MS = 120_000L

        /** Фрагменты короче 0,25 с — щелчки и шум, а не речь. */
        const val MIN_SEGMENT_SAMPLES = SPEECH_SAMPLE_RATE / 4
        const val IDLE_RELEASE_MS = 120_000L
        private const val LEVEL_GAIN = 6.0
    }
}
