package ai.prism.data.models

import ai.prism.data.catalog.ModelCatalog
import ai.prism.data.settings.MapKeyValueStore
import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.DownloadError
import ai.prism.domain.model.ModelStatus
import ai.prism.testing.FakeDeviceInfo
import ai.prism.testing.testModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultModelRepositoryTest {

    private val model = testModel()
    private val catalogJson = Json.encodeToString(
        ai.prism.domain.model.ModelCatalogFile.serializer(),
        ai.prism.domain.model.ModelCatalogFile(version = 1, models = listOf(model)),
    )

    private class MemoryStorage(var freeSpace: Long = Long.MAX_VALUE) : ModelStorage {
        val downloaded = mutableSetOf<String>()
        override fun isDownloaded(model: CatalogModel) = model.id in downloaded
        override fun partialBytes(model: CatalogModel) = 0L
        override fun filePath(model: CatalogModel) = "/models/${model.fileName}"
        override fun delete(model: CatalogModel) {
            downloaded -= model.id
        }
        override fun freeSpaceBytes() = freeSpace
    }

    private class ScriptedDownloader(private val storage: MemoryStorage) : ModelDownloader {
        var failWith: DownloadError? = null
        override suspend fun download(
            model: CatalogModel,
            onProgress: (Long, Long) -> Unit,
            onVerifying: () -> Unit,
        ) {
            onProgress(model.sizeBytes / 2, model.sizeBytes)
            failWith?.let { throw DownloadException(it, "ошибка") }
            onVerifying()
            storage.downloaded += model.id
        }
    }

    private fun repository(storage: MemoryStorage, downloader: ModelDownloader) = DefaultModelRepository(
        catalog = ModelCatalog { catalogJson.encodeToByteArray() },
        storage = storage,
        downloader = downloader,
        store = MapKeyValueStore(),
        deviceInfo = FakeDeviceInfo(),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun successfulDownloadBecomesActive() {
        val storage = MemoryStorage()
        val repo = repository(storage, ScriptedDownloader(storage))

        repo.download(model.id)

        assertEquals(ModelStatus.Downloaded, repo.models.value.single().status)
        assertEquals(model.id, repo.activeModelId.value)
        assertEquals("/models/${model.fileName}", repo.downloadedFilePath(model.id))
    }

    @Test
    fun failedDownloadIsPausedWithError() {
        val storage = MemoryStorage()
        val downloader = ScriptedDownloader(storage).apply { failWith = DownloadError.NETWORK }
        val repo = repository(storage, downloader)

        repo.download(model.id)

        val status = repo.models.value.single().status
        assertEquals(DownloadError.NETWORK, (status as ModelStatus.Paused).error)
        assertNull(repo.activeModelId.value)
    }

    @Test
    fun notEnoughSpaceStopsBeforeDownloading() {
        val storage = MemoryStorage(freeSpace = 1024)
        val repo = repository(storage, ScriptedDownloader(storage))

        repo.download(model.id)

        val status = repo.models.value.single().status
        assertEquals(DownloadError.NOT_ENOUGH_SPACE, (status as ModelStatus.Paused).error)
    }

    @Test
    fun deletingActiveModelClearsSelection() {
        val storage = MemoryStorage()
        val repo = repository(storage, ScriptedDownloader(storage))
        repo.download(model.id)

        repo.delete(model.id)

        assertEquals(ModelStatus.NotDownloaded, repo.models.value.single().status)
        assertNull(repo.activeModelId.value)
    }
}
