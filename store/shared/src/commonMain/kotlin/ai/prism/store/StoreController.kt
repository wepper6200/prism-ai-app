package ai.prism.store

import ai.prism.store.model.AppInstaller
import ai.prism.store.model.CatalogSource
import ai.prism.store.model.PackageDownloader
import ai.prism.store.model.StoreApp
import ai.prism.store.model.StoreError
import ai.prism.store.model.StoreException
import ai.prism.store.model.Versions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Что можно сделать с приложением. */
enum class AppAction { INSTALL, UPDATE, OPEN }

sealed interface AppProgress {
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : AppProgress

    /** Android — ждёт подтверждения в системном окне; Windows — распаковка. */
    data object Installing : AppProgress
}

data class AppItem(
    val app: StoreApp,
    val sizeBytes: Long,
    val installedVersion: String?,
    val action: AppAction,
    val progress: AppProgress? = null,
    val error: StoreError? = null,
)

data class StoreState(
    val loading: Boolean = false,
    val error: StoreError? = null,
    val apps: List<AppItem> = emptyList(),
)

/**
 * Prism Store: каталог из последнего релиза, установка и обновление приложений. Сеть — только когда
 * пользователь открывает магазин, обновляет список или ставит приложение.
 */
class StoreController(
    private val source: CatalogSource,
    private val downloader: PackageDownloader,
    private val installer: AppInstaller,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(StoreState())
    val state: StateFlow<StoreState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private val installJobs = mutableMapOf<String, Job>()

    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val catalog = source.load()
                val apps = catalog.apps.mapNotNull { app -> item(app) }
                _state.update { current ->
                    StoreState(
                        apps = apps.map { fresh ->
                            val old = current.apps.firstOrNull { it.app.id == fresh.app.id }
                            if (old?.progress != null) fresh.copy(progress = old.progress) else fresh
                        },
                    )
                }
            } catch (e: StoreException) {
                _state.update { it.copy(loading = false, error = e.error) }
            }
        }
    }

    /** Заново проверяет установленные версии (например, когда магазин снова на экране после установки). */
    fun recheckInstalled() {
        _state.update { current ->
            current.copy(apps = current.apps.map { old -> item(old.app)?.copy(progress = old.progress, error = old.error) ?: old })
        }
    }

    fun install(appId: String) {
        if (installJobs[appId]?.isActive == true) return
        val app = _state.value.apps.firstOrNull { it.app.id == appId }?.app ?: return
        val file = installer.fileFor(app) ?: return
        installJobs[appId] = scope.launch {
            update(appId) { it.copy(progress = AppProgress.Downloading(0, file.sizeBytes), error = null) }
            try {
                val path = downloader.download(file) { downloaded, total ->
                    update(appId) { it.copy(progress = AppProgress.Downloading(downloaded, total)) }
                }
                update(appId) { it.copy(progress = AppProgress.Installing) }
                installer.install(app, path)
                update(appId) { (item(it.app) ?: it).copy(progress = null) }
            } catch (e: StoreException) {
                update(appId) { it.copy(progress = null, error = e.error) }
            }
        }
    }

    fun cancel(appId: String) {
        installJobs[appId]?.cancel()
        update(appId) { it.copy(progress = null) }
    }

    fun open(appId: String) {
        _state.value.apps.firstOrNull { it.app.id == appId }?.let { installer.open(it.app) }
    }

    private fun item(app: StoreApp): AppItem? {
        val file = installer.fileFor(app) ?: return null
        val installed = installer.installedVersion(app)
        val action = when {
            installed == null -> AppAction.INSTALL
            Versions.isNewer(app.version, installed) -> AppAction.UPDATE
            else -> AppAction.OPEN
        }
        return AppItem(app = app, sizeBytes = file.sizeBytes, installedVersion = installed, action = action)
    }

    private fun update(appId: String, change: (AppItem) -> AppItem) {
        _state.update { current -> current.copy(apps = current.apps.map { if (it.app.id == appId) change(it) else it }) }
    }
}
