package ai.prism.service.update

import ai.prism.core.BuildInfo
import ai.prism.domain.update.AppRelease
import ai.prism.domain.update.AppUpdater
import ai.prism.domain.update.AppVersion
import ai.prism.domain.update.UpdateError
import ai.prism.domain.update.UpdateException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val downloadedBytes: Long, val totalBytes: Long) : UpdateState

    /** Android — ждёт подтверждения в системном окне; Windows — перезапускается. */
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val error: UpdateError, val release: AppRelease?) : UpdateState
}

/**
 * Проверка, загрузка и установка новой версии по кнопке в настройках. Живёт дольше экрана настроек:
 * загрузка продолжается, если пользователь ушёл в чат.
 */
class UpdateService(
    private val updater: AppUpdater,
    private val scope: CoroutineScope,
    private val currentVersion: String = BuildInfo.VERSION_NAME,
) {
    val supported: Boolean get() = updater.supported

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null

    fun check() {
        if (!supported || job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val release = updater.latestRelease()
                if (AppVersion.isNewer(release.version, currentVersion)) {
                    UpdateState.Available(release)
                } else {
                    UpdateState.UpToDate(currentVersion)
                }
            } catch (e: UpdateException) {
                UpdateState.Failed(e.error, null)
            }
        }
    }

    /** Скачивает и ставит найденную версию; повторное нажатие после ошибки или отмены начинает заново (с докачкой). */
    fun install() {
        if (job?.isActive == true) return
        val release = when (val current = _state.value) {
            is UpdateState.Available -> current.release
            is UpdateState.Installing -> current.release
            is UpdateState.Failed -> current.release
            else -> null
        } ?: return
        val asset = release.asset
        if (asset == null) {
            _state.value = UpdateState.Failed(UpdateError.NO_PACKAGE, release)
            return
        }
        job = scope.launch {
            _state.value = UpdateState.Downloading(release, 0, asset.sizeBytes)
            try {
                val path = updater.download(asset) { downloaded, total ->
                    _state.value = UpdateState.Downloading(release, downloaded, total)
                }
                _state.value = UpdateState.Installing(release)
                updater.install(path, release.version)
            } catch (e: UpdateException) {
                _state.value = UpdateState.Failed(e.error, release)
            }
        }
    }

    /** Останавливает загрузку; скачанная часть остаётся для докачки. */
    fun cancel() {
        val current = _state.value
        job?.cancel()
        if (current is UpdateState.Downloading) _state.value = UpdateState.Available(current.release)
    }
}
