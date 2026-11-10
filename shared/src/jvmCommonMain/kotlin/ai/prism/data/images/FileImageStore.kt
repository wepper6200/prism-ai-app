package ai.prism.data.images

import ai.prism.core.newId
import ai.prism.domain.model.ImageAttachment
import ai.prism.domain.repository.ImageException
import ai.prism.domain.repository.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** Изображения сообщений в каталоге приложения: `<id>.jpg`. */
class FileImageStore(
    private val directory: File,
    private val codec: ImageCodec,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : ImageStore {

    override suspend fun save(bytes: ByteArray): ImageAttachment = withContext(ioContext) {
        if (bytes.size > MAX_INPUT_BYTES) throw ImageException("Изображение слишком большое")
        val image = try {
            codec.toJpeg(bytes, ImageStore.MAX_SIDE)
        } catch (e: OutOfMemoryError) {
            throw ImageException("Не хватило памяти, чтобы открыть изображение", e)
        } ?: throw ImageException("Формат изображения не поддерживается или файл повреждён")
        val id = newId()
        val fileName = "$id$EXTENSION"
        try {
            directory.mkdirs()
            val temp = File(directory, "$fileName.tmp")
            temp.writeBytes(image.jpeg)
            if (!temp.renameTo(File(directory, fileName))) {
                temp.delete()
                throw IOException("rename failed")
            }
        } catch (e: IOException) {
            throw ImageException("Не удалось сохранить изображение: ${e.message}", e)
        }
        ImageAttachment(id = id, fileName = fileName, width = image.width, height = image.height)
    }

    override suspend fun saveVideo(bytes: ByteArray, width: Int, height: Int): ImageAttachment = withContext(ioContext) {
        val fileName = newId() + ImageAttachment.VIDEO_EXTENSION
        try {
            directory.mkdirs()
            val temp = File(directory, "$fileName.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(File(directory, fileName))) {
                temp.delete()
                throw IOException("rename failed")
            }
        } catch (e: IOException) {
            throw ImageException("Не удалось сохранить видео: ${e.message}", e)
        }
        ImageAttachment(id = fileName.removeSuffix(ImageAttachment.VIDEO_EXTENSION), fileName = fileName, width = width, height = height)
    }

    override fun path(fileName: String): String = File(directory, fileName).absolutePath

    override suspend fun read(fileName: String): ByteArray? = withContext(ioContext) {
        val file = File(directory, fileName)
        try {
            if (file.isFile) file.readBytes() else null
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun delete(fileNames: Collection<String>) = withContext(ioContext) {
        fileNames.forEach { File(directory, it).delete() }
    }

    override suspend fun deleteOrphans(referenced: Set<String>, olderThanMillis: Long) = withContext(ioContext) {
        val now = System.currentTimeMillis()
        directory.listFiles()
            ?.filter { it.isFile && it.name !in referenced && now - it.lastModified() > olderThanMillis }
            ?.forEach { it.delete() }
        Unit
    }

    /** Пиксели изображения для модели. */
    fun decodeRgb(fileName: String): RgbImage? = codec.decodeRgb(File(directory, fileName))

    private companion object {
        const val EXTENSION = ".jpg"
        const val MAX_INPUT_BYTES = 64 * 1024 * 1024
    }
}
