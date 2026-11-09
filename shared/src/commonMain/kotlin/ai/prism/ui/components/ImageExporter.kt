package ai.prism.ui.components

import androidx.compose.runtime.Composable

/** Сохранение изображения в выбранное пользователем место (вне каталога приложения). */
fun interface ImageExporter {
    fun export(bytes: ByteArray, fileName: String)
}

/** [onResult] — `true`, если файл сохранён; `false` — ошибка. Отмена выбора места ничего не вызывает. */
@Composable
expect fun rememberImageExporter(onResult: (Boolean) -> Unit): ImageExporter
