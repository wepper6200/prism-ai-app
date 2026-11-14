package ai.prism.voice

import ai.prism.core.MjpegAvi
import ai.prism.domain.model.SpeechEngineType
import ai.prism.domain.voice.RecognizerSpec
import ai.prism.domain.voice.SpeechAudio
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Проверяет произнесённые слова в настоящем треке, а не только наличие звука в WAV. */
class RussianMusicIntegrationTest {
    @Test
    fun generatedSongContainsRussianLyrics() = runBlocking {
        val path = System.getenv("PRISM_TEST_RUSSIAN_MUSIC")
        val models = System.getenv("PRISM_TEST_GIGAAM_DIR")
        assumeTrue("не заданы русский трек и GigaAM", path != null && models != null)
        val audio = assertNotNull(MjpegAvi.parseWav(File(checkNotNull(path)).readBytes()))
        val engine = DesktopSherpaSpeechEngine(threads = 2)
        val heard = try {
            engine.transcribe(
                RecognizerSpec("gigaam", SpeechEngineType.NEMO_CTC,
                    mapOf("model" to File(models, "model.int8.onnx").path, "tokens" to File(models, "tokens.txt").path), language = "ru"),
                SpeechAudio(audio.monoSamples(), audio.sampleRate),
            )
        } finally { engine.release() }
        println("Русский музыкальный вокал: «$heard»")
        File(path).resolveSibling("russian-vocals.txt").writeText(heard)
        val words = heard.lowercase()
        val matches = listOf("тихий", "вечер", "окном", "тобой", "домой", "звезда", "дорог", "рядом")
            .count { it in words }
        assertTrue(matches >= 3, "Не удалось подтвердить русский текст песни: «$heard»")
    }
}
