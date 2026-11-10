package ai.prism.imagegen

import ai.prism.core.MjpegAvi
import ai.prism.domain.imagegen.ImageGenerationProgress
import ai.prism.domain.imagegen.VideoRequest
import ai.prism.domain.model.ModelKind
import ai.prism.platform.DesktopNativeLibraryLoader
import ai.prism.testing.testImageModel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Настоящее видео: Wan 2.1 T2V 1.3B (модель для телефонов) на процессоре, маленький ролик.
 * PRISM_TEST_WAN_DIR — каталог с файлами модели из каталога. Без программы или модели тест пропускается.
 * Ролик сохраняется рядом со скриншотами UI-тестов.
 */
class VideoGenerationIntegrationTest {

    private val dir = Files.createTempDirectory("prism-video-it").toFile()
    private val screenshots = File(System.getProperty("prism.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    @Test
    fun wanMakesRedVideo(): Unit = runBlocking {
        assumeTrue("нет нативных библиотек", System.getProperty("prism.native.dir") != null)
        val exe = DesktopNativeLibraryLoader.findImageGenerator()
        assumeTrue("программа генерации не собрана", exe != null)
        val wan = System.getenv("PRISM_TEST_WAN_DIR")?.let(::File)?.takeIf { it.isDirectory }
        assumeTrue("нет Wan 2.1 1.3B", wan != null)
        val generator = ProcessImageGenerator(executable = { exe }, workDir = File(dir, "out"), threads = Runtime.getRuntime().availableProcessors())
        val files = mapOf(
            "diffusion_model" to File(wan, "Wan2.1-T2V-1.3B-Q4_K_M.gguf").absolutePath,
            "t5xxl" to File(wan, "umt5-xxl-encoder-Q3_K_S.gguf").absolutePath,
            "vae" to File(wan, "wan_2.1_vae.safetensors").absolutePath,
        )
        val steps = mutableListOf<Int>()
        val started = System.nanoTime()

        val video = generator.generateVideo(
            testImageModel(id = "wan-it").copy(kind = ModelKind.VIDEO),
            files,
            VideoRequest(
                prompt = "a plain bright red background, flat solid red color, nothing moves",
                width = 256, height = 256, frames = 5, fps = 16, steps = 4, cfg = 6f, flowShift = 3f,
                sampler = "euler", scheduler = "", vaeTiling = true, mmap = true, offloadToCpu = false,
                flashAttention = true, seed = 42,
            ),
        ) { if (it is ImageGenerationProgress.Step) steps += it.step }

        val seconds = (System.nanoTime() - started) / 1e9
        File(screenshots, "generated_wan_video.avi").writeBytes(video.avi)
        val parsed = checkNotNull(MjpegAvi.parse(video.avi), "ролик не читается как MJPEG AVI")
        val first = ImageIO.read(parsed.frames.first().inputStream())
        ImageIO.write(first, "png", File(screenshots, "generated_wan_first_frame.png"))
        var r = 0L
        var g = 0L
        var b = 0L
        for (y in 0 until first.height) for (x in 0 until first.width) {
            val p = first.getRGB(x, y)
            r += p shr 16 and 0xFF
            g += p shr 8 and 0xFF
            b += p and 0xFF
        }
        val n = (first.width * first.height).toDouble()
        println("Wan 2.1 1.3B 256×256, ${parsed.frames.size} кадров: ${"%.1f".format(seconds)} с, средний цвет R=%.0f G=%.0f B=%.0f".format(r / n, g / n, b / n))
        assertEquals(256, video.width)
        assertEquals(video.frames, parsed.frames.size)
        assertTrue(parsed.frames.size >= 5, "кадров: ${parsed.frames.size}")
        assertEquals(listOf(0, 1, 2, 3, 4), steps.distinct().sorted())
        assertTrue(r / n > 110 && r / n > g / n + 25 && r / n > b / n + 25, "первый кадр не красный")
        generator.release()
    }
}
