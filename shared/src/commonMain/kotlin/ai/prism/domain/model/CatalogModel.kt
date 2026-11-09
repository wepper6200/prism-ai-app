package ai.prism.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Формат диалога, в котором модель была обучена. */
@Serializable
enum class ChatFormat {
    @SerialName("gemma4")
    GEMMA4,

    @SerialName("gemma3")
    GEMMA3,

    /** Без разметки диалога: системный промпт и реплики подряд (тестовые модели). */
    @SerialName("raw")
    RAW,
}

@Serializable
data class ModelLicense(
    val name: String,
    val url: String,
)

/** Один скачиваемый файл модели: адрес, имя на устройстве, размер и SHA-256 (проверяются при загрузке). */
@Serializable
data class ModelFile(
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** Что делает модель. */
@Serializable
enum class ModelKind {
    /** Языковая модель для чата. */
    @SerialName("chat")
    CHAT,

    /** Генерация изображений по описанию (stable-diffusion.cpp). */
    @SerialName("image")
    IMAGE,
}

/**
 * Дополнительный файл модели изображений. [role] — назначение для stable-diffusion.cpp:
 * `t5xxl`, `clip_l`, `clip_g`, `vae`, `taesd`.
 */
@Serializable
data class ModelComponent(
    val role: String,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    val file: ModelFile get() = ModelFile(url, fileName, sizeBytes, sha256)
}

/** Параметры генерации по умолчанию для модели изображений. */
@Serializable
data class ImageModelSettings(
    val width: Int = 1024,
    val height: Int = 1024,
    val steps: Int = 4,
    /** Сила следования описанию (CFG); 1 — без отрицательного описания, у дистиллированных моделей. */
    val cfg: Float = 1.0f,
    /** Встроенная подсказка для дистиллированных моделей FLUX. */
    val guidance: Float = 3.5f,
    /** Алгоритм и расписание шагов (`euler`, `sgm_uniform`…); пусто — по умолчанию модели. */
    val sampler: String = "",
    val scheduler: String = "",
    /** Декодировать изображение по частям — меньше пиковой памяти (телефон). */
    val vaeTiling: Boolean = false,
    /** Отображать веса в память вместо копирования (меньше пиковой памяти). */
    val mmap: Boolean = false,
)

/** Модель из каталога `files/models.json`. */
@Serializable
data class CatalogModel(
    val id: String,
    val name: String,
    val family: String,
    val parameters: String,
    val quantization: String,
    /** Описание по коду языка: `ru`, позже `en`. */
    val description: Map<String, String>,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Контекст языковой модели в токенах (у моделей изображений не используется). */
    val contextLength: Int = 0,
    val minRamMb: Int,
    val chatFormat: ChatFormat = ChatFormat.RAW,
    val license: ModelLicense,
    /** `android` и/или `desktop` — где модель рекомендуется. */
    val recommendedFor: List<String> = emptyList(),
    /** Модуль зрения (проектор mmproj): с ним модель понимает изображения. `null` — только текст. */
    val vision: ModelFile? = null,
    val kind: ModelKind = ModelKind.CHAT,
    /** Платформы, где модель доступна: `android`, `desktop`. Пусто — везде. */
    val platforms: List<String> = emptyList(),
    /** Назначение основного файла модели изображений: `model` (целый чекпойнт) или `diffusion_model`. */
    val mainRole: String = "model",
    /** Остальные файлы модели изображений (текстовые энкодеры, VAE). */
    val components: List<ModelComponent> = emptyList(),
    val image: ImageModelSettings? = null,
) {
    /** Основной файл с весами модели. */
    val mainFile: ModelFile get() = ModelFile(url, fileName, sizeBytes, sha256)

    /** Все файлы модели в порядке загрузки. */
    val files: List<ModelFile> get() = listOfNotNull(mainFile, vision) + components.map { it.file }

    fun availableOn(platform: String): Boolean = platforms.isEmpty() || platform in platforms

    val totalSizeBytes: Long get() = files.sumOf { it.sizeBytes }

    val supportsImages: Boolean get() = vision != null
}

@Serializable
data class ModelCatalogFile(
    val version: Int,
    val models: List<CatalogModel>,
)

enum class DownloadError {
    NETWORK,
    NOT_ENOUGH_SPACE,
    CHECKSUM_MISMATCH,
    STORAGE,
    SERVER,
}

sealed interface ModelStatus {
    data object NotDownloaded : ModelStatus

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelStatus

    /** Файл скачан, идёт проверка контрольной суммы. */
    data object Verifying : ModelStatus

    /** Загрузка прервана (пользователем или ошибкой); её можно продолжить с места остановки. */
    data class Paused(val downloadedBytes: Long, val totalBytes: Long, val error: DownloadError?) : ModelStatus

    data object Downloaded : ModelStatus
}

/**
 * Модель каталога на устройстве.
 *
 * [status] описывает все файлы модели вместе (основной + модуль зрения), а [chatReady] — что основной файл
 * уже скачан: модель отвечает текстом, даже если модуль зрения ещё не докачан.
 */
data class ModelEntry(
    val model: CatalogModel,
    val status: ModelStatus,
    /** Хватает ли оперативной памяти устройства по требованиям модели. */
    val fitsInMemory: Boolean,
    val chatReady: Boolean = status == ModelStatus.Downloaded,
) {
    /** Модель понимает изображения: модуль зрения есть в каталоге и скачан. */
    val visionReady: Boolean get() = model.supportsImages && status == ModelStatus.Downloaded

    /** Модель уже работает с текстом, но модуль зрения ещё не скачан. */
    val visionMissing: Boolean get() = model.supportsImages && chatReady && status != ModelStatus.Downloaded
}
