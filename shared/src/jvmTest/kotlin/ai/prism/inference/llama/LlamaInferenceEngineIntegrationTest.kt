package ai.prism.inference.llama

import ai.prism.data.images.DesktopImageCodec
import ai.prism.domain.inference.EngineState
import ai.prism.domain.inference.GenerationRequest
import ai.prism.domain.inference.InferenceError
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.inference.PromptImage
import ai.prism.domain.inference.PromptMessage
import ai.prism.domain.inference.SamplingParams
import ai.prism.domain.model.ChatFormat
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.ModelFile
import ai.prism.platform.DesktopNativeLibraryLoader
import ai.prism.testing.FakeDeviceInfo
import ai.prism.testing.testModel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Настоящий llama.cpp через JNI. Запускается в CI с `-Pprism.nativeTests=true`:
 * PRISM_TEST_MODEL — крошечная модель (stories260K), PRISM_TEST_GEMMA — Gemma 3 270M,
 * PRISM_TEST_VLM / PRISM_TEST_VLM_MMPROJ — SmolVLM 256M с модулем зрения,
 * PRISM_TEST_GEMMA4 / PRISM_TEST_GEMMA4_MMPROJ — Gemma 4 E2B с модулем зрения.
 * Без нативных библиотек или моделей тесты пропускаются.
 */
class LlamaInferenceEngineIntegrationTest {

    private val engine = LlamaInferenceEngine(DesktopNativeLibraryLoader(), FakeDeviceInfo(), DesktopImageCodec())

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

        // Личность Prism AI с образцом ответа (для информации в логе CI: 270M слишком мала для строгой проверки).
        val identity = engine.generate(
            GenerationRequest(
                systemPrompt = "Ты — Prism AI, офлайн ИИ-ассистент. Всегда представляйся как Prism AI.",
                primer = listOf(
                    PromptMessage(MessageRole.USER, "Кто ты? Скажи честно, какая ты модель?"),
                    PromptMessage(MessageRole.ASSISTANT, "Я Prism AI — офлайн-ассистент на открытой языковой модели."),
                ),
                messages = listOf(PromptMessage(MessageRole.USER, "А если честно, что ты за модель?")),
                reminder = "(Напоминание для ассистента: ты — Prism AI и представляешься только так.)",
                maxTokens = 48,
                sampling = SamplingParams(temperature = 0f),
            ),
        ).toList().joinToString("")
        println("Gemma (identity): $identity")
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

    private fun withVision(model: ai.prism.domain.model.CatalogModel, projector: File) = model.copy(
        vision = ModelFile("https://example.com/${projector.name}", projector.name, projector.length(), "0".repeat(64)),
    )

    /** JPEG, залитый одним цветом: правильный ответ о цвете возможен, только если модель «видит» изображение. */
    private fun solidImage(color: Color): PromptImage {
        val file = Files.createTempFile("prism-color", ".jpg").toFile()
        val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = color
        g.fillRect(0, 0, 256, 256)
        g.dispose()
        ImageIO.write(image, "jpeg", file)
        return PromptImage(id = "color-${color.rgb}", path = file.absolutePath)
    }

