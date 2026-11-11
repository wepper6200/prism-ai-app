package ai.prism.voice

import ai.prism.data.models.FileModelStorage
import ai.prism.domain.model.ModelFile
import ai.prism.domain.model.SpeechEngineType
import ai.prism.domain.voice.RecognizerSpec
import ai.prism.domain.voice.SPEECH_SAMPLE_RATE
import ai.prism.domain.voice.SpeechAudio
import ai.prism.domain.voice.VoiceSpec
import ai.prism.service.voice.VoiceService
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Настоящие модели речи через sherpa-onnx (задание Desktop в CI): голос Piper из архива каталога
 * (PRISM_TEST_PIPER_ARCHIVE) произносит фразу, детектор речи (PRISM_TEST_VAD) находит её, а GigaAM
 * (PRISM_TEST_GIGAAM_DIR) и Whisper (PRISM_TEST_WHISPER_DIR) распознают. Без моделей тест пропускается.
 */
class SpeechIntegrationTest {

    private val engine = DesktopSherpaSpeechEngine(threads = 2)

    private fun env(name: String): File? = System.getenv(name)?.let(::File)?.takeIf { it.exists() }

    /** Архив голоса распаковывается так же, как после загрузки в приложении. */
    private fun voice(): VoiceSpec {
        val archive = env("PRISM_TEST_PIPER_ARCHIVE")
        assumeTrue("не задан голос PRISM_TEST_PIPER_ARCHIVE", archive != null)
        val source = checkNotNull(archive)
        val dir = Files.createTempDirectory("prism-voice").toFile()
        source.copyTo(File(dir, source.name))
        val file = ModelFile("https://example.com/${source.name}", source.name, source.length(), "0".repeat(64), archive = true)
        val unpacked = FileModelStorage(dir).unpackedPath(file)
        assertTrue(PiperFiles.find(unpacked) != null, "в архиве нет модели, tokens.txt и espeak-ng-data")
        return VoiceSpec("piper", unpacked)
    }

    private fun speak(text: String): SpeechAudio = runBlocking { engine.synthesize(voice(), text, 1.0f) }

    @Test
    fun piperSpeaksRussian() {
        val audio = speak(PHRASE)
        println("Piper: ${audio.samples.size} отсчётов, ${audio.sampleRate} Гц, ${audio.durationSeconds} с")
        assertTrue(audio.durationSeconds in 1.0f..10.0f, "длительность ${audio.durationSeconds} с")
        assertTrue(audio.samples.any { abs(it) > 0.05f }, "озвучка беззвучна")
    }

    @Test
    fun voiceDetectorFindsThePhraseInSilence() {
        val vad = env("PRISM_TEST_VAD")
        assumeTrue("не задан детектор речи PRISM_TEST_VAD", vad != null)
        val speech = resample(speak(PHRASE), SPEECH_SAMPLE_RATE)
        val silence = FloatArray(SPEECH_SAMPLE_RATE)
        val recording = silence + speech + silence + silence
        val detector = engine.voiceDetector(checkNotNull(vad).absolutePath, VoiceService.DICTATION_SILENCE_S)
        val segments = mutableListOf<FloatArray>()
        try {
            recording.asList().chunked(SPEECH_SAMPLE_RATE / 10).forEach { chunk ->
                detector.accept(chunk.toFloatArray())
                segments += detector.takeSegments()
            }
            detector.flush()
            segments += detector.takeSegments()
        } finally {
            detector.close()
        }
        val found = segments.sumOf { it.size }.toFloat() / SPEECH_SAMPLE_RATE
        println("VAD: ${segments.size} фрагм., ${found} с речи из ${speech.size.toFloat() / SPEECH_SAMPLE_RATE} с")
        assertTrue(segments.isNotEmpty(), "речь не найдена")
        assertTrue(found > 0.5f && found < recording.size.toFloat() / SPEECH_SAMPLE_RATE - 1.5f, "найдено $found с")
    }

    @Test
    fun gigaAmRecognizesPiper() {
        val dir = env("PRISM_TEST_GIGAAM_DIR")
        assumeTrue("не задана модель PRISM_TEST_GIGAAM_DIR", dir != null)
        val spec = RecognizerSpec(
            modelId = "gigaam",
            engine = SpeechEngineType.NEMO_CTC,
            files = mapOf("model" to File(dir, "model.int8.onnx").path, "tokens" to File(dir, "tokens.txt").path),
            language = "ru",
        )
        assertRecognized("GigaAM", spec)
    }

    @Test
    fun whisperRecognizesPiper() {
        val dir = env("PRISM_TEST_WHISPER_DIR")
        assumeTrue("не задана модель PRISM_TEST_WHISPER_DIR", dir != null)
        val spec = RecognizerSpec(
            modelId = "whisper",
            engine = SpeechEngineType.WHISPER,
            files = mapOf(
                "encoder" to File(dir, "small-encoder.int8.onnx").path,
                "decoder" to File(dir, "small-decoder.int8.onnx").path,
                "tokens" to File(dir, "small-tokens.txt").path,
            ),
        )
        assertRecognized("Whisper", spec)
    }

    private fun assertRecognized(name: String, spec: RecognizerSpec) = runBlocking {
        val audio = speak(PHRASE)
        val text = try {
            engine.transcribe(spec, audio)
        } finally {
            engine.release()
        }
        println("$name: «$text»")
        val words = text.lowercase()
        assertTrue("привет" in words && "погод" in words, "$name распознал «$text»")
    }

    /** Простая линейная интерполяция: голос Piper 22 050 Гц → 16 кГц для детектора речи. */
    private fun resample(audio: SpeechAudio, rate: Int): FloatArray {
        val ratio = audio.sampleRate.toDouble() / rate
        val size = (audio.samples.size / ratio).toInt()
        return FloatArray(size) { i ->
            val position = i * ratio
            val index = position.toInt().coerceAtMost(audio.samples.size - 2)
            val fraction = (position - index).toFloat()
            audio.samples[index] * (1 - fraction) + audio.samples[index + 1] * fraction
        }
    }

    private companion object {
        const val PHRASE = "Привет! Сегодня хорошая погода."
    }
}
