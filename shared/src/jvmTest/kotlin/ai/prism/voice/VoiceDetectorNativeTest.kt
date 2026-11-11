package ai.prism.voice

import ai.prism.domain.voice.SPEECH_SAMPLE_RATE
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Нативные библиотеки sherpa-onnx и onnxruntime загружаются из jar для этой ОС (в CI — Linux и Windows):
 * детектор речи PRISM_TEST_VAD не находит речи в тишине. Без модели тест пропускается.
 */
class VoiceDetectorNativeTest {

    @Test
    fun silenceHasNoSpeech() {
        val vad = System.getenv("PRISM_TEST_VAD")?.let(::File)?.takeIf { it.isFile }
        assumeTrue("не задан детектор речи PRISM_TEST_VAD", vad != null)
        val detector = DesktopSherpaSpeechEngine(threads = 1).voiceDetector(checkNotNull(vad).absolutePath, 0.5f)
        try {
            repeat(20) { detector.accept(FloatArray(SPEECH_SAMPLE_RATE / 10)) }
            detector.flush()
            assertFalse(detector.isSpeaking)
            assertTrue(detector.takeSegments().isEmpty())
        } finally {
            detector.close()
        }
    }
}
