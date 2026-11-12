package ai.prism.imagegen

import ai.prism.domain.imagegen.ImageGenerationError
import ai.prism.domain.imagegen.ImageGenerationException
import ai.prism.domain.imagegen.ImageGenerationProgress
import ai.prism.domain.imagegen.ImageRequest
import ai.prism.testing.testImageModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Протокол обмена с программой генерации — на поддельной программе [FakeImageWorker]. */
class ProcessImageGeneratorTest {

    private val dir = Files.createTempDirectory("prism-imagegen").toFile()
    private val log = File(dir, "commands.log")
    private lateinit var generator: ProcessImageGenerator

    @BeforeTest
    fun setUp() {
        assumeFalse("скрипт запуска — только для Unix", System.getProperty("os.name").lowercase().contains("win"))
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val script = File(dir, "fake_imagegen").apply {
            writeText(
                "#!/bin/sh\nexec '$java' -cp '${System.getProperty("java.class.path")}' " +
                    "ai.prism.imagegen.FakeImageWorker '${log.absolutePath}'\n",
            )
            setExecutable(true)
        }
        generator = ProcessImageGenerator(executable = { script }, workDir = File(dir, "out"), threads = 2)
    }

    private val model = testImageModel()
    private val files = mapOf("diffusion_model" to "/models/flux.gguf", "vae" to "/models/ae.safetensors")

    private fun request(prompt: String = "a red square", steps: Int = 3) = ImageRequest(
        prompt = prompt, width = 8, height = 8, steps = steps, cfg = 1f, guidance = 3.5f,
        sampler = "euler", scheduler = "", vaeTiling = false, mmap = false,
    )

    @Test
    fun modelIsLoadedOnceAndStepsAreReported() = runBlocking {
        val events = mutableListOf<ImageGenerationProgress>()

        val first = generator.generate(model, files, request(), events::add)
        val second = generator.generate(model, files, request(), events::add)

        assertEquals(8, first.width)
        assertEquals(java.awt.Color.RED.rgb, ImageIO.read(first.png.inputStream()).getRGB(0, 0))
        assertEquals(7, second.seed)
        assertEquals(ImageGenerationProgress.Loading, events.first())
        assertEquals(1, events.count { it == ImageGenerationProgress.Loading }, "модель загружается один раз")
        assertEquals(ImageGenerationProgress.Step(3, 3), events.last())
        assertEquals(1, log.readLines().count { "\"load\"" in it })
        assertTrue(log.readLines().first().contains("/models/flux.gguf"))

        generator.release()
        assertTrue(log.readLines().last().contains("quit"))
    }

    @Test
    fun weightsAreCompressedOnceAndCompressionCanBeStopped() = runBlocking {
        val output = File(dir, "encoder.safetensors.q4_k.gguf")
        var started = 0

        generator.compressWeights("/models/encoder.safetensors", output.absolutePath, "q4_k") { started++ }
        generator.compressWeights("/models/encoder.safetensors", output.absolutePath, "q4_k") { started++ }

        assertTrue(output.isFile)
        assertEquals(1, started, "готовая копия не сжимается заново")
        val command = log.readLines().single { "\"convert\"" in it }
        assertTrue("\"type\":\"q4_k\"" in command && "/models/encoder.safetensors" in command)

        // Сжатие не прерывается командой — отмена завершает процесс.
        val slowOutput = File(dir, "slow.q4_k.gguf")
        val job = async(Dispatchers.Default) {
            runCatching { generator.compressWeights("/models/slow.safetensors", slowOutput.absolutePath, "q4_k") {} }
        }
        while (log.readLines().none { "slow.safetensors" in it }) Thread.sleep(20)
        generator.cancel()
        val error = job.await().exceptionOrNull() as ImageGenerationException
        assertEquals(ImageGenerationError.CANCELLED, error.error)
        assertTrue(!slowOutput.exists())

        // После отмены программа запускается снова.
        assertEquals(8, generator.generate(model, files, request()) {}.width)
    }

    @Test
    fun brokenModelIsReported() = runBlocking {
        val error = assertFailsWith<ImageGenerationException> {
            generator.generate(model, mapOf("diffusion_model" to "/models/broken.gguf"), request()) {}
        }
        assertEquals(ImageGenerationError.MODEL_CORRUPTED, error.error)
    }

    @Test
    fun killedWorkerMeansNotEnoughMemoryAndRestarts() = runBlocking {
        val error = assertFailsWith<ImageGenerationException> { generator.generate(model, files, request("crash")) {} }
        assertEquals(ImageGenerationError.NOT_ENOUGH_MEMORY, error.error)

        // Следующий запрос запускает программу заново.
        assertEquals(8, generator.generate(model, files, request()) {}.height)
    }

    @Test
    fun cancelStopsGeneration() = runBlocking {
        val running = async(Dispatchers.Default) {
            runCatching {
                generator.generate(model, files, request("slow", steps = 20)) { event ->
                    if (event is ImageGenerationProgress.Step && event.step == 1) generator.cancel()
                }
            }
        }
        val error = running.await().exceptionOrNull() as ImageGenerationException
        assertEquals(ImageGenerationError.CANCELLED, error.error)

        // Модель осталась загруженной: следующий рисунок без повторной загрузки.
        generator.generate(model, files, request()) {}
        assertEquals(1, log.readLines().count { "\"load\"" in it })
        generator.release()
    }
}
