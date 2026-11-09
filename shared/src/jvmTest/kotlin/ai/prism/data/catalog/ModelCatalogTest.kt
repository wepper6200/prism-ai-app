package ai.prism.data.catalog

import ai.prism.domain.model.ChatFormat
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
            assertTrue(file.url.startsWith("https://") && file.url.endsWith("/" + file.fileName), file.fileName)
            assertTrue(file.sha256.matches(Regex("[0-9a-f]{64}")) && file.sizeBytes > 0, file.fileName)
        }
        models.forEach { model ->
            assertTrue(model.url.startsWith("https://") && model.url.endsWith("/" + model.fileName), model.id)
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")), "sha256 ${model.id}")
            assertTrue(model.sizeBytes > 0 && model.contextLength >= 2048 && model.minRamMb > 0, model.id)
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

    @Test
    fun hasGemma4ModelForPhones() {
        assertTrue(models.any { it.chatFormat == ChatFormat.GEMMA4 && "android" in it.recommendedFor && it.minRamMb <= 8192 })
    }
}
