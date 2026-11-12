package ai.prism.data.update

import ai.prism.core.BuildInfo
import ai.prism.data.models.DownloadException
import ai.prism.data.models.FileModelStorage
import ai.prism.data.models.HttpModelDownloader
import ai.prism.domain.model.DownloadError
import ai.prism.domain.model.ModelFile
import ai.prism.domain.update.AppRelease
import ai.prism.domain.update.AppUpdater
import ai.prism.domain.update.ReleaseAsset
import ai.prism.domain.update.UpdateError
import ai.prism.domain.update.UpdateException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Ответ GitHub API о релизе — только нужные поля. */
@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long,
    /** `sha256:…` — GitHub считает сам; у старых релизов может не быть, тогда берётся SHA256SUMS.txt. */
    val digest: String? = null,
)

/**
 * Обновление из релизов GitHub репозитория приложения: последний релиз, файл с окончанием [assetSuffix]
 * (`-android-arm64.apk`, `-windows-x64.zip`), загрузка с докачкой в [updatesDir] и проверкой SHA-256.
 * Установку делает платформа ([install]).
 */
abstract class GitHubAppUpdater(
    private val updatesDir: File,
    private val assetSuffix: String,
    private val apiUrl: String = LATEST_RELEASE_URL,
    allowedSchemes: Set<String> = setOf("https"),
) : AppUpdater {

    private val storage = FileModelStorage(updatesDir)
    private val downloader = HttpModelDownloader(storage, allowedSchemes = allowedSchemes)

    override suspend fun latestRelease(): AppRelease = withContext(Dispatchers.IO) {
        val release = try {
            json.decodeFromString(GitHubRelease.serializer(), fetchText(apiUrl))
        } catch (e: SerializationException) {
            throw UpdateException(UpdateError.NETWORK, "Непонятный ответ сервера: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw UpdateException(UpdateError.NETWORK, "Непонятный ответ сервера: ${e.message}", e)
        }
        val asset = release.assets.firstOrNull { it.name.endsWith(assetSuffix) }
        AppRelease(
            version = release.tagName.removePrefix("v"),
            notes = release.body.orEmpty().trim(),
            pageUrl = release.htmlUrl,
            asset = asset?.let { ReleaseAsset(it.name, it.downloadUrl, it.size, sha256Of(it, release.assets)) },
        )
    }

    override suspend fun download(asset: ReleaseAsset, onProgress: (Long, Long) -> Unit): String {
        val file = ModelFile(asset.url, asset.name, asset.sizeBytes, asset.sha256)
        withContext(Dispatchers.IO) {
            updatesDir.mkdirs()
            // Старые версии и недокачанные чужие файлы больше не нужны.
            updatesDir.listFiles()?.filter { it.isFile && !it.name.startsWith(asset.name) }?.forEach { it.delete() }
        }
        try {
            downloader.download(file, onProgress) {}
        } catch (e: DownloadException) {
            val error = when (e.error) {
                DownloadError.CHECKSUM_MISMATCH -> UpdateError.CHECKSUM
                DownloadError.NOT_ENOUGH_SPACE, DownloadError.STORAGE -> UpdateError.STORAGE
                else -> UpdateError.NETWORK
            }
            throw UpdateException(error, e.message ?: "Загрузка не удалась", e)
        }
        return storage.file(file).absolutePath
    }

    /** SHA-256 файла: из поля digest или из SHA256SUMS.txt того же релиза. */
    private fun sha256Of(asset: GitHubAsset, assets: List<GitHubAsset>): String {
        asset.digest?.takeIf { it.startsWith("sha256:") }?.let { return it.removePrefix("sha256:") }
        val sums = assets.firstOrNull { it.name == SUMS_FILE }
            ?: throw UpdateException(UpdateError.NO_PACKAGE, "В релизе нет контрольных сумм")
        return fetchText(sums.downloadUrl).lineSequence()
            .map { it.trim().split(Regex("\\s+\\*?")) }
            .firstOrNull { it.size == 2 && it[1] == asset.name }?.get(0)
            ?: throw UpdateException(UpdateError.NO_PACKAGE, "Нет контрольной суммы для ${asset.name}")
    }

    private fun fetchText(url: String): String {
        var uri = URI(url)
        repeat(MAX_REDIRECTS) {
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "PrismAI/${BuildInfo.VERSION_NAME}")
                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw UpdateException(UpdateError.NETWORK, "Перенаправление без адреса")
                        uri = uri.resolve(location)
                    }
                    code == HttpURLConnection.HTTP_NOT_FOUND ->
                        throw UpdateException(UpdateError.NO_PACKAGE, "Релизов пока нет")
                    code != HttpURLConnection.HTTP_OK ->
                        throw UpdateException(UpdateError.NETWORK, "Сервер ответил кодом $code")
                    else -> return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                }
            } catch (e: IOException) {
                throw UpdateException(UpdateError.NETWORK, "Нет связи с сервером: ${e.message}", e)
            } finally {
                connection.disconnect()
            }
        }
        throw UpdateException(UpdateError.NETWORK, "Слишком много перенаправлений")
    }

    companion object {
        /** Репозиторий, из релизов которого ставится приложение. */
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/wepper6200/prism-ai-app/releases/latest"
        private const val SUMS_FILE = "SHA256SUMS.txt"
        private const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MS = 20_000
        private val json = Json { ignoreUnknownKeys = true }
    }
}
