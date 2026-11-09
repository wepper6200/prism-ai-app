package ai.prism.desktop

import ai.prism.resources.Res
import ai.prism.resources.app_name
import ai.prism.resources.prism_logo
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import javax.swing.SwingUtilities

/** Заголовок окна из строковых ресурсов. */
@Composable
fun prismWindowTitle(): String = stringResource(Res.string.app_name)

/** Иконка окна и панели задач. */
@Composable
fun prismWindowIcon(): Painter = painterResource(Res.drawable.prism_logo)

/**
 * Выполняет [block] в потоке Swing (главный поток Compose Desktop и Decompose)
 * и возвращает результат; исключения пробрасываются вызывающему.
 */
fun <T> runOnUiThread(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}
