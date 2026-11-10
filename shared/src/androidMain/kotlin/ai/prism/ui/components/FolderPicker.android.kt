package ai.prism.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberFolderPicker(onPicked: (String) -> Unit): FolderPicker {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Доступ к папке сохраняется после перезапуска приложения.
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            picked(uri.toString())
        }
    }
    return remember(launcher) {
        FolderPicker {
            try {
                launcher.launch(null)
            } catch (e: ActivityNotFoundException) {
                // На устройстве нет системного выбора папок.
            }
        }
    }
}
