package ai.prism.ui.components
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
@Composable
internal actual fun rememberTextCopier(): (String) -> Boolean = remember {
    { text -> try { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null); true } catch (_: Exception) { false } }
}
