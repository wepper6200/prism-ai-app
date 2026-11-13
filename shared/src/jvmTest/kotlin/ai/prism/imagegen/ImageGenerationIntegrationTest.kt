package ai.prism.imagegen

import ai.prism.domain.imagegen.ImageGenerationError
import ai.prism.domain.imagegen.ImageGenerationException
import ai.prism.domain.imagegen.ImageGenerationProgress
import ai.prism.domain.imagegen.ImageRequest
import ai.prism.domain.model.ModelComponent
import ai.prism.platform.DesktopNativeLibraryLoader
import ai.prism.testing.testImageModel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Настоящая программа генерации (stable-diffusion.cpp). Запускается в CI с `-Pprism.nativeTests=true -Pprism.imagegen=true`:
 * PRISM_TEST_SDXL / PRISM_TEST_SDXL_TAESD — SDXL-Lightning (телефонная модель),
 * PRISM_TEST_FLUX_DIR — каталог с файлами FLUX.1 [schnell] Q4. Без программы или моделей тесты пропускаются.
 * Нарисованные картинки сохраняются рядом со скриншотами UI-тестов.
 */
class ImageGenerationIntegrationTest {

    private val dir = Files.createTempDirectory("prism-imagegen-it").toFile()
    private val screenshots = File(System.getProperty("prism.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    private fun generator(): ProcessImageGenerator {
        assumeTrue("нет нативных библиотек", System.getProperty("prism.native.dir") != null)
        val exe = DesktopNativeLibraryLoader.findImageGenerator()
        assumeTrue("программа генерации не собрана", exe != null)
        return ProcessImageGenerator(executable = { exe }, workDir = File(dir, "out"), threads = Runtime.getRuntime().availableProcessors())
    }

    private fun env(name: String): File? = System.getenv(name)?.let(::File)?.takeIf { it.exists() }

    /** Доля «красноты» картинки: средние каналы R, G, B. */
    private fun meanRgb(png: ByteArray): Triple<Double, Double, Double> {
        val image = ImageIO.read(png.inputStream())
        var r = 0L
        var g = 0L
        var b = 0L
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = image.getRGB(x, y)
            r += p shr 16 and 0xFF
            g += p shr 8 and 0xFF
            b += p and 0xFF
        }
        val n = (image.width * image.height).toDouble()
        return Triple(r / n, g / n, b / n)
    }

    private fun request(width: Int, steps: Int, scheduler: String = "", vaeTiling: Boolean = false) = ImageRequest(
        prompt = "a plain bright red background, flat solid red color, nothing else",
        width = width, height = width, steps = steps, cfg = 1f, guidance = 3.5f,
        sampler = "euler", scheduler = scheduler, vaeTiling = vaeTiling, mmap = vaeTiling, seed = 42,
    )

    /** Программа запускается (на Windows — со всеми DLL) и сообщает о повреждённой модели, а не падает. */
    @Test
    fun workerStartsAndRejectsBrokenModel(): Unit = runBlocking {
        val generator = generator()
        val broken = File(dir, "broken.gguf").apply { writeBytes(ByteArray(4096) { 7 }) }
        val error = assertFailsWith<ImageGenerationException> {
            generator.generate(testImageModel(), mapOf("model" to broken.absolutePath), request(64, 1)) {}
        }
        assertEquals(ImageGenerationError.MODEL_CORRUPTED, error.error, error.message)
        generator.release()
    }