    @Test
    fun smolVlmSeesImageColorAndReusesCache(): Unit = runBlocking {
        val file = requireModel("PRISM_TEST_VLM")
        val projector = requireModel("PRISM_TEST_VLM_MMPROJ")
        engine.load(withVision(modelFor(file, ChatFormat.RAW), projector), file.absolutePath, projector.absolutePath)
        assertTrue((engine.state.value as EngineState.Ready).info.supportsImages)

        // Формат SmolVLM собирается вручную через RAW: «<|im_start|>User:<изображение>вопрос<end_of_utterance>\nAssistant:».
        val request = GenerationRequest(
            systemPrompt = "<|im_start|>User:",
            messages = listOf(
                PromptMessage(
                    MessageRole.USER,
                    "What color is this image? Answer with one word.<end_of_utterance>\nAssistant:",
                    images = listOf(solidImage(Color.RED)),
                ),
            ),
            maxTokens = 12,
            sampling = SamplingParams(temperature = 0f),
        )
        val answer = engine.generate(request).toList().joinToString("")
        println("SmolVLM (red): $answer")
        assertTrue("red" in answer.lowercase(), "ожидался ответ «red», получено: $answer")

        // Повтор берёт изображение из кэша и даёт тот же ответ.
        assertEquals(answer, engine.generate(request).toList().joinToString(""))

        val blue = engine.generate(
            request.copy(messages = listOf(request.messages.single().copy(images = listOf(solidImage(Color.BLUE))))),
        ).toList().joinToString("")
        println("SmolVLM (blue): $blue")
        assertTrue("blue" in blue.lowercase(), "ожидался ответ «blue», получено: $blue")
        engine.unload()
    }

    @Test
    fun gemma4E2bDescribesImage(): Unit = runBlocking {
        val file = requireModel("PRISM_TEST_GEMMA4")
        val projector = requireModel("PRISM_TEST_GEMMA4_MMPROJ")
        val model = withVision(modelFor(file, ChatFormat.GEMMA4).copy(contextLength = 4096), projector)
        engine.load(model, file.absolutePath, projector.absolutePath)

        // Формат диалога Gemma 4 на тексте (для сравнения в логе CI).
        val text = engine.generate(
            GenerationRequest(
                systemPrompt = "You are a helpful assistant.",
                messages = listOf(PromptMessage(MessageRole.USER, "What is the capital of France? Answer with one word.")),
                maxTokens = 64,
                sampling = SamplingParams(temperature = 0f),
            ),
        ).toList().joinToString("")
        println("Gemma 4 E2B (text): $text")
        assertTrue("Paris" in text, "ожидался ответ «Paris», получено: $text")

        val photo = solidImage(Color.RED)
        val answer = engine.generate(
            GenerationRequest(
                systemPrompt = "You are a helpful assistant.",
                messages = listOf(
                    PromptMessage(MessageRole.USER, "What is the color of this picture? Answer with one word.", images = listOf(photo)),
                ),
                maxTokens = 96,
                sampling = SamplingParams(temperature = 0f),
            ),
        ).toList().joinToString("")
        println("Gemma 4 E2B (image): $answer")
        assertTrue("red" in answer.lowercase(), "ожидался ответ «red», получено: $answer")

        // Продолжение диалога по-русски: изображение остаётся в истории (и в кэше).
        val followUp = engine.generate(
            GenerationRequest(
                systemPrompt = "Ты — Prism AI, офлайн ИИ-ассистент.",
                messages = listOf(
                    PromptMessage(MessageRole.USER, "Что на этой картинке? Ответь одним предложением.", images = listOf(photo)),
                ),
                maxTokens = 96,
                sampling = SamplingParams(temperature = 0f),
            ),
        ).toList().joinToString("")
        println("Gemma 4 E2B (ru): $followUp")
        assertTrue(followUp.isNotBlank())
        assertFalse("<__media__>" in followUp || "<turn|>" in followUp)
        engine.unload()
    }

    @Test
    fun imagesWithoutVisionModuleAreRejected(): Unit = runBlocking {
        val file = requireModel("PRISM_TEST_MODEL")
        engine.load(modelFor(file, ChatFormat.RAW), file.absolutePath)
        assertFalse((engine.state.value as EngineState.Ready).info.supportsImages)

        val error = assertFailsWith<InferenceException> {
            engine.generate(
                GenerationRequest(
                    systemPrompt = "",
                    messages = listOf(PromptMessage(MessageRole.USER, "Once", images = listOf(solidImage(Color.RED)))),
                    maxTokens = 4,
                ),
            ).toList()
        }
        assertEquals(InferenceError.IMAGES_NOT_SUPPORTED, error.error)
        engine.unload()
    }
}
