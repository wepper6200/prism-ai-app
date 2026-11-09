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
    val contextLength: Int,
    val minRamMb: Int,
    val chatFormat: ChatFormat,
    val license: ModelLicense,
    /** `android` и/или `desktop` — где модель рекомендуется. */
    val recommendedFor: List<String> = emptyList(),
)

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

data class ModelEntry(
    val model: CatalogModel,
    val status: ModelStatus,
    /** Хватает ли оперативной памяти устройства по требованиям модели. */
    val fitsInMemory: Boolean,
)
