package ai.prism.data.models

import ai.prism.data.catalog.ModelCatalog
import ai.prism.data.settings.KeyValueStore
import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.DeviceInfo
import ai.prism.domain.imagegen.resolveImageModelFiles
import ai.prism.domain.model.DownloadError
import ai.prism.domain.model.ModelKind
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelFile
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.repository.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlin.coroutines.CoroutineContext

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
    /** `android` или `desktop`: модели других платформ скрываются. `null` — показывать все. */
    private val platform: String? = null,
    /** Где распаковываются архивы моделей. */
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : ModelRepository {

    private val catalogModels = MutableStateFlow<List<CatalogModel>>(emptyList())
    private val statuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())

    /** Модели, у которых скачан основной файл: с ними уже можно разговаривать. */
    private val chatReady = MutableStateFlow<Set<String>>(emptySet())
    private val jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val active = MutableStateFlow(store.getString(KEY_ACTIVE_MODEL)?.takeIf { it.isNotBlank() })

    override val activeModelId: StateFlow<String?> = active.asStateFlow()

    override val models: StateFlow<List<ModelEntry>> = combine(catalogModels, statuses, chatReady) { list, states, ready ->
        list.map { model ->
            ModelEntry(
                model = model,
                status = states[model.id] ?: ModelStatus.NotDownloaded,
                fitsInMemory = deviceInfo.totalRamBytes >= model.minRamMb.toLong() * BYTES_IN_MB * RAM_TOLERANCE_PERCENT / 100,
                chatReady = model.id in ready,
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
            catalogModels.value = list.filter { model -> platform == null || model.availableOn(platform) }
            statuses.value = catalogModels.value.associate { it.id to initialStatus(it) }
            chatReady.value = catalogModels.value.filter { storage.isDownloaded(it.mainFile) }.map { it.id }.toSet()
        }
    }

    /** Скачивает недостающие файлы модели: основной и модуль зрения. */
    override fun download(modelId: String) {
        val model = catalogModel(modelId) ?: return
        if (jobs.value[modelId]?.isActive == true) return
        val total = model.totalSizeBytes
        val already = downloadedBytes(model)
        if (storage.freeSpaceBytes() < total - already + SPACE_MARGIN_BYTES) {
            setStatus(modelId, ModelStatus.Paused(already, total, DownloadError.NOT_ENOUGH_SPACE))
            return
        }
        setStatus(modelId, ModelStatus.Downloading(already, total))
        val job = scope.launch {
            try {
                var completed = 0L
                for (file in model.files) {
                    if (!storage.isDownloaded(file)) {
                        val base = completed
                        downloader.download(
                            file = file,
                            onProgress = { done, _ -> setStatus(modelId, ModelStatus.Downloading(base + done, total)) },
                            onVerifying = { setStatus(modelId, ModelStatus.Verifying) },
                        )
                        if (file.archive) {
                            setStatus(modelId, ModelStatus.Verifying)
                            unpack(file)
                        }
                        if (file == model.mainFile) chatReady.update { it + modelId }
                    }
                    completed += file.sizeBytes
                }
                setStatus(modelId, ModelStatus.Downloaded)
                val current = active.value
                if (model.kind == ModelKind.CHAT && (current == null || current !in chatReady.value)) {
                    setActive(modelId)
                }
            } catch (e: DownloadException) {
                setStatus(modelId, ModelStatus.Paused(downloadedBytes(model), total, e.error))
                // Основной файл мог докачаться раньше, чем оборвался модуль зрения.
                if (model.kind == ModelKind.CHAT && current() == null && modelId in chatReady.value) setActive(modelId)
            } catch (e: CancellationException) {
                setStatus(modelId, ModelStatus.Paused(downloadedBytes(model), total, null))
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
            // Общие файлы (детектор речи у моделей распознавания) остаются, пока они нужны другой модели.
            val usedElsewhere = catalogModels.value
                .filter { other -> other.id != modelId && statuses.value[other.id].let { it != null && it != ModelStatus.NotDownloaded } }
                .flatMap { other -> other.files.map { it.fileName } }
                .toSet()
            model.files.filter { it.fileName !in usedElsewhere }.forEach(storage::delete)
            setStatus(modelId, ModelStatus.NotDownloaded)
            chatReady.update { it - modelId }
            if (active.value == modelId) {
                active.value = null
                store.putString(KEY_ACTIVE_MODEL, "")
            }
        }
    }

    override fun setActive(modelId: String) {
        // Активной бывает только модель чата: модели рисования и поиска по файлам работают сами по себе.
        if (catalogModel(modelId)?.kind != ModelKind.CHAT) return
        active.value = modelId
        store.putString(KEY_ACTIVE_MODEL, modelId)
    }

    override fun refresh() {
        val busy = jobs.value.filterValues { it.isActive }.keys
        statuses.update { current ->
            catalogModels.value.associate { model ->
                model.id to if (model.id in busy) current[model.id] ?: initialStatus(model) else initialStatus(model)
            }
        }
        chatReady.value = catalogModels.value.filter { storage.isDownloaded(it.mainFile) }.map { it.id }.toSet()
    }

    override fun catalogModel(modelId: String): CatalogModel? =
        catalogModels.value.firstOrNull { it.id == modelId }

    override fun downloadedFilePath(modelId: String): String? =
        catalogModel(modelId)?.mainFile?.takeIf(storage::isDownloaded)?.let(storage::filePath)

    override fun downloadedProjectorPath(modelId: String): String? =
        catalogModel(modelId)?.vision?.takeIf(storage::isDownloaded)?.let(storage::filePath)

    override fun imageModelFiles(modelId: String): Map<String, String>? {
        val model = catalogModel(modelId)?.takeIf { it.kind == ModelKind.IMAGE || it.kind == ModelKind.VIDEO } ?: return null
        return resolveImageModelFiles(model) { file -> file.takeIf(storage::isDownloaded)?.let(storage::filePath) }
    }

    override suspend fun speechModelFiles(modelId: String): Map<String, String>? {
        val model = catalogModel(modelId)
            ?.takeIf { it.kind == ModelKind.SPEECH_RECOGNITION || it.kind == ModelKind.SPEECH_SYNTHESIS }
            ?: return null
        val roles = listOf(model.mainRole to model.mainFile) + model.components.map { it.role to it.file }
        if (!roles.all { (_, file) -> storage.isDownloaded(file) }) return null
        return withContext(ioContext) {
            roles.associate { (role, file) -> role to if (file.archive) unpack(file) else storage.filePath(file) }
        }
    }

    /** Распаковывает архив модели; ошибка записи — как ошибка загрузки. */
    private fun unpack(file: ModelFile): String = try {
        storage.unpackedPath(file)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw DownloadException(DownloadError.STORAGE, "Cannot unpack ${file.fileName}: ${e.message}", e)
    }

    private fun current(): String? = active.value?.takeIf { it in chatReady.value }

    /** Скачанные байты всех файлов модели, включая незавершённые части. */
    private fun downloadedBytes(model: CatalogModel): Long =
        model.files.sumOf { if (storage.isDownloaded(it)) it.sizeBytes else storage.partialBytes(it) }

    private fun initialStatus(model: CatalogModel): ModelStatus {
        val downloaded = downloadedBytes(model)
        return when {
            model.files.all(storage::isDownloaded) -> ModelStatus.Downloaded
            downloaded > 0 -> ModelStatus.Paused(downloaded, model.totalSizeBytes, null)
            else -> ModelStatus.NotDownloaded
        }
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
