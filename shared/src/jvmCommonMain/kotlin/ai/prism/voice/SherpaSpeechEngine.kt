package ai.prism.voice

import ai.prism.domain.voice.RecognizerSpec
import ai.prism.domain.voice.SpeechAudio
import ai.prism.domain.voice.SpeechEngine
import ai.prism.domain.voice.VoiceDetector
import ai.prism.domain.voice.VoiceError
import ai.prism.domain.voice.VoiceException
import ai.prism.domain.voice.VoiceSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Модель распознавания sherpa-onnx в памяти. */
internal interface NativeRecognizer {
    fun transcribe(samples: FloatArray, sampleRate: Int): String

    fun release()
}

/** Голос sherpa-onnx в памяти. */
internal interface NativeVoice {
    fun generate(text: String, speed: Float): SpeechAudio

    fun release()
}

/** Детектор речи sherpa-onnx (Silero VAD). */
internal interface NativeVad {
    fun acceptWaveform(samples: FloatArray)

    fun isSpeechDetected(): Boolean

    fun empty(): Boolean

    /** Отсчёты первого законченного фрагмента речи. */
    fun frontSamples(): FloatArray

    fun pop()

    fun flush()

    fun release()
}

/** Файлы голоса Piper в распакованном архиве. */
internal data class PiperFiles(val model: String, val tokens: String, val dataDir: String) {
    companion object {
        /** Ищет модель `.onnx` с `tokens.txt` и `espeak-ng-data` рядом (в папке архива или вложенной). */
        fun find(directory: String): PiperFiles? = File(directory).walkTopDown()
            .maxDepth(SEARCH_DEPTH)
            .filter { it.isFile && it.name.endsWith(".onnx") }
            .mapNotNull { model ->
                val dir = model.parentFile ?: return@mapNotNull null
                val tokens = File(dir, "tokens.txt")
                val data = File(dir, "espeak-ng-data")
                if (tokens.isFile && data.isDirectory) PiperFiles(model.absolutePath, tokens.absolutePath, data.absolutePath) else null
            }
            .firstOrNull()

        private const val SEARCH_DEPTH = 3
    }
}

/**
 * Распознавание и озвучка на sherpa-onnx. Общая часть для Android (Kotlin API из AAR) и Desktop (Java API):
 * модели загружаются при первом обращении и остаются в памяти до [release] или смены модели;
 * все обращения к ним идут в одном потоке.
 */
abstract class SherpaSpeechEngine(protected val threads: Int) : SpeechEngine {

    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "prism-speech").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var recognizer: Pair<RecognizerSpec, NativeRecognizer>? = null
    private var voice: Pair<VoiceSpec, NativeVoice>? = null

    override val isSupported: Boolean = true

    internal abstract fun createRecognizer(spec: RecognizerSpec): NativeRecognizer

    internal abstract fun createVoice(files: PiperFiles): NativeVoice

    internal abstract fun createVad(modelPath: String, minSilenceSeconds: Float): NativeVad

    override suspend fun transcribe(spec: RecognizerSpec, audio: SpeechAudio): String = withContext(dispatcher) {
        val loaded = recognizer?.takeIf { it.first == spec }?.second ?: run {
            recognizer?.second?.release()
            recognizer = null
            native("load ${spec.modelId}") { createRecognizer(spec) }.also { recognizer = spec to it }
        }
        native("transcribe") { loaded.transcribe(audio.samples, audio.sampleRate) }
    }

    override suspend fun synthesize(spec: VoiceSpec, text: String, speed: Float): SpeechAudio = withContext(dispatcher) {
        val loaded = voice?.takeIf { it.first == spec }?.second ?: run {
            voice?.second?.release()
            voice = null
            val files = PiperFiles.find(spec.directory)
                ?: throw VoiceException(VoiceError.NO_VOICE, "No Piper voice in ${spec.directory}")
            native("load ${spec.modelId}") { createVoice(files) }.also { voice = spec to it }
        }
        native("synthesize") { loaded.generate(text, speed) }
    }

    override fun voiceDetector(modelPath: String, minSilenceSeconds: Float): VoiceDetector {
        val vad = native("load voice detector") { createVad(modelPath, minSilenceSeconds) }
        return object : VoiceDetector {
            override fun accept(samples: FloatArray) = vad.acceptWaveform(samples)

            override val isSpeaking: Boolean get() = vad.isSpeechDetected()

            override fun takeSegments(): List<FloatArray> {
                val segments = mutableListOf<FloatArray>()
                while (!vad.empty()) {
                    segments += vad.frontSamples()
                    vad.pop()
                }
                return segments
            }

            override fun flush() = vad.flush()

            override fun close() = vad.release()
        }
    }

    override suspend fun release() = withContext(dispatcher) {
        recognizer?.second?.release()
        recognizer = null
        voice?.second?.release()
        voice = null
    }

    /** Ошибки нативной библиотеки и нехватку памяти — в [VoiceError.ENGINE]. */
    private inline fun <T> native(action: String, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: VoiceException) {
        throw e
    } catch (e: Exception) {
        throw VoiceException(VoiceError.ENGINE, "Speech engine failed to $action: ${e.message}", e)
    } catch (e: LinkageError) {
        throw VoiceException(VoiceError.ENGINE, "Speech engine library is not available: ${e.message}", e)
    } catch (e: OutOfMemoryError) {
        throw VoiceException(VoiceError.ENGINE, "Not enough memory to $action", e)
    }

    protected companion object {
        /** Детектор речи: фрагменты не длиннее 20 с (Whisper распознаёт до 30 с за раз). */
        const val VAD_MAX_SPEECH_SECONDS = 20f
        const val VAD_MIN_SPEECH_SECONDS = 0.25f
        const val VAD_THRESHOLD = 0.5f
        const val VAD_WINDOW = 512
    }
}
