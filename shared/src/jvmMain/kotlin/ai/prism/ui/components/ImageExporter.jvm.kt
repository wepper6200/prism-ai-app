package ai.prism.ui.components

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
import java.io.File
import java.io.IOException

@Composable
actual fun rememberImageExporter(onResult: (Boolean) -> Unit): ImageExporter {
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)
    return remember {
        ImageExporter { bytes, fileName ->
            val dialog = FileDialog(null as Frame?, null, FileDialog.SAVE).apply {
                file = fileName
                isVisible = true
            }
            val name = dialog.file ?: return@ImageExporter
            val target = File(dialog.directory, if (name.contains('.')) name else "$name.jpg")
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        target.writeBytes(bytes)
                        true
                    } catch (e: IOException) {
                        false
                    }
                }
                result(ok)
            }
        }
    }
}
