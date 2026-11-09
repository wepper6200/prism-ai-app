package ai.prism.data.models

import ai.prism.data.catalog.ModelCatalog
import ai.prism.data.settings.KeyValueStore
import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.DeviceInfo
import ai.prism.domain.model.DownloadError
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.repository.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/**
 * Каталог моделей + файлы на устройстве + загрузки.
 * Загрузки идут в области приложения [scope] и не прерываются при уходе с экрана.
 */
class DefaultModelRepository(
    private val catalog: ModelCatalog,
    private val storage: ModelStorage,
    private val downloader: ModelDownloader,
    private val store: KeyValueStore,
    private val deviceInfo: DeviceInfo,
    private val scope: CoroutineScope,
) : ModelRepository {

    private val catalogModels = MutableStateFlow<List<CatalogModel>>(emptyList())
    private val statuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
    private val jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val active = MutableStateFlow(store.getString(KEY_ACTIVE_MODEL)?.takeIf { it.isNotBlank() })

    override val activeModelId: StateFlow<String?> = active.asStateFlow()

    override val models: StateFlow<List<ModelEntry>> = combine(catalogModels, statuses) { list, states ->
        list.map { model ->
            ModelEntry(
                model = model,
                status = states[model.id] ?: ModelStatus.NotDownloaded,
                fitsInMemory = deviceInfo.totalRamBytes >= model.minRamMb.toLong() * BYTES_IN_MB * RAM_TOLERANCE_PERCENT / 100,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            val list = try {
                catalog.load()
            } catch (e: SerializationException) {
                println("Prism AI: каталог моделей повреждён: ${e.message}")
                emptyList()
            }
            catalogModels.value = list
            statuses.value = list.associate { it.id to initialStatus(it) }
        }
    }

    override fun download(modelId: String) {
        val model = catalogModel(modelId) ?: return
        if (jobs.value[modelId]?.isActive == true) return
        val partial = storage.partialBytes(model)
        if (storage.freeSpaceBytes() < model.sizeBytes - partial + SPACE_MARGIN_BYTES) {
            setStatus(modelId, ModelStatus.Paused(partial, model.sizeBytes, DownloadError.NOT_ENOUGH_SPACE))
            return
        }
        setStatus(modelId, ModelStatus.Downloading(partial, model.sizeBytes))
        val job = scope.launch {
            try {
                downloader.download(
                    model = model,
                    onProgress = { done, total -> setStatus(modelId, ModelStatus.Downloading(done, total)) },
                    onVerifying = { setStatus(modelId, ModelStatus.Verifying) },
                )
                setStatus(modelId, ModelStatus.Downloaded)
                val current = active.value
                if (current == null || catalogModel(current)?.let(storage::isDownloaded) != true) {
                    setActive(modelId)
                }
            } catch (e: DownloadException) {
                setStatus(modelId, ModelStatus.Paused(storage.partialBytes(model), model.sizeBytes, e.error))
            } catch (e: CancellationException) {
                setStatus(modelId, ModelStatus.Paused(storage.partialBytes(model), model.sizeBytes, null))
                throw e
            } finally {
                jobs.update { it - modelId }
            }
        }
        jobs.update { it + (modelId to job) }
    }

    override fun pause(modelId: String) {
        jobs.value[modelId]?.cancel()
    }

    override fun delete(modelId: String) {
        val model = catalogModel(modelId) ?: return
        val job = jobs.value[modelId]
        scope.launch {
            job?.cancel()
            job?.join()
            storage.delete(model)
            setStatus(modelId, ModelStatus.NotDownloaded)
            if (active.value == modelId) {
                active.value = null
                store.putString(KEY_ACTIVE_MODEL, "")
            }
        }
    }

    override fun setActive(modelId: String) {
        active.value = modelId
        store.putString(KEY_ACTIVE_MODEL, modelId)
    }

    override fun catalogModel(modelId: String): CatalogModel? =
        catalogModels.value.firstOrNull { it.id == modelId }

    override fun downloadedFilePath(modelId: String): String? =
        catalogModel(modelId)?.takeIf(storage::isDownloaded)?.let(storage::filePath)

    private fun initialStatus(model: CatalogModel): ModelStatus = when {
        storage.isDownloaded(model) -> ModelStatus.Downloaded
        storage.partialBytes(model) > 0 -> ModelStatus.Paused(storage.partialBytes(model), model.sizeBytes, null)
        else -> ModelStatus.NotDownloaded
    }

    private fun setStatus(modelId: String, status: ModelStatus) {
        statuses.update { it + (modelId to status) }
    }

    private companion object {
        const val KEY_ACTIVE_MODEL = "active_model"
        const val BYTES_IN_MB = 1024L * 1024L
        const val RAM_TOLERANCE_PERCENT = 85
        const val SPACE_MARGIN_BYTES = 200L * 1024 * 1024
    }
}
