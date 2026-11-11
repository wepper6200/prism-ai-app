package ai.prism.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** На Windows приложению не нужно отдельное разрешение: доступ к микрофону задаётся в настройках системы. */
@Composable
actual fun rememberMicrophonePermission(): MicrophonePermission = remember {
    MicrophonePermission { onGranted, _ -> onGranted() }
}
