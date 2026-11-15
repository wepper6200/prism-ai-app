package ai.prism.ui.components
import androidx.compose.runtime.Composable
/** True only when the platform accepted the text. */
@Composable
internal expect fun rememberTextCopier(): (String) -> Boolean
