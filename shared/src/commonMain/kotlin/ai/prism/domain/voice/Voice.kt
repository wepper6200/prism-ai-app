package ai.prism.domain.voice

import ai.prism.domain.model.SpeechEngineType
import kotlinx.coroutines.flow.Flow

/** Частота, с которой записывается микрофон и работают распознавание и детектор речи. */
const val SPEECH_SAMPLE_RATE: Int = 16_000

/** Звук: отсчёты моно в диапазоне [-1, 1] и их частота. */
class SpeechAudio(val samples: FloatArray, val sampleRate: Int) {
    val durationSeconds: Float get() = samples.size.toFloat() / sampleRate
}

/** Модель распознавания: движок, файлы по назначению (`model`/`encoder`, `decoder`, `tokens`) и язык. */
data class RecognizerSpec(
    val modelId: String,
    val engine: SpeechEngineType,
    val files: Map<String, String>,
    /** Язык речи (`ru`) или пусто — модель определяет его сама. */
    val language: String = "",
)

/**
 * Голос: папка распакованного архива модели (Piper — `.onnx`, `tokens.txt` и `espeak-ng-data`; Supertonic — модели
 * ONNX, `tts.json`, `voice.bin`) в ней или во вложенной папке, номер говорящего, язык и шаги синтеза.
 */
data class VoiceSpec(
    val modelId: String,
    val directory: String,
    val engine: SpeechEngineType = SpeechEngineType.PIPER,
    val speaker: Int = 0,
    val language: String = "",
    /** Шагов синтеза (Supertonic); 0 — по умолчанию. */
    val steps: Int = 0,
    /** Обычная скорость модели; итоговая — она, умноженная на скорость из настроек. */
    val speed: Float = 1.0f,
)

/**
 * Детектор речи (Silero VAD) для записи с микрофона: принимает отсчёты [SPEECH_SAMPLE_RATE] и выдаёт
 * законченные фрагменты речи. Не потокобезопасен: работает в одной корутине записи.
 */
interface VoiceDetector {
    /** Принимает очередную порцию звука. */
    fun accept(samples: FloatArray)

    /** Сейчас звучит речь (фрагмент ещё не закончился). */
    val isSpeaking: Boolean

    /** Законченные с прошлого вызова фрагменты речи. */
    fun takeSegments(): List<FloatArray>

    /** Завершает текущий фрагмент речи, даже если пауза после него ещё не наступила. */
    fun flush()

    fun close()
}

/** Распознавание и синтез речи на устройстве (sherpa-onnx). Модели держит в памяти до [release]. */
interface SpeechEngine {
    /** Есть ли движок речи на этой платформе. */
    val isSupported: Boolean

    /** Распознаёт фрагмент речи. Бросает [VoiceException]. */
    suspend fun transcribe(spec: RecognizerSpec, audio: SpeechAudio): String

    /** Озвучивает текст голосом [spec]; [speed] — 1.0 обычная скорость. Бросает [VoiceException]. */
    suspend fun synthesize(spec: VoiceSpec, text: String, speed: Float): SpeechAudio

    /** Детектор речи по модели Silero VAD [modelPath]; [minSilenceSeconds] — пауза, которая завершает фразу. */
    fun voiceDetector(modelPath: String, minSilenceSeconds: Float): VoiceDetector

    /** Выгружает модели речи из памяти. */
    suspend fun release()

    /** Движка речи нет (тесты, неподдерживаемые платформы). */
    object None : SpeechEngine {
        override val isSupported: Boolean = false

        override suspend fun transcribe(spec: RecognizerSpec, audio: SpeechAudio): String =
            throw VoiceException(VoiceError.ENGINE, "Speech engine is not available")

        override suspend fun synthesize(spec: VoiceSpec, text: String, speed: Float): SpeechAudio =
            throw VoiceException(VoiceError.ENGINE, "Speech engine is not available")

        override fun voiceDetector(modelPath: String, minSilenceSeconds: Float): VoiceDetector =
            throw VoiceException(VoiceError.ENGINE, "Speech engine is not available")

        override suspend fun release() = Unit
    }
}

/** Микрофон. */
interface AudioInput {
    /**
     * Записывает звук моно [SPEECH_SAMPLE_RATE], пока поток собирается. Порции — примерно по 0,1 с.
     * Нет разрешения — [VoiceError.PERMISSION_DENIED], микрофон недоступен — [VoiceError.MICROPHONE].
     */
    fun record(): Flow<FloatArray>
}

/** Динамик. */
interface AudioOutput {
    /** Проигрывает звук и возвращается, когда он доиграл. Отмена корутины сразу останавливает звук. */
    suspend fun play(audio: SpeechAudio)
}

enum class VoiceError {
    /** Не скачана модель распознавания речи. */
    NO_RECOGNIZER,

    /** Не скачан голос для озвучки. */
    NO_VOICE,

    /** Нет разрешения на микрофон. */
    PERMISSION_DENIED,

    /** Микрофон не открылся (занят другим приложением или его нет). */
    MICROPHONE,

    /** Речь не услышана. */
    NOTHING_HEARD,

    /** Модель речи не загрузилась или упала (нехватка памяти, повреждённые файлы). */
    ENGINE,

    /** Не удалось воспроизвести звук. */
    PLAYBACK,
}

class VoiceException(
    val error: VoiceError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
