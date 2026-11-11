package ai.prism.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberMicrophonePermission(): MicrophonePermission {
    val context = LocalContext.current
    // Что сделать после ответа на запрос разрешения (запрос открывает системный диалог).
    val pending = remember { arrayOfNulls<Pair<() -> Unit, () -> Unit>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val callbacks = pending[0]
        pending[0] = null
        if (granted) callbacks?.first?.invoke() else callbacks?.second?.invoke()
    }
    return remember(context, launcher) {
        MicrophonePermission { onGranted, onDenied ->
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                onGranted()
            } else {
                pending[0] = onGranted to onDenied
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
