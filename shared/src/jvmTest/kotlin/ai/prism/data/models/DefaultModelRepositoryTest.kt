package ai.prism.data.models

import ai.prism.data.catalog.ModelCatalog
import ai.prism.data.settings.MapKeyValueStore
import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.DownloadError
import ai.prism.domain.model.ModelCatalogFile
import ai.prism.domain.model.ModelFile
import ai.prism.domain.model.ModelStatus
import ai.prism.testing.FakeDeviceInfo
import ai.prism.testing.testModel
import ai.prism.testing.testVision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultModelRepositoryTest {

    private val model = testModel()
    private val visionFile = testVision("vision-model")
    private val visionModel = testModel(id = "vision-model", vision = visionFile)

    private fun catalogJson(models: List<CatalogModel>) =
        Json.encodeToString(ModelCatalogFile.serializer(), ModelCatalogFile(version = 2, models = models))

    private class MemoryStorage(var freeSpace: Long = Long.MAX_VALUE) : ModelStorage {
        val downloaded = mutableSetOf<String>()
        override fun isDownloaded(file: ModelFile) = file.fileName in downloaded
        override fun partialBytes(file: ModelFile) = 0L
        override fun filePath(file: ModelFile) = "/models/${file.fileName}"
        override fun delete(file: ModelFile) {
            downloaded -= file.fileName
        }
        override fun freeSpaceBytes() = freeSpace
    }

    private class ScriptedDownloader(private val storage: MemoryStorage) : ModelDownloader {
        /** Файлы, загрузка которых завершится ошибкой. */
        val failing = mutableMapOf<String, DownloadError>()
        val requested = mutableListOf<String>()
        override suspend fun download(
            file: ModelFile,
            onProgress: (Long, Long) -> Unit,
            onVerifying: () -> Unit,
        ) {
            requested += file.fileName
            onProgress(file.sizeBytes / 2, file.sizeBytes)
            failing[file.fileName]?.let { throw DownloadException(it, "ошибка") }
            onVerifying()
            storage.downloaded += file.fileName
        }
    }

    private fun repository(
        storage: MemoryStorage,
        downloader: ModelDownloader,
        models: List<CatalogModel> = listOf(model),
    ) = DefaultModelRepository(
        catalog = ModelCatalog { catalogJson(models).encodeToByteArray() },
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
        assertNull(repo.downloadedProjectorPath(model.id))
    }

    @Test
    fun failedDownloadIsPausedWithError() {
        val storage = MemoryStorage()
        val downloader = ScriptedDownloader(storage).apply { failing[model.fileName] = DownloadError.NETWORK }
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

    @Test
    fun visionModelDownloadsBothFiles() {
        val storage = MemoryStorage()
        val downloader = ScriptedDownloader(storage)
        val repo = repository(storage, downloader, listOf(visionModel))

        repo.download(visionModel.id)

        val entry = repo.models.value.single()
        assertEquals(ModelStatus.Downloaded, entry.status)
        assertTrue(entry.visionReady)
        assertEquals(listOf(visionModel.fileName, visionFile.fileName), downloader.requested)
        assertEquals("/models/${visionFile.fileName}", repo.downloadedProjectorPath(visionModel.id))
    }

    /** Модель, скачанная до появления зрения: текст работает, докачивается только модуль зрения. */
    @Test
    fun modelWithoutVisionModuleKeepsWorkingAndDownloadsOnlyTheModule() {
        val storage = MemoryStorage().apply { downloaded += visionModel.fileName }
        val downloader = ScriptedDownloader(storage)
        val repo = repository(storage, downloader, listOf(visionModel))

        val before = repo.models.value.single()
        assertTrue(before.chatReady)
        assertTrue(before.visionMissing)
        assertEquals(visionModel.sizeBytes, (before.status as ModelStatus.Paused).downloadedBytes)
        assertEquals(visionModel.totalSizeBytes, before.status.totalBytes)
        assertEquals("/models/${visionModel.fileName}", repo.downloadedFilePath(visionModel.id))

        repo.download(visionModel.id)

        assertEquals(listOf(visionFile.fileName), downloader.requested)
        assertTrue(repo.models.value.single().visionReady)
    }

    @Test
    fun failedVisionDownloadStillActivatesTextModel() {
        val storage = MemoryStorage()
        val downloader = ScriptedDownloader(storage).apply { failing[visionFile.fileName] = DownloadError.NETWORK }
        val repo = repository(storage, downloader, listOf(visionModel))

        repo.download(visionModel.id)

        val entry = repo.models.value.single()
        assertTrue(entry.chatReady)
        assertFalse(entry.visionReady)
        assertEquals(DownloadError.NETWORK, (entry.status as ModelStatus.Paused).error)
        assertEquals(visionModel.id, repo.activeModelId.value)
    }
}
