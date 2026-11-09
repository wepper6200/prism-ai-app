package ai.prism.ui.components

import ai.prism.resources.Res
import ai.prism.resources.image_close
import ai.prism.resources.image_save
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource

/** Загрузка байтов сохранённого изображения по имени файла. */
typealias ImageBytesLoader = suspend (fileName: String) -> ByteArray?

/**
 * Небольшой кэш декодированных изображений: лента сообщений не декодирует их заново при прокрутке.
 * Используется только из главного потока (декодирование — в фоне, запись — после него).
 */
private object DecodedImages {
    private const val CAPACITY = 12
    private val cache = LinkedHashMap<String, ImageBitmap>()

    fun get(key: String): ImageBitmap? = cache.remove(key)?.also { cache[key] = it }

    fun put(key: String, bitmap: ImageBitmap) {
        cache.remove(key)
        cache[key] = bitmap
        while (cache.size > CAPACITY) cache.remove(cache.keys.first())
    }
}

@OptIn(ExperimentalResourceApi::class)
private suspend fun decode(fileName: String, load: ImageBytesLoader): ImageBitmap? {
    DecodedImages.get(fileName)?.let { return it }
    val bitmap = withContext(Dispatchers.Default) {
        load(fileName)?.let { bytes ->
            try {
                bytes.decodeToImageBitmap()
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    } ?: return null
    DecodedImages.put(fileName, bitmap)
    return bitmap
}

/** Изображение из хранилища приложения; пока оно декодируется, видна подложка. */
@Composable
fun StoredImage(
    fileName: String,
    load: ImageBytesLoader,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val bitmap by produceState(DecodedImages.get(fileName), fileName) {
        if (value == null) value = decode(fileName, load)
    }
    Box(modifier.background(placeholder)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Изображение на весь экран поверх приложения. [onSave] — кнопка «Сохранить», [message] — итог сохранения. */
@Composable
fun ImageViewer(
    fileName: String,
    load: ImageBytesLoader,
    contentDescription: String?,
    onDismiss: () -> Unit,
    onSave: (() -> Unit)? = null,
    message: String? = null,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("image_viewer"),
        ) {
            StoredImage(
                fileName = fileName,
                load = load,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                placeholder = Color.Black,
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(12.dp),
            ) {
                if (onSave != null) {
                    FilledTonalIconButton(onClick = onSave, modifier = Modifier.testTag("save_image")) {
                        Icon(Icons.Outlined.Download, contentDescription = stringResource(Res.string.image_save))
                    }
                }
                FilledTonalIconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.image_close))
                }
            }
            if (message != null) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(24.dp),
                ) {
                    Text(message, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }
    }
}
