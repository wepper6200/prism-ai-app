package ai.prism.store.data

import ai.prism.store.model.PackageDownloader
import ai.prism.store.model.StoreError
import ai.prism.store.model.StoreException
import ai.prism.store.model.StoreFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Загрузка файла приложения в [directory] с докачкой (`.part` + заголовок Range) и проверкой SHA-256.
 * Старые файлы других версий удаляются перед загрузкой.
 */
class HttpPackageDownloader(
    private val directory: File,
    private val allowedSchemes: Set<String> = setOf("https"),
) : PackageDownloader {

    override suspend fun download(file: StoreFile, onProgress: (Long, Long) -> Unit): String = withContext(Dispatchers.IO) {
        directory.mkdirs()
        directory.listFiles()?.filter { it.isFile && !it.name.startsWith(file.fileName) }?.forEach { it.delete() }
        val target = File(directory, file.fileName)
        if (target.isFile && target.length() == file.sizeBytes && sha256(target).equals(file.sha256, ignoreCase = true)) {
            onProgress(file.sizeBytes, file.sizeBytes)
            return@withContext target.absolutePath
        }
        val part = File(directory, file.fileName + ".part")
        var downloaded = if (part.isFile && part.length() <= file.sizeBytes) part.length() else 0L
        try {
            downloaded = transfer(file, part, downloaded, onProgress)
        } catch (e: IOException) {
            val error = if (directory.usableSpace < file.sizeBytes - downloaded) StoreError.STORAGE else StoreError.NETWORK
            throw StoreException(error, "Загрузка прервана: ${e.message}", e)
        }
        if (downloaded != file.sizeBytes) throw StoreException(StoreError.NETWORK, "Сервер передал файл не полностью")
        if (!sha256(part).equals(file.sha256, ignoreCase = true)) {
            part.delete()
            throw StoreException(StoreError.CHECKSUM, "Контрольная сумма не совпала")
        }
        target.delete()
        if (!part.renameTo(target)) throw StoreException(StoreError.STORAGE, "Не удалось сохранить ${target.name}")
        target.absolutePath
    }

    private suspend fun transfer(file: StoreFile, part: File, startFrom: Long, onProgress: (Long, Long) -> Unit): Long {
        val connection = open(file.url, startFrom)
        try {
            var downloaded = startFrom
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit
                HttpURLConnection.HTTP_OK -> downloaded = 0L
                HTTP_RANGE_NOT_SATISFIABLE -> return part.length()
                else -> throw StoreException(StoreError.NETWORK, "Сервер ответил кодом $code")
            }
            val buffer = ByteArray(BUFFER_SIZE)
            var lastReport = 0L
            connection.inputStream.use { input ->
                FileOutputStream(part, downloaded > 0).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded > file.sizeBytes) throw StoreException(StoreError.NETWORK, "Файл больше ожидаемого")
                        val now = System.nanoTime()
                        if (now - lastReport > PROGRESS_INTERVAL_NS) {
                            onProgress(downloaded, file.sizeBytes)
                            lastReport = now
                        }
                    }
                }
            }
            onProgress(downloaded, file.sizeBytes)
            return downloaded
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, startFrom: Long): HttpURLConnection {
        var uri = URI(url)
        repeat(MAX_REDIRECTS) {
            if (uri.scheme !in allowedSchemes) throw StoreException(StoreError.NETWORK, "Разрешены только HTTPS-адреса")
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "PrismStore")
            if (startFrom > 0) connection.setRequestProperty("Range", "bytes=$startFrom-")
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw StoreException(StoreError.NETWORK, "Перенаправление без адреса")
                uri = uri.resolve(location)
            } else {
                return connection
            }
        }
        throw StoreException(StoreError.NETWORK, "Слишком много перенаправлений")
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
        const val MAX_REDIRECTS = 8
        const val TIMEOUT_MS = 30_000
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val PROGRESS_INTERVAL_NS = 250_000_000L
    }
}
