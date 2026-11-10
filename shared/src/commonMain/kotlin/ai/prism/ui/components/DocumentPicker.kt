package ai.prism.ui.components

import ai.prism.presentation.chat.PickedDocument
import androidx.compose.runtime.Composable

/** Выбор документов (PDF, DOCX, TXT, MD) средствами платформы. */
fun interface DocumentPicker {
    fun pickDocuments()
}

/**
 * [onPicked] получает выбранные файлы (чтение — вне главного потока),
 * [onFailed] вызывается, если какой-то файл прочитать не удалось.
 */
@Composable
expect fun rememberDocumentPicker(onPicked: (List<PickedDocument>) -> Unit, onFailed: () -> Unit): DocumentPicker

/** Расширения файлов, которые понимает поиск по файлам. */
internal val DOCUMENT_EXTENSIONS = listOf("pdf", "docx", "txt", "md", "markdown", "csv", "log")
