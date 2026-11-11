package ai.prism.ui.components

import androidx.compose.runtime.Composable

/** Разрешение на микрофон: на Android спрашивается у пользователя, на Desktop не нужно. */
fun interface MicrophonePermission {
    /** Вызывает [onGranted], если разрешение есть или пользователь его дал, иначе [onDenied]. */
    fun withPermission(onGranted: () -> Unit, onDenied: () -> Unit)
}

@Composable
expect fun rememberMicrophonePermission(): MicrophonePermission
