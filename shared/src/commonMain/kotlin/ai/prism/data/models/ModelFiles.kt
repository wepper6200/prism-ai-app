package ai.prism.data.models

import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.DownloadError

/** Где лежат файлы моделей. */
interface ModelStorage {
    fun isDownloaded(model: CatalogModel): Boolean

    /** Сколько байт уже скачано в незавершённый файл. */
    fun partialBytes(model: CatalogModel): Long

    fun filePath(model: CatalogModel): String

    fun delete(model: CatalogModel)

    fun freeSpaceBytes(): Long
}

/** Скачивание модели с докачкой и проверкой SHA-256. */
interface ModelDownloader {
    /**
     * Скачивает модель (продолжает с места остановки), проверяет контрольную сумму
     * и переносит файл на место. Бросает [DownloadException].
     */
    suspend fun download(
        model: CatalogModel,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onVerifying: () -> Unit,
    )
}

class DownloadException(
    val error: DownloadError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
