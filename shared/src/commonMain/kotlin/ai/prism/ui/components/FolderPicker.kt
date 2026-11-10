package ai.prism.ui.components

import androidx.compose.runtime.Composable

/** Выбор папки проекта средствами платформы. */
fun interface FolderPicker {
    fun pickFolder()
}

/** [onPicked] получает адрес папки: путь на Desktop или URI дерева документов (с постоянным доступом) на Android. */
@Composable
expect fun rememberFolderPicker(onPicked: (String) -> Unit): FolderPicker
