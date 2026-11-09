package ai.prism.domain.imagegen

import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.ModelFile

/**
 * Локальная генерация изображений по текстовому описанию (stable-diffusion.cpp в отдельном процессе).
 * Модель держится в памяти между запросами, пока не вызван [release].
 */
interface ImageGenerator {
    /** На этой платформе есть программа генерации. */
    val isSupported: Boolean

    /**
     * Создаёт изображение и возвращает байты PNG. Загружает [model] (файлы — [files]: назначение → путь),
     * если загружена другая. Бросает [ImageGenerationException]; отмена через [cancel] — [ImageGenerationError.CANCELLED].
     */
    suspend fun generate(
        model: CatalogModel,
        files: Map<String, String>,
        request: ImageRequest,
        onProgress: (ImageGenerationProgress) -> Unit,
    ): GeneratedImage

    /** Останавливает текущую генерацию (из любого потока). */
    fun cancel()

    /** Выгружает модель и завершает процесс генерации — освобождает память для чата. */
    suspend fun release()
}

data class ImageRequest(
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfg: Float,
    val guidance: Float,
    val sampler: String,
    val scheduler: String,
    val vaeTiling: Boolean,
    val mmap: Boolean,
    /** −1 — случайное зерно. */
    val seed: Long = -1,
)

sealed interface ImageGenerationProgress {
    /** Модель загружается в память. */
    data object Loading : ImageGenerationProgress

    data class Step(val step: Int, val steps: Int) : ImageGenerationProgress
}

class GeneratedImage(val png: ByteArray, val width: Int, val height: Int, val seed: Long)

enum class ImageGenerationError {
    /** Нет программы генерации на этой платформе или она не запускается. */
    NOT_SUPPORTED,

    /** Программа не запустилась из-за отсутствия драйвера видеокарты с Vulkan (Windows). */
    GPU_DRIVER_MISSING,
    NOT_ENOUGH_MEMORY,
    MODEL_CORRUPTED,
    CANCELLED,
    FAILED,
}

class ImageGenerationException(
    val error: ImageGenerationError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Пути к файлам скачанной модели изображений по назначению: `model`/`diffusion_model`, `t5xxl`, `clip_l`… */
fun resolveImageModelFiles(model: CatalogModel, pathOf: (ModelFile) -> String?): Map<String, String>? {
    val main = pathOf(model.mainFile) ?: return null
    val result = mutableMapOf(model.mainRole to main)
    for (component in model.components) {
        result[component.role] = pathOf(component.file) ?: return null
    }
    return result
}
