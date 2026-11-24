package ai.prism.data.agent

import ai.prism.domain.agent.WorkspaceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment

/** Сворачивает окна Prism AI на время [block] и возвращает их, когда снимок сделан. */
internal suspend fun <T> withPrismWindowsHidden(block: suspend () -> T): T = withContext(Dispatchers.IO) {
    if (GraphicsEnvironment.isHeadless()) throw WorkspaceException("screen control is not available: no screen")
    val frames = onUi { Frame.getFrames().filter { it.isVisible && it.extendedState and Frame.ICONIFIED == 0 } }
    onUi { frames.forEach { it.extendedState = it.extendedState or Frame.ICONIFIED } }
    try {
        delay(450L)
        block()
    } finally {
        onUi {
            frames.forEach { it.extendedState = it.extendedState and Frame.ICONIFIED.inv() }
            frames.lastOrNull()?.toFront()
        }
    }
}

private fun <T> onUi(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()
    var result: Result<T>? = null
    EventQueue.invokeAndWait { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}
