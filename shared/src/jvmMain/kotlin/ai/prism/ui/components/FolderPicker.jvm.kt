package ai.prism.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import javax.swing.JFileChooser

@Composable
actual fun rememberFolderPicker(onPicked: (String) -> Unit): FolderPicker {
    val picked by rememberUpdatedState(onPicked)
    return remember {
        FolderPicker {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.absolutePath?.let(picked)
            }
        }
    }
}
