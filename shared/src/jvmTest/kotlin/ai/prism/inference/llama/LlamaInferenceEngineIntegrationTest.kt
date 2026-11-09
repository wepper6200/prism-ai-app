package ai.prism.inference.llama

import ai.prism.domain.inference.EngineState
import ai.prism.domain.inference.GenerationRequest
import ai.prism.domain.inference.InferenceError
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.inference.PromptMessage
import ai.prism.domain.inference.SamplingParams
import ai.prism.domain.model.ChatFormat
import ai.prism.domain.model.MessageRole
import ai.prism.platform.DesktopNativeLibraryLoader
import ai.prism.testing.FakeDeviceInfo
import ai.prism.testing.testModel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Настоящий llama.cpp через JNI. Запускается в CI с `-Pprism.nativeTests=true`:
 * PRISM_TEST_MODEL — крошечная модель (stories260K), PRISM_TEST_GEMMA — Gemma 3 270M.
 * Без нативных библиотек или моделей тесты пропускаются.
 */
class LlamaInferenceEngineIntegrationTest {

    private val engine = LlamaInferenceEngine(DesktopNativeLibraryLoader(), FakeDeviceInfo())

    private fun requireModel(variable: String): File {
        assumeTrue("нет нативных библиотек", System.getProperty("prism.native.dir") != null)
        val file = System.getenv(variable)?.let(::File)?.takeIf { it.isFile }
        assumeTrue("не задана модель $variable", file != null)
        return checkNotNull(file)
    }

    private fun modelFor(file: File, format: ChatFormat) = testModel().copy(
        id = file.nameWithoutExtension,
        fileName = file.name,
        sizeBytes = file.length(),
        contextLength = 2048,
        minRamMb = 1,
        chatFormat = format,
    )

    @Test
    fun tinyModelGeneratesTextAndStops(): Unit = runBlocking {
        val file = requireModel("PRISM_TEST_MODEL")
        engine.load(modelFor(file, ChatFormat.RAW), file.absolutePath)
        assertIs<EngineState.Ready>(engine.state.value)

        val request = GenerationRequest(
            systemPrompt = "",
            messages = listOf(PromptMessage(MessageRole.USER, "Once upon a time")),
            maxTokens = 32,
            sampling = SamplingParams(temperature = 0f),
        )
        val first = engine.generate(request).toList().joinToString("")
        assertTrue(first.isNotBlank(), "модель ничего не ответила")

        // Повтор с тем же промптом переиспользует кэш и при жадном сэмплинге даёт тот же текст.
        val second = engine.generate(request).toList().joinToString("")
        assertEquals(first, second)

        // Сборщик забрал один фрагмент и отменил поток — генерация останавливается,
        // а движок остаётся готовым к следующему запросу.
        val firstPiece = engine.generate(request.copy(maxTokens = 400)).take(1).toList()
        assertEquals(1, firstPiece.size)
        val afterCancel = engine.generate(request).toList().joinToString("")
        assertEquals(first, afterCancel)

        engine.unload()
        assertIs<EngineState.Idle>(engine.state.value)
    }

    @Test
    fun gemmaAnswersInRussianWithoutControlTokens(): Unit = runBlocking {
        val file = requireModel("PRISM_TEST_GEMMA")
        engine.load(modelFor(file, ChatFormat.GEMMA3), file.absolutePath)

        val answer = engine.generate(
            GenerationRequest(
                systemPrompt = "Ты — Prism AI, офлайн ИИ-ассистент.",
                messages = listOf(PromptMessage(MessageRole.USER, "Привет! Ответь одним предложением.")),
                maxTokens = 64,
                sampling = SamplingParams(temperature = 0f),
            ),
        ).toList().joinToString("")

        println("Gemma: $answer")
        assertTrue(answer.isNotBlank())
        assertFalse("<start_of_turn>" in answer || "<end_of_turn>" in answer)
        engine.unload()
    }

    /** Правильный ответ на фактический вопрос возможен, только если формат диалога Gemma собран верно. */
    @Test
    fun gemmaFollowsChatFormat(): Unit = runBlocking {
        val file = requireModel("PRISM_TEST_GEMMA")
        engine.load(modelFor(file, ChatFormat.GEMMA3), file.absolutePath)

        val answer = engine.generate(
            GenerationRequest(
                systemPrompt = "You are a helpful assistant.",
                messages = listOf(PromptMessage(MessageRole.USER, "What is the capital of France? Answer with one word.")),
                maxTokens = 16,
                sampling = SamplingParams(temperature = 0f),
            ),
        ).toList().joinToString("")

        println("Gemma (format check): $answer")
        assertTrue("Paris" in answer, "ожидался ответ «Paris», получено: $answer")
        engine.unload()
    }

    @Test
    fun corruptedModelFileIsReported(): Unit = runBlocking {
        assumeTrue("нет нативных библиотек", System.getProperty("prism.native.dir") != null)
        val file = Files.createTempFile("broken", ".gguf").toFile().apply { writeBytes(ByteArray(4096) { 7 }) }

        val error = assertFailsWith<InferenceException> {
            engine.load(modelFor(file, ChatFormat.RAW), file.absolutePath)
        }

        assertEquals(InferenceError.MODEL_CORRUPTED, error.error)
        assertIs<EngineState.Failed>(engine.state.value)
    }
}
