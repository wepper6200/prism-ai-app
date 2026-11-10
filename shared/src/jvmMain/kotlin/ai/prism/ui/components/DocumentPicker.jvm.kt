package ai.prism.ui.components

import ai.prism.presentation.chat.PickedDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.IOException

@Composable
actual fun rememberDocumentPicker(onPicked: (List<PickedDocument>) -> Unit, onFailed: () -> Unit): DocumentPicker {
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onFailed)
    return remember {
        DocumentPicker {
            val dialog = FileDialog(null as Frame?, null, FileDialog.LOAD).apply {
                isMultipleMode = true
                // Фильтр работает на macOS/Linux; на Windows подсказка — через шаблон имени.
                setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in DOCUMENT_EXTENSIONS }
                file = DOCUMENT_EXTENSIONS.joinToString(";") { "*.$it" }
                isVisible = true
            }
            val files = dialog.files.orEmpty().toList()
            if (files.isNotEmpty()) {
                scope.launch {
                    val results = withContext(Dispatchers.IO) {
                        files.map { file ->
                            try {
                                PickedDocument(file.name, file.readBytes())
                            } catch (e: IOException) {
                                null
                            }
                        }
                    }
                    val ok = results.filterNotNull()
                    if (ok.isNotEmpty()) picked(ok)
                    if (ok.size < results.size) failed()
                }
            }
        }
    }
}
