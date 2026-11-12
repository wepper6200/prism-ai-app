package ai.prism.store.data

import ai.prism.store.model.AndroidPackage
import ai.prism.store.model.CatalogSource
import ai.prism.store.model.StoreApp
import ai.prism.store.model.StoreCatalog
import ai.prism.store.model.StoreFile
import ai.prism.store.model.WindowsPackage
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
    val size: Long = 0,
    /** `sha256:…` — GitHub считает сам. */
    val digest: String? = null,
)

/** Приложение Prism, которое можно найти среди файлов релиза по имени (если каталога в релизе нет). */
internal data class KnownApp(
    val id: String,
    val name: String,
    val packageName: String,
    val androidFile: Regex,
    val folder: String,
    val exe: String,
    val windowsFile: Regex,
)

/** Каталог из последнего релиза GitHub: файл [CATALOG_FILE] среди файлов релиза. */
class GitHubCatalogSource(
    private val apiUrl: String = LATEST_RELEASE_URL,
    private val userAgent: String = "PrismStore",
) : CatalogSource {

    override suspend fun load(): StoreCatalog = withContext(Dispatchers.IO) {
        val release = parse { json.decodeFromString(GitHubRelease.serializer(), fetch(apiUrl, "application/vnd.github+json")) }
        val asset = release.assets.firstOrNull { it.name == CATALOG_FILE }
            ?: return@withContext fromAssets(release.assets)
                ?: throw StoreException(StoreError.CATALOG, "В последнем релизе нет $CATALOG_FILE")
        parse { json.decodeFromString(StoreCatalog.serializer(), fetch(asset.downloadUrl, "application/json")) }
    }

    /**
     * Релизы, вышедшие до Prism Store, каталога не содержат: приложения находятся по именам файлов,
     * версия — из имени, размер и SHA-256 — из данных GitHub. Файл без SHA-256 не предлагается.
     */
    internal fun fromAssets(assets: List<GitHubAsset>): StoreCatalog? {
        fun match(pattern: Regex): Pair<String, StoreFile>? = assets.firstNotNullOfOrNull { asset ->
            val version = pattern.matchEntire(asset.name)?.groupValues?.get(1) ?: return@firstNotNullOfOrNull null
            val sha = asset.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                ?: return@firstNotNullOfOrNull null
            version to StoreFile(asset.name, asset.downloadUrl, asset.size, sha)
        }
        val apps = KNOWN_APPS.mapNotNull { known ->
            val android = match(known.androidFile)
            val windows = match(known.windowsFile)
            val version = (android ?: windows)?.first ?: return@mapNotNull null
            StoreApp(
                id = known.id,
                name = known.name,
                version = version,
                android = android?.let { AndroidPackage(known.packageName, it.second) },
                windows = windows?.let { WindowsPackage(known.folder, known.exe, it.second) },
            )
        }
        return apps.takeIf { it.isNotEmpty() }?.let { StoreCatalog(apps = it) }
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

        private val KNOWN_APPS = listOf(
            KnownApp(
                id = "prism-ai", name = "Prism AI", packageName = "ai.prism.app",
                androidFile = Regex("""PrismAI-([\d.]+)-android-arm64\.apk"""),
                folder = "PrismAI", exe = "PrismAI.exe",
                windowsFile = Regex("""PrismAI-([\d.]+)-windows-x64\.zip"""),
            ),
            KnownApp(
                id = "prism-store", name = "Prism Store", packageName = "ai.prism.store",
                androidFile = Regex("""PrismStore-([\d.]+)-android\.apk"""),
                folder = "PrismStore", exe = "PrismStore.exe",
                windowsFile = Regex("""PrismStore-([\d.]+)-windows\.zip"""),
            ),
        )
    }
}
