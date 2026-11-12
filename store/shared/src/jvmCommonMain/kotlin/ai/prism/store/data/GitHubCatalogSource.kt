package ai.prism.store.data

import ai.prism.store.model.CatalogSource
import ai.prism.store.model.StoreCatalog
import ai.prism.store.model.StoreError
import ai.prism.store.model.StoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

@Serializable
internal data class GitHubRelease(val assets: List<GitHubAsset> = emptyList())

@Serializable
internal data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)

/** Каталог из последнего релиза GitHub: файл [CATALOG_FILE] среди файлов релиза. */
class GitHubCatalogSource(
    private val apiUrl: String = LATEST_RELEASE_URL,
    private val userAgent: String = "PrismStore",
) : CatalogSource {

    override suspend fun load(): StoreCatalog = withContext(Dispatchers.IO) {
        val release = parse { json.decodeFromString(GitHubRelease.serializer(), fetch(apiUrl, "application/vnd.github+json")) }
        val asset = release.assets.firstOrNull { it.name == CATALOG_FILE }
            ?: throw StoreException(StoreError.CATALOG, "В последнем релизе нет $CATALOG_FILE")
        parse { json.decodeFromString(StoreCatalog.serializer(), fetch(asset.downloadUrl, "application/json")) }
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw StoreException(StoreError.CATALOG, "Каталог повреждён: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw StoreException(StoreError.CATALOG, "Каталог повреждён: ${e.message}", e)
    }

    private fun fetch(url: String, accept: String): String {
        var uri = URI(url)
        repeat(MAX_REDIRECTS) {
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("User-Agent", userAgent)
                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw StoreException(StoreError.NETWORK, "Перенаправление без адреса")
                        uri = uri.resolve(location)
                    }
                    code == HttpURLConnection.HTTP_NOT_FOUND ->
                        throw StoreException(StoreError.CATALOG, "Релизов пока нет")
                    code != HttpURLConnection.HTTP_OK ->
                        throw StoreException(StoreError.NETWORK, "Сервер ответил кодом $code")
                    else -> return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                }
            } catch (e: IOException) {
                throw StoreException(StoreError.NETWORK, "Нет связи с сервером: ${e.message}", e)
            } finally {
                connection.disconnect()
            }
        }
        throw StoreException(StoreError.NETWORK, "Слишком много перенаправлений")
    }

    companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/wepper6200/prism-ai-app/releases/latest"
        const val CATALOG_FILE = "prism-store-catalog.json"
        private const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MS = 20_000
        private val json = Json { ignoreUnknownKeys = true }
    }
}
