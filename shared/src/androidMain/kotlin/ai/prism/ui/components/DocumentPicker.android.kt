package ai.prism.ui.components

import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import ai.prism.presentation.chat.PickedDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
actual fun rememberDocumentPicker(onPicked: (List<PickedDocument>) -> Unit, onFailed: () -> Unit): DocumentPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onFailed)

    // Системный выбор файлов (Storage Access Framework): приложению не нужно разрешение на доступ к хранилищу.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    try {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                            ?: uri.lastPathSegment.orEmpty()
                        context.contentResolver.openInputStream(uri)?.use { PickedDocument(name, it.readBytes()) }
                    } catch (e: IOException) {
                        null
                    } catch (e: SecurityException) {
                        null
                    }
                }
            }
            val ok = results.filterNotNull()
            if (ok.isNotEmpty()) picked(ok)
            if (ok.size < results.size) failed()
        }
    }

    return remember(launcher) {
        DocumentPicker {
            try {
                launcher.launch(MIME_TYPES)
            } catch (e: ActivityNotFoundException) {
                failed()
            }
        }
    }
}

private val MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/plain",
    "text/markdown",
    "text/x-markdown",
    "text/csv",
    // Файлы .md часто приходят без своего типа; неподходящие отсеет проверка расширения.
    "application/octet-stream",
)