    @Test
    fun sdxlLightningDrawsRedPicture(): Unit = runBlocking {
        val generator = generator()
        val model = env("PRISM_TEST_SDXL")
        val taesd = env("PRISM_TEST_SDXL_TAESD")
        assumeTrue("нет SDXL-Lightning", model != null && taesd != null)
        val files = mapOf("model" to model!!.absolutePath, "taesd" to taesd!!.absolutePath)
        val steps = mutableListOf<Int>()
        val started = System.nanoTime()

        val image = generator.generate(
            testImageModel().copy(mainRole = "model", components = listOf(ModelComponent("taesd", "", "taesd", 0, ""))),
            files,
            request(width = 512, steps = 4, scheduler = "sgm_uniform", vaeTiling = true),
        ) { if (it is ImageGenerationProgress.Step) steps += it.step }

        val seconds = (System.nanoTime() - started) / 1e9
        File(screenshots, "generated_sdxl_lightning.png").writeBytes(image.png)
        val (r, g, b) = meanRgb(image.png)
        println("SDXL-Lightning 512×512, 4 шага: ${"%.1f".format(seconds)} с, средний цвет R=%.0f G=%.0f B=%.0f".format(r, g, b))
        assertEquals(512, image.width)
        assertEquals(listOf(0, 1, 2, 3, 4), steps.distinct().sorted(), "шаги семплирования без тайлов декодера")
        assertTrue(r > 120 && r > g + 25 && r > b + 25, "картинка не красная: R=$r G=$g B=$b")
        generator.release()
    }

    /** Перерисовка: при слабой силе изменения синий исходник остаётся синим, хотя описание просит красный фон. */
    @Test
    fun sdxlLightningKeepsSourcePhotoAtLowStrength(): Unit = runBlocking {
        val generator = generator()
        val model = env("PRISM_TEST_SDXL")
        val taesd = env("PRISM_TEST_SDXL_TAESD")
        assumeTrue("нет SDXL-Lightning", model != null && taesd != null)
        val source = File(dir, "blue.png")
        val blue = java.awt.image.BufferedImage(640, 480, java.awt.image.BufferedImage.TYPE_INT_RGB)
        blue.createGraphics().apply {
            color = java.awt.Color(30, 60, 220)
            fillRect(0, 0, 640, 480)
            dispose()
        }
        ImageIO.write(blue, "png", source)

        val image = generator.generate(
            testImageModel().copy(mainRole = "model"),
            mapOf("model" to model!!.absolutePath, "taesd" to taesd!!.absolutePath),
            request(width = 512, steps = 4, scheduler = "sgm_uniform", vaeTiling = true)
                .copy(width = 512, height = 384, initImagePath = source.absolutePath, strength = 0.35f),
        ) {}

        File(screenshots, "generated_sdxl_img2img.png").writeBytes(image.png)
        val (r, g, b) = meanRgb(image.png)
        println("SDXL-Lightning перерисовка 512×384: средний цвет R=%.0f G=%.0f B=%.0f".format(r, g, b))
        assertEquals(384, image.height)
        assertTrue(b > r + 40, "исходное синее фото не учтено: R=$r G=$g B=$b")
        generator.release()
    }

    @Test
    fun fluxSchnellDrawsRedPicture(): Unit = runBlocking {
        val generator = generator()
        val flux = env("PRISM_TEST_FLUX_DIR")
        assumeTrue("нет FLUX.1 [schnell]", flux != null)
        val files = mapOf(
            "diffusion_model" to File(flux, "prism-image-0.5-q4_0.gguf").absolutePath,
            "t5xxl" to File(flux, "prism-image-0.5-t5xxl-q4_0.gguf").absolutePath,
            "clip_l" to File(flux, "prism-image-0.5-clip_l-q8_0.gguf").absolutePath,
            "vae" to File(flux, "prism-image-0.5-vae.safetensors").absolutePath,
        )
        val started = System.nanoTime()

        val image = generator.generate(testImageModel(), files, request(width = 256, steps = 2)) {}

        val seconds = (System.nanoTime() - started) / 1e9
        File(screenshots, "generated_flux_schnell.png").writeBytes(image.png)
        val (r, g, b) = meanRgb(image.png)
        println("FLUX.1 schnell 256×256, 2 шага: ${"%.1f".format(seconds)} с, средний цвет R=%.0f G=%.0f B=%.0f".format(r, g, b))
        assertTrue(r > 120 && r > g + 25 && r > b + 25, "картинка не красная: R=$r G=$g B=$b")
        generator.release()
    }
}
