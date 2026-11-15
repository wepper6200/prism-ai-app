package ai.prism.ui.components
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
@Composable
internal actual fun rememberTextCopier(): (String) -> Boolean {
    val context = LocalContext.current
    return remember(context) { { text ->
        try { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Prism AI", text)); true } catch (_: Exception) { false }
    } }
}
