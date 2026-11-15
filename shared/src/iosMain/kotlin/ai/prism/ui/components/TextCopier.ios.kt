@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package ai.prism.ui.components
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIPasteboard
@Composable
internal actual fun rememberTextCopier(): (String) -> Boolean = remember {
    { text -> UIPasteboard.generalPasteboard.string = text; true }
}
