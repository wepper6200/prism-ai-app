package ai.prism.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.FileDialog
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
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

            override fun hasClipboardImage(): Boolean = runCatching {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) || clipboardFiles(clipboard).isNotEmpty()
            }.getOrDefault(false)

            override fun pasteImages() {
                val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return
                // Скопированная картинка (снимок экрана, «Копировать изображение» в браузере) или файлы из проводника.
                val image = runCatching {
                    if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) clipboard.getData(DataFlavor.imageFlavor) as? Image else null
                }.getOrNull()
                val files = if (image == null) clipboardFiles(clipboard) else emptyList()
                if (image == null && files.isEmpty()) return
                scope.launch {
                    val results = withContext(Dispatchers.IO) { if (image != null) listOf(pngOf(image)) else files.map(::readOrNull) }
                    val ok = results.filterNotNull()
                    if (ok.isNotEmpty()) picked(ok)
                    if (ok.size < results.size) failed()
                }
            }
        }
    }
}

/** Файлы изображений, скопированные в проводнике. */
private fun clipboardFiles(clipboard: Clipboard): List<File> = runCatching {
    if (!clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) return emptyList()
    (clipboard.getData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty()
        .filterIsInstance<File>()
        .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
}.getOrDefault(emptyList())

/** Картинка из буфера обмена в PNG (на белом фоне: прозрачность не нужна для JPEG, в который сохраняется фото). */
private fun pngOf(image: Image): ByteArray? = runCatching {
    val width = image.getWidth(null)
    val height = image.getHeight(null)
    if (width <= 0 || height <= 0) return null
    val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = buffered.createGraphics()
    try {
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        graphics.drawImage(image, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    ByteArrayOutputStream().use { out ->
        ImageIO.write(buffered, "png", out)
        out.toByteArray()
    }
}.getOrNull()

private fun readOrNull(file: File): ByteArray? = try {
    file.readBytes()
} catch (e: IOException) {
    null
}

private val EXTENSIONS = listOf("jpg", "jpeg", "png", "bmp", "gif")
