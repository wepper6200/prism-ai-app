package ai.prism.domain.agent

import kotlinx.serialization.Serializable

@Serializable
data class DesktopWindow(val handle: Long, val pid: Int, val started: String, val title: String, val app: String)

@Serializable
data class WindowElement(
    val id: String,
    val name: String,
    val kind: String,
    val actions: List<String>,
)

@Serializable
data class WindowObservation(val title: String, val elements: List<WindowElement>)

enum class WindowOperation(val wireName: String) {
    INVOKE("invoke"), SET_TEXT("set_text"), SUBMIT_TEXT("submit_text"), SCROLL_UP("scroll_up"), SCROLL_DOWN("scroll_down"),
}

enum class WindowActionResult { DONE, TYPED_UNVERIFIED }

class WindowTitleChanged : WorkspaceException("Заголовок окна изменился. Проверьте окно и подтвердите действие повторно.")

interface WindowControl {
    suspend fun windows(): List<DesktopWindow>
    suspend fun inspect(window: DesktopWindow): WindowObservation
    suspend fun capture(window: DesktopWindow): ByteArray = throw WorkspaceException("Window snapshots are unavailable")
    suspend fun act(window: DesktopWindow, element: WindowElement, operation: WindowOperation, text: String = "")
    suspend fun actWithResult(window: DesktopWindow, element: WindowElement, operation: WindowOperation, text: String): WindowActionResult {
        act(window, element, operation, text)
        return WindowActionResult.DONE
    }
    suspend fun submitVisuallyChecked(window: DesktopWindow, element: WindowElement, text: String) {
        throw WorkspaceException("Приложение не поддерживает подтверждение черновика.")
    }
}
