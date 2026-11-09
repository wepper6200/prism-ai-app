package ai.prism.data.models

import ai.prism.core.BuildInfo
import ai.prism.domain.model.ModelFile
import ai.prism.domain.model.DownloadError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Загрузка по HTTPS с докачкой (заголовок Range) во временный файл `.part`,
 * проверкой SHA-256 и атомарным переносом на место.
 */
class HttpModelDownloader(
    private val storage: FileModelStorage,
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
    /** Разрешённые схемы адресов; http — только для тестов с локальным сервером. */
    private val allowedSchemes: Set<String> = setOf("https"),
) : ModelDownloader {

    override suspend fun download(
        file: ModelFile,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onVerifying: () -> Unit,
    ) {
        withContext(Dispatchers.IO) { downloadBlocking(file, onProgress, onVerifying) }
    }

    private suspend fun downloadBlocking(
        model: ModelFile,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onVerifying: () -> Unit,
    ) {
        val part = storage.partFile(model)
        val target = storage.file(model)
        target.parentFile?.mkdirs()

        var downloaded = if (part.isFile) part.length() else 0L
        if (downloaded > model.sizeBytes) {
            part.delete()
            downloaded = 0L
        }

        if (downloaded < model.sizeBytes) {
            try {
                downloaded = transfer(model, downloaded, onProgress)
            } catch (e: IOException) {
                val error = if (storage.freeSpaceBytes() < LOW_SPACE_BYTES) DownloadError.NOT_ENOUGH_SPACE else DownloadError.NETWORK
                throw DownloadException(error, "Загрузка прервана: ${e.message}", e)
            }
        }
        if (downloaded != model.sizeBytes) {
            throw DownloadException(DownloadError.NETWORK, "Сервер передал файл не полностью")
        }

        onVerifying()
        val actual = try {
            sha256(part)
        } catch (e: IOException) {
            throw DownloadException(DownloadError.STORAGE, "Не удалось прочитать файл: ${e.message}", e)
        }
        if (!actual.equals(model.sha256, ignoreCase = true)) {
            part.delete()
            throw DownloadException(DownloadError.CHECKSUM_MISMATCH, "Контрольная сумма не совпала")
        }
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw DownloadException(DownloadError.STORAGE, "Не удалось сохранить файл модели: ${e.message}", e)
        }
    }

    /** Докачивает файл начиная с [startFrom] байта; возвращает итоговый размер. */
    private suspend fun transfer(
        model: ModelFile,
        startFrom: Long,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        val part = storage.partFile(model)
        val connection = open(model.url, startFrom)
        try {
            var downloaded = startFrom
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit
                HttpURLConnection.HTTP_OK -> downloaded = 0L // сервер не поддержал докачку — начинаем заново
                HTTP_RANGE_NOT_SATISFIABLE -> return part.length()
                else -> throw DownloadException(DownloadError.SERVER, "Сервер ответил кодом $code")
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
                        if (downloaded > model.sizeBytes) {
                            throw DownloadException(DownloadError.SERVER, "Файл на сервере больше ожидаемого")
                        }
                        val now = System.nanoTime()
                        if (now - lastReport > PROGRESS_INTERVAL_NS) {
                            onProgress(downloaded, model.sizeBytes)
                            lastReport = now
                        }
                    }
                }
            }
            onProgress(downloaded, model.sizeBytes)
            return downloaded
        } finally {
            connection.disconnect()
        }
    }

    /** Открывает соединение, вручную проходя редиректы (с сохранением заголовка Range). */
    private fun open(url: String, startFrom: Long): HttpURLConnection {
        var uri = URI(url)
        repeat(MAX_REDIRECTS) {
            if (uri.scheme !in allowedSchemes) throw DownloadException(DownloadError.SERVER, "Разрешены только HTTPS-адреса")
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", "PrismAI/${BuildInfo.VERSION_NAME}")
            if (startFrom > 0) connection.setRequestProperty("Range", "bytes=$startFrom-")
            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw DownloadException(DownloadError.SERVER, "Перенаправление без адреса")
                uri = uri.resolve(location)
            } else {
                return connection
            }
        }
        throw DownloadException(DownloadError.SERVER, "Слишком много перенаправлений")
    }

    private suspend fun sha256(file: java.io.File): String {
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
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val PROGRESS_INTERVAL_NS = 250_000_000L
        const val LOW_SPACE_BYTES = 64L * 1024 * 1024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
