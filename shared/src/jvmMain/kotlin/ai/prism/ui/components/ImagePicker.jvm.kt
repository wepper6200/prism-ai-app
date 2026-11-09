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
actual fun rememberImagePicker(onPicked: (List<ByteArray>) -> Unit, onFailed: () -> Unit): ImagePicker {
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onFailed)
    return remember {
        object : ImagePicker {
            override val canTakePhoto: Boolean = false

            override fun pickImages() {
                val dialog = FileDialog(null as Frame?, null, FileDialog.LOAD).apply {
                    isMultipleMode = true
                    // Фильтр работает на macOS/Linux; на Windows подсказка — через шаблон имени.
                    setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in EXTENSIONS }
                    file = EXTENSIONS.joinToString(";") { "*.$it" }
                    isVisible = true
                }
                val files = dialog.files.orEmpty().toList()
                if (files.isEmpty()) return
                scope.launch {
                    val results = withContext(Dispatchers.IO) { files.map(::readOrNull) }
                    val ok = results.filterNotNull()
                    if (ok.isNotEmpty()) picked(ok)
                    if (ok.size < results.size) failed()
                }
            }

            override fun takePhoto() = Unit
        }
    }
}

private fun readOrNull(file: File): ByteArray? = try {
    file.readBytes()
} catch (e: IOException) {
    null
}

private val EXTENSIONS = listOf("jpg", "jpeg", "png", "bmp", "gif")
