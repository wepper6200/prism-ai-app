package ai.prism.data.catalog

import ai.prism.domain.model.ChatFormat
import ai.prism.domain.model.ModelKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Проверяет встроенный каталог `files/models.json`. */
class ModelCatalogTest {

    private val models = runBlocking { ModelCatalog().load() }

    @Test
    fun catalogIsValid() {
        assertTrue(models.isNotEmpty())
        assertEquals(models.size, models.map { it.id }.toSet().size, "id моделей должны быть уникальны")
        val files = models.flatMap { it.files }
        assertEquals(files.size, files.map { it.fileName }.toSet().size, "имена файлов должны быть уникальны")
        files.forEach { file ->
            // Компоненты моделей изображений хранятся под своими именами (у разных репозиториев они совпадают).
            assertTrue(file.url.startsWith("https://") && file.url.substringAfterLast('.') == file.fileName.substringAfterLast('.'), file.fileName)
            assertTrue(file.sha256.matches(Regex("[0-9a-f]{64}")) && file.sizeBytes > 0, file.fileName)
        }
        models.forEach { model ->
            assertTrue(model.url.startsWith("https://") && model.url.endsWith("/" + model.fileName), model.id)
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")), "sha256 ${model.id}")
            assertTrue(model.sizeBytes > 0 && model.minRamMb > 0, model.id)
            if (model.kind == ModelKind.CHAT) assertTrue(model.contextLength >= 2048, model.id)
            assertTrue(!model.description["ru"].isNullOrBlank(), "описание ${model.id}")
            assertTrue(model.license.url.startsWith("https://"), "лицензия ${model.id}")
        }
    }

    /** Все модели Gemma 4 понимают изображения: у каждой есть модуль зрения из того же репозитория. */
    @Test
    fun gemma4ModelsHaveVisionModules() {
        models.filter { it.chatFormat == ChatFormat.GEMMA4 }.forEach { model ->
            val vision = model.vision
            assertTrue(vision != null && vision.fileName.startsWith("mmproj-"), model.id)
            assertEquals(model.url.substringBeforeLast('/'), vision.url.substringBeforeLast('/'), model.id)
        }
    }

    /** На каждой платформе есть ровно одна модель рисования со всеми нужными stable-diffusion.cpp файлами. */
    @Test
    fun imageModelsCoverBothPlatforms() {
        for (platform in listOf("android", "desktop")) {
            val image = models.filter { it.kind == ModelKind.IMAGE && it.availableOn(platform) }
            assertEquals(1, image.size, platform)
            val model = image.single()
            assertTrue(model.image != null && model.mainRole in setOf("model", "diffusion_model"), model.id)
            assertTrue(model.components.all { it.role in setOf("t5xxl", "clip_l", "clip_g", "vae", "taesd") }, model.id)
        }
        val flux = models.single { it.id.startsWith("flux1-schnell") }
        assertEquals(setOf("t5xxl", "clip_l", "vae"), flux.components.map { it.role }.toSet())
    }

    @Test
    fun hasGemma4ModelForPhones() {
        assertTrue(models.any { it.chatFormat == ChatFormat.GEMMA4 && "android" in it.recommendedFor && it.minRamMb <= 8192 })
    }
}
