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

/**
 * Desktop: модели можно держать в любой папке (например, на другом диске). Внутри выбранной папки создаётся своя
 * [FOLDER_NAME] — при возврате в стандартную папку переносится только она. Перед переносом модели выгружаются
 * из памяти ([beforeMove]): Windows не даёт переместить открытый файл.
 */
class ChoosableModelFolder(
    private val storage: FileModelStorage,
    private val defaultDir: File,
    private val store: KeyValueStore,
    private val repository: ModelRepository,
    private val scope: CoroutineScope,
    unavailable: Boolean = false,
    private val beforeMove: suspend () -> Unit = {},
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : ModelFolder {

    private val _state = MutableStateFlow(
        ModelFolderState(
            canChoose = true,
            currentPath = storage.directory.absolutePath,
            isDefault = isDefault(storage.directory),
            error = if (unavailable) ModelFolderError.FOLDER_UNAVAILABLE else null,
        ),
    )
    override val state: StateFlow<ModelFolderState> = _state.asStateFlow()

    override fun setShared(shared: Boolean) = Unit

    override fun choose(path: String) {
        val picked = File(path).absoluteFile
        val target = if (picked.name == FOLDER_NAME) picked else File(picked, FOLDER_NAME)
        relocate(target, saved = target.absolutePath)
    }

    override fun resetToDefault() = relocate(defaultDir.absoluteFile, saved = "")

    private fun relocate(target: File, saved: String) {
        val current = _state.value
        if (current.moving || target.absoluteFile == storage.directory.absoluteFile) return
        if (repository.models.value.any { it.status is ModelStatus.Downloading || it.status == ModelStatus.Verifying }) {
            _state.update { it.copy(error = ModelFolderError.DOWNLOADS_ACTIVE) }
            return
        }
        val from = storage.directory
        _state.update { it.copy(moving = true, movedBytes = 0, totalBytes = 0, error = null) }
        scope.launch {
            beforeMove()
            val error = withContext(ioContext) {
                try {
                    ModelFolderMover.move(
                        from = from,
                        to = target,
                        onTotal = { total -> _state.update { it.copy(totalBytes = total) } },
                        onProgress = { moved -> _state.update { it.copy(movedBytes = moved) } },
                    )
                    null
                } catch (e: ModelFolderMover.NotEnoughSpaceException) {
                    ModelFolderError.NOT_ENOUGH_SPACE
                } catch (e: IOException) {
                    ModelFolderError.FAILED
                }
            }
            if (error == null) {
                storage.directory = target
                store.putString(KEY_FOLDER, saved)
            }
            repository.refresh()
            _state.update {
                it.copy(
                    moving = false,
                    currentPath = storage.directory.absolutePath,
                    isDefault = isDefault(storage.directory),
                    error = error,
                )
            }
        }
    }

    private fun isDefault(dir: File) = dir.absoluteFile == defaultDir.absoluteFile

    companion object {
        /** Ключ настройки: путь к папке моделей; пусто — стандартная папка. */
        const val KEY_FOLDER = "models_folder"
        const val FOLDER_NAME = "PrismAI Models"

        /**
         * Папка моделей при запуске и признак, что выбранная папка недоступна (тогда — стандартная).
         * Папку на отключённом диске не создаём: иначе модели «пропали» бы в пустой новой папке.
         */
        fun initialDirectory(store: KeyValueStore, defaultDir: File): Pair<File, Boolean> {
            val saved = store.getString(KEY_FOLDER)?.takeIf { it.isNotBlank() }?.let(::File) ?: return defaultDir to false
            return if (saved.isDirectory) saved to false else defaultDir to true
        }
    }
}
