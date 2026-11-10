package ai.prism.ui.components

import androidx.compose.runtime.Composable

/** Сохранение изображения или видео в выбранное пользователем место (вне каталога приложения). */
fun interface ImageExporter {
    fun export(bytes: ByteArray, fileName: String)
}

/**
 * [mimeType] — тип сохраняемых файлов (для системного выбора места на Android).
 * [onResult] — `true`, если файл сохранён; `false` — ошибка. Отмена выбора места ничего не вызывает.
 */
@Composable
expect fun rememberImageExporter(mimeType: String = "image/jpeg", onResult: (Boolean) -> Unit): ImageExporter
