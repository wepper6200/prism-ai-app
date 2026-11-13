package ai.prism.ui.components

import ai.prism.platform.CameraOutputProvider
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
actual fun rememberImagePicker(onPicked: (List<ByteArray>) -> Unit, onFailed: () -> Unit): ImagePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onFailed)
    // Имя файла снимка переживает пересоздание Activity, пока открыта камера.
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }

    fun read(uris: List<Uri>, deleteAfter: Boolean) {
        if (uris.isEmpty()) return
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    try {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (e: IOException) {
                        null
                    } catch (e: SecurityException) {
                        null
                    } finally {
                        if (deleteAfter) context.contentResolver.delete(uri, null, null)
                    }
                }
            }
            val ok = results.filterNotNull()
            if (ok.isNotEmpty()) picked(ok)
            if (ok.size < results.size) failed()
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        read(uris, deleteAfter = false)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val name = pendingPhoto
        pendingPhoto = null
        if (name != null) {
            val uri = CameraOutputProvider.uriFor(context, name)
            if (success) read(listOf(uri), deleteAfter = true) else context.contentResolver.delete(uri, null, null)
        }
    }

    return remember(context, gallery, camera) {
        object : ImagePicker {
            override val canTakePhoto: Boolean =
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

            override fun pickImages() {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }

            override fun takePhoto() {
                val name = "photo-${System.currentTimeMillis()}.jpg"
                pendingPhoto = name
                try {
                    camera.launch(CameraOutputProvider.uriFor(context, name))
                } catch (e: ActivityNotFoundException) {
                    // На устройстве нет приложения камеры.
                    pendingPhoto = null
                    failed()
                }
            }

            private val clipboard: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)

            override fun hasClipboardImage(): Boolean = try {
                val description = clipboard?.primaryClipDescription
                description != null && (0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("image/") }
            } catch (e: SecurityException) {
                false
            }

            override fun pasteImages() {
                val clip = try {
                    clipboard?.primaryClip
                } catch (e: SecurityException) {
                    null
                } ?: return
                // Скопированное изображение лежит в буфере как адрес content://; читаем его, как фото из галереи.
                read((0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.take(MAX_PICK), deleteAfter = false)
            }
        }
    }
}

private const val MAX_PICK = 4
