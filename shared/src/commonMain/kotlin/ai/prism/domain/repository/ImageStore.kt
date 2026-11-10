package ai.prism.domain.repository

import ai.prism.domain.model.ImageAttachment

/**
 * Изображения, приложенные к сообщениям. Хранятся только на устройстве — в каталоге приложения —
 * уменьшенными до [MAX_SIDE] пикселей по большей стороне и повёрнутыми по EXIF.
 */
interface ImageStore {
    /**
     * Сохраняет изображение из байтов файла (JPEG, PNG, WebP, HEIC… — что умеет платформа).
     * Бросает [ImageException], если формат не поддерживается или файл повреждён.
     */
    suspend fun save(bytes: ByteArray): ImageAttachment

    /** Сохраняет видео (MJPEG AVI) как вложение сообщения: файл `….avi` рядом с изображениями. */
    suspend fun saveVideo(bytes: ByteArray, width: Int, height: Int): ImageAttachment

    /** Абсолютный путь к файлу изображения. */
    fun path(fileName: String): String

    /** Байты сохранённого JPEG для показа в интерфейсе или `null`, если файла нет. */
    suspend fun read(fileName: String): ByteArray?

    suspend fun delete(fileNames: Collection<String>)

    /** Удаляет файлы, на которые ничего не ссылается и которые старше [olderThanMillis] (брошенные черновики). */
    suspend fun deleteOrphans(referenced: Set<String>, olderThanMillis: Long)

    companion object {
        /** Достаточно для моделей каталога (Gemma 3 — 896 пикселей, Gemma 4 — до ~800 при 280 токенах). */
        const val MAX_SIDE = 1024
    }
}

class ImageException(message: String, cause: Throwable? = null) : Exception(message, cause)
