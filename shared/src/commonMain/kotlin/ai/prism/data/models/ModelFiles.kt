package ai.prism.data.models

import ai.prism.domain.model.DownloadError
import ai.prism.domain.model.ModelFile

/** Где лежат файлы моделей. */
interface ModelStorage {
    fun isDownloaded(file: ModelFile): Boolean

    /** Сколько байт уже скачано в незавершённый файл. */
    fun partialBytes(file: ModelFile): Long

    fun filePath(file: ModelFile): String

    /**
     * Распаковывает скачанный архив (если он ещё не распакован этой версией) и возвращает путь к папке
     * с его содержимым. Долгая операция: вызывать не из главного потока. Бросает `IOException`.
     */
    fun unpackedPath(file: ModelFile): String

    /** Удаляет файл, его незавершённую часть и распакованное содержимое архива. */
    fun delete(file: ModelFile)

    fun freeSpaceBytes(): Long
}

/** Скачивание файла модели с докачкой и проверкой SHA-256. */
interface ModelDownloader {
    /**
     * Скачивает файл (продолжает с места остановки), проверяет контрольную сумму
     * и переносит его на место. Бросает [DownloadException].
     */
    suspend fun download(
        file: ModelFile,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onVerifying: () -> Unit,
    )
}

class DownloadException(
    val error: DownloadError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
