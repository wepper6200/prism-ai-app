package ai.prism.testing

import ai.prism.domain.voice.AudioInput
import ai.prism.domain.voice.AudioOutput
import ai.prism.domain.voice.RecognizerSpec
import ai.prism.domain.voice.SPEECH_SAMPLE_RATE
import ai.prism.domain.voice.SpeechAudio
import ai.prism.domain.voice.SpeechEngine
import ai.prism.domain.voice.VoiceDetector
import ai.prism.domain.voice.VoiceException
import ai.prism.domain.voice.VoiceSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.abs

/** Распознаёт любую речь как [transcript] и «озвучивает» фразы короткой тишиной. */
class FakeSpeechEngine(var supported: Boolean = true) : SpeechEngine {
    var transcript = "Какая сегодня погода?"
    val recognized = mutableListOf<RecognizerSpec>()
    val spoken = mutableListOf<String>()
    val voices = mutableListOf<VoiceSpec>()
    var releases = 0

    override val isSupported: Boolean get() = supported

    override suspend fun transcribe(spec: RecognizerSpec, audio: SpeechAudio): String {
        recognized += spec
        return transcript
    }

    override suspend fun synthesize(spec: VoiceSpec, text: String, speed: Float): SpeechAudio {
        voices += spec
        spoken += text
        return SpeechAudio(FloatArray(SPEECH_SAMPLE_RATE / 10), SPEECH_SAMPLE_RATE)
    }

    override fun voiceDetector(modelPath: String, minSilenceSeconds: Float): VoiceDetector = FakeVoiceDetector()

    override suspend fun release() {
        releases++
    }
}

/** Речь — порции громче 0,1; фрагмент заканчивается на первой тихой порции после речи. */
class FakeVoiceDetector : VoiceDetector {
    private val current = mutableListOf<Float>()
    private val finished = mutableListOf<FloatArray>()
    private var speaking = false

    override fun accept(samples: FloatArray) {
        if (samples.any { abs(it) > SPEECH_LEVEL }) {
            current += samples.toList()
            speaking = true
        } else if (speaking) {
            flush()
        }
    }

    override val isSpeaking: Boolean get() = speaking

    override fun takeSegments(): List<FloatArray> = finished.toList().also { finished.clear() }

    override fun flush() {
        if (current.isNotEmpty()) finished += current.toFloatArray()
        current.clear()
        speaking = false
    }

    override fun close() = Unit

    private companion object {
        const val SPEECH_LEVEL = 0.1f
    }
}

/**
 * Микрофон: каждая запись отдаёт следующую заготовку из [recordings]; когда они закончатся — долгую тишину
 * (дольше, чем приложение ждёт начала речи).
 */
class FakeAudioInput(recordings: List<List<FloatArray>> = listOf(phrase())) : AudioInput {
    val recordings = ArrayDeque(recordings)
    var error: VoiceException? = null
    var started = 0

    override fun record(): Flow<FloatArray> = flow {
        started++
        error?.let { throw it }
        (recordings.removeFirstOrNull() ?: silence(LONG_SILENCE_CHUNKS)).forEach { emit(it) }
    }

    companion object {
        /** Тишина, [speechChunks] порций речи по 0,1 с и снова тишина. */
        fun phrase(speechChunks: Int = 5, silenceChunks: Int = 3): List<FloatArray> {
            val chunk = SPEECH_SAMPLE_RATE / 10
            val silence = List(silenceChunks) { FloatArray(chunk) }
            val speech = List(speechChunks) { FloatArray(chunk) { 0.5f } }
            return silence + speech + silence
        }

        fun silence(chunks: Int = 10): List<FloatArray> = List(chunks) { FloatArray(SPEECH_SAMPLE_RATE / 10) }

        /** 25 с тишины. */
        const val LONG_SILENCE_CHUNKS = 250
    }
}

/** Динамик, который запоминает проигранные клипы. */
class FakeAudioOutput : AudioOutput {
    val played = mutableListOf<SpeechAudio>()

    override suspend fun play(audio: SpeechAudio) {
        played += audio
    }
}
