@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package ai.prism.ios

import ai.prism.core.currentTimeMillis
import ai.prism.core.newId
import ai.prism.data.images.EncodedImage
import ai.prism.data.images.ImageCodec
import ai.prism.data.images.RgbImage
import ai.prism.data.models.DownloadException
import ai.prism.data.models.ModelDownloader
import ai.prism.data.models.ModelStorage
import ai.prism.data.settings.KeyValueStore
import ai.prism.domain.model.DownloadError
import ai.prism.domain.model.ImageAttachment
import ai.prism.domain.model.ModelFile
import ai.prism.domain.repository.ImageException
import ai.prism.domain.repository.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class IosPreferences(private val host: IosHost) : KeyValueStore {
    override fun getString(key: String): String? = host.preference(key)
    override fun putString(key: String, value: String) = host.setPreference(key, value)
}

internal class IosModelStorage(private val host: IosHost) : ModelStorage {
    val directory = host.dataDirectory() + "/models"
    init { check(host.makeDirectory(directory)) }
    override fun filePath(file: ModelFile): String = "$directory/${file.fileName}"
    fun part(file: ModelFile): String = filePath(file) + ".part"
    override fun isDownloaded(file: ModelFile): Boolean = host.exists(filePath(file)) && host.size(filePath(file)) == file.sizeBytes
    override fun partialBytes(file: ModelFile): Long = host.size(part(file)).coerceAtLeast(0)
    override fun freeSpaceBytes(): Long = host.freeSpace()
    override fun unpackedPath(file: ModelFile): String {
        val directory = filePath(file) + ".d"
        val marker = "$directory/.prism-unpacked"
        if (host.read(marker)?.toBytes()?.decodeToString() != file.sha256) {
            if (!host.unpackArchive(filePath(file), directory)) throw DownloadException(DownloadError.STORAGE, "Cannot unpack voice model")
            check(host.write(marker, file.sha256.encodeToByteArray().toNativeData()))
        }
        return directory
    }
    override fun delete(file: ModelFile) {
        listOf("", ".part", ".resume", ".d").forEach { host.remove(filePath(file) + it) }
    }
    override fun adoptOldFiles(file: ModelFile) {
        file.oldFileNames.filter { it != file.fileName && '/' !in it && '\\' !in it }.forEach { old ->
            listOf("", ".part", ".resume", ".d").forEach { suffix ->
                val target = filePath(file) + suffix
                val source = "$directory/$old$suffix"
                if (!host.exists(target) && host.exists(source)) host.move(source, target)
            }
        }
    }
}

internal class IosModelDownloader(private val host: IosHost, private val storage: IosModelStorage) : ModelDownloader {
    override suspend fun download(file: ModelFile, onProgress: (Long, Long) -> Unit, onVerifying: () -> Unit) = withContext(Dispatchers.Default) {
        if (host.freeSpace() < file.sizeBytes - storage.partialBytes(file)) throw DownloadException(DownloadError.NOT_ENOUGH_SPACE, "Not enough space")
        val part = storage.part(file)
        suspendCancellableCoroutine { continuation ->
            val task = host.download(file.url, part, storage.filePath(file) + ".resume", onProgress) { error ->
                if (continuation.isActive) {
                    if (error == null) continuation.resume(Unit)
                    else continuation.resumeWithException(DownloadException(DownloadError.NETWORK, error))
                }
            }
            continuation.invokeOnCancellation { task.cancel() }
        }
        onVerifying()
        if (host.size(part) != file.sizeBytes || !host.sha256(part).equals(file.sha256, true)) {
            host.remove(part)
            throw DownloadException(DownloadError.CHECKSUM_MISMATCH, "Downloaded model failed size or SHA-256 verification")
        }
        if (!host.move(part, storage.filePath(file))) throw DownloadException(DownloadError.STORAGE, "Cannot save model")
        if (file.archive) storage.unpackedPath(file)
    }
}

internal class IosImageCodec(private val host: IosHost) : ImageCodec {
    override fun toJpeg(bytes: ByteArray, maxSide: Int): EncodedImage? = host.encodeImage(bytes.toNativeData(), maxSide)?.let {
        EncodedImage(it.jpeg.toBytes(), it.width, it.height)
    }
    override fun decodeRgb(path: String): RgbImage? = host.read(path)?.let { host.encodeImage(it, ImageStore.MAX_SIDE) }?.let {
        RgbImage(it.width, it.height, it.rgb.toBytes())
    }
}

internal class IosImageStore(private val host: IosHost, private val codec: ImageCodec) : ImageStore {
    private val directory = host.dataDirectory() + "/images"
    init { check(host.makeDirectory(directory)) }
    override suspend fun save(bytes: ByteArray): ImageAttachment = withContext(Dispatchers.Default) {
        val image = codec.toJpeg(bytes, ImageStore.MAX_SIDE) ?: throw ImageException("Cannot decode image")
        val id = newId()
        val name = "$id.jpg"
        if (!host.write(path(name), image.jpeg.toNativeData())) throw ImageException("Cannot save image")
        ImageAttachment(id, name, image.width, image.height)
    }
    override suspend fun saveVideo(bytes: ByteArray, width: Int, height: Int): ImageAttachment = saveMedia(bytes, width, height, "avi")
    override suspend fun saveAudio(bytes: ByteArray): ImageAttachment = saveMedia(bytes, 0, 0, "wav")
    private fun saveMedia(bytes: ByteArray, width: Int, height: Int, extension: String): ImageAttachment {
        val id = newId(); val name = "$id.$extension"
        if (!host.write(path(name), bytes.toNativeData())) throw ImageException("Cannot save media")
        return ImageAttachment(id, name, width, height)
    }
    override fun path(fileName: String): String {
        require('/' !in fileName && '\\' !in fileName && fileName != "..")
        return "$directory/$fileName"
    }
    override suspend fun read(fileName: String): ByteArray? = withContext(Dispatchers.Default) { host.read(path(fileName))?.toBytes() }
    override suspend fun delete(fileNames: Collection<String>) { fileNames.forEach { host.remove(path(it)) } }
    override suspend fun deleteOrphans(referenced: Set<String>, olderThanMillis: Long) {
        host.children(directory).filter { it !in referenced && host.modifiedMillis(path(it)) < olderThanMillis && host.modifiedMillis(path(it)) <= currentTimeMillis() }
            .forEach { host.remove(path(it)) }
    }
}
