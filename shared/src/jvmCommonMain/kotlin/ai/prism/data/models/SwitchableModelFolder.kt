package ai.prism.data.models

import ai.prism.data.settings.KeyValueStore
import ai.prism.domain.model.ModelFolder
import ai.prism.domain.model.ModelFolderError
import ai.prism.domain.model.ModelFolderState
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.repository.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** Доступ к общей папке телефона (Android 11+: «Доступ ко всем файлам»). */
interface SharedStorageAccess {
    val supported: Boolean

    fun granted(): Boolean

    /** Открывает экран, где пользователь даёт доступ. */
    fun request()
}

/**
 * Перенос моделей между внутренней папкой приложения и общей папкой (`Download/PrismAI/models`)
 * ([ModelFolderMover]). Уже лежащие в новой папке файлы того же размера не копируются — так после переустановки
 * приложение находит модели, скачанные раньше.
 */
class SwitchableModelFolder(
    private val storage: FileModelStorage,
    private val internalDir: File,
    private val sharedDir: File,
    private val sharedLabel: String,
    private val access: SharedStorageAccess,
    private val store: KeyValueStore,
    private val repository: ModelRepository,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : ModelFolder {

    private val _state = MutableStateFlow(
        ModelFolderState(
            supported = access.supported,
            shared = storage.directory.absoluteFile == sharedDir.absoluteFile,
            sharedPath = sharedLabel,
        ),
    )
    override val state: StateFlow<ModelFolderState> = _state.asStateFlow()

    override fun setShared(shared: Boolean) {
        val current = _state.value
        if (!current.supported || current.moving || current.shared == shared) return
        if (shared && !access.granted()) {
            access.request()
            _state.update { it.copy(error = ModelFolderError.NEED_ACCESS) }
            return
        }
        if (repository.models.value.any { it.status is ModelStatus.Downloading || it.status == ModelStatus.Verifying }) {
            _state.update { it.copy(error = ModelFolderError.DOWNLOADS_ACTIVE) }
            return
        }
        val from = storage.directory
        val to = if (shared) sharedDir else internalDir
        _state.update { it.copy(moving = true, movedBytes = 0, totalBytes = 0, error = null) }
        scope.launch {
            val error = withContext(ioContext) {
                try {
                    move(from, to)
                    null
                } catch (e: ModelFolderMover.NotEnoughSpaceException) {
                    ModelFolderError.NOT_ENOUGH_SPACE
                } catch (e: IOException) {
                    ModelFolderError.FAILED
                }
            }
            if (error == null) {
                storage.directory = to
                store.putString(KEY_SHARED, shared.toString())
            }
            repository.refresh()
            _state.update {
                it.copy(moving = false, shared = if (error == null) shared else it.shared, error = error)
            }
        }
    }

    private fun move(from: File, to: File) {
        ModelFolderMover.move(
            from = from,
            to = to,
            onTotal = { total -> _state.update { it.copy(totalBytes = total) } },
            onProgress = { moved -> _state.update { it.copy(movedBytes = moved) } },
        )
    }

    companion object {
        /** Ключ настройки: модели в общей папке. */
        const val KEY_SHARED = "models_shared_folder"

        /** Папка моделей при запуске: общая, если её выбрали и доступ всё ещё есть. */
        fun initialDirectory(store: KeyValueStore, access: SharedStorageAccess, internalDir: File, sharedDir: File): File =
            if (store.getString(KEY_SHARED) == "true" && access.supported && access.granted()) sharedDir else internalDir
    }
}
