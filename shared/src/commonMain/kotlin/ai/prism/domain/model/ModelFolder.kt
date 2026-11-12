package ai.prism.domain.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ModelFolderError {
    /** Нет доступа ко всем файлам — открыт экран разрешения, после него настройку нужно включить снова. */
    NEED_ACCESS,

    /** Идёт загрузка модели — перенос возможен после её окончания или паузы. */
    DOWNLOADS_ACTIVE,

    NOT_ENOUGH_SPACE,

    /** Не удалось скопировать файлы. */
    FAILED,

    /** Desktop: выбранная раньше папка недоступна (диск отключён) — сейчас используется стандартная. */
    FOLDER_UNAVAILABLE,
}

data class ModelFolderState(
    /** Можно ли хранить модели в общей папке на этой платформе (Android 11+). */
    val supported: Boolean = false,
    /** Модели лежат в общей папке и переживут удаление приложения. */
    val shared: Boolean = false,
    /** Общая папка, понятная пользователю (`Download/PrismAI`). */
    val sharedPath: String = "",
    /** Desktop: папку моделей можно выбрать любую (например, на другом диске). */
    val canChoose: Boolean = false,
    /** Папка, где сейчас лежат модели. */
    val currentPath: String = "",
    /** Модели в стандартной папке приложения. */
    val isDefault: Boolean = true,
    val moving: Boolean = false,
    val movedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: ModelFolderError? = null,
)

/**
 * Где хранятся модели: во внутренней памяти приложения (удаляется вместе с ним) или в общей папке телефона,
 * которая остаётся после удаления. После переустановки настройку включают снова — модели находятся в папке.
 */
interface ModelFolder {
    val state: StateFlow<ModelFolderState>

    /** Переносит скачанные модели в общую папку ([shared] = true) или обратно во внутреннюю память. */
    fun setShared(shared: Boolean)

    /** Desktop: переносит модели в выбранную папку (внутри неё — своя папка «PrismAI Models»). */
    fun choose(path: String) = Unit

    /** Desktop: возвращает модели в стандартную папку приложения. */
    fun resetToDefault() = Unit

    /** Платформа без выбора папки (Desktop: модели и так лежат вне программы). */
    object None : ModelFolder {
        override val state: StateFlow<ModelFolderState> = MutableStateFlow(ModelFolderState())

        override fun setShared(shared: Boolean) = Unit
    }
}
