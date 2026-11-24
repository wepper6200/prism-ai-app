package ai.prism.service.agent

import ai.prism.domain.agent.*
import ai.prism.domain.inference.PromptImage
import ai.prism.domain.repository.ImageStore

/** One reply owns the selected window and its most recent observation. */
class WindowSession(
    initialWindow: DesktopWindow?,
    private val control: WindowControl,
    private val approvalImages: ImageStore? = null,
    val automatic: Boolean = false,
) {
    val wholeDesktop = initialWindow == null
    private var available = emptyList<DesktopWindow>()
    private var elements = emptyList<WindowElement>()
    private var observedWindow = initialWindow
    private data class Draft(val handle: Long, val elementId: String, val text: String, val requiresVisualCheck: Boolean)
    private var draft: Draft? = null
    fun submitDraftCall(): ToolCall? {
        val pendingDraft = draft ?: return null
        val index = elements.indexOfFirst { it.id == pendingDraft.elementId && "submit_text" in it.actions }
        return if (index >= 0) ToolCall("window_control", mapOf("action" to "submit_text", "element" to (index + 1).toString())) else null
    }

    val canSubmitDraft: Boolean get() = submitDraftCall() != null
    fun isMessageDraft(): Boolean {
        val element = elements.firstOrNull { it.id == draft?.elementId } ?: return false
        val label = element.name.lowercase()
        return label.contains("сообщен") || label.contains("message")
    }
    val needsVisualDraftCheck: Boolean get() = draft?.requiresVisualCheck == true
    var destinationChanged: Boolean = false
        private set
    private val captures = mutableListOf<String>()

    suspend fun release(store: ImageStore) {
        if (captures.isNotEmpty()) store.delete(captures.toList())
        captures.clear()
    }

    suspend fun execute(call: ToolCall, approve: suspend (PendingAction) -> Boolean, newId: () -> String,
        visionImages: ImageStore? = null,
    ): ToolOutcome {
        val tool = AgentTool.WINDOW_CONTROL
        val action = call.args["action"]?.trim()
        if (action == "list_windows" && wholeDesktop) return listWindows()
        if (action == "use_window" && wholeDesktop) {
            if (needsVisualDraftCheck) throw WorkspaceException("Finish or decline confirmation of the open draft before choosing another window.")
            val index = call.args["window"]?.trim()?.toIntOrNull()?.minus(1)
            observedWindow = index?.let(available::getOrNull) ?: throw WorkspaceException("Choose a window number from list_windows.")
            draft = null
            elements = emptyList()
            return observe()
        }
        if (action == "screenshot") {
            val store = visionImages ?: throw WorkspaceException("A downloaded vision module is needed for a window snapshot.")
            val image = try { store.save(control.capture(target())) }
                catch (e: ai.prism.domain.repository.ImageException) { throw WorkspaceException("Unable to decode the window snapshot", e) }
            captures += image.fileName
            return ToolOutcome("Snapshot of the selected window. Use inspect to discover actionable controls.",
                AgentStep(tool, target().title, "Снимок выбранного окна", AgentStepStatus.DONE),
                listOf(PromptImage(image.id, store.path(image.fileName))))
        }
        if (action == "inspect") return observe()
        val operation = WindowOperation.entries.firstOrNull { it.wireName == action }
            ?: throw WorkspaceException("Unknown window action. Start with inspect.")
        val index = call.args["element"]?.trim()?.toIntOrNull()?.minus(1)
        val element = index?.let(elements::getOrNull)?.takeIf { operation.wireName in it.actions }
            ?: throw WorkspaceException("Use a control number and action from the latest window snapshot.")
        val currentWindow = target()
        if (draft?.requiresVisualCheck == true && operation in setOf(WindowOperation.SET_TEXT, WindowOperation.INVOKE)) {
            throw WorkspaceException("The keyboard already typed a draft. Do not type again or invoke a button. Use submit_text to ask the user to verify the visible draft if sending was requested.")
        }
        val input = when (operation) {
            WindowOperation.SET_TEXT -> call.args["text"]?.takeIf { it.isNotBlank() && it.length <= 1000 }
                ?: throw WorkspaceException("set_text requires nonempty text (at most 1000 characters).")
            WindowOperation.SUBMIT_TEXT -> draft?.takeIf { it.handle == currentWindow.handle && it.elementId == element.id }?.text
                ?: throw WorkspaceException("Enter is available only for the field where Prism verified the draft in this task.")
            else -> ""
        }
        return perform(currentWindow, element, operation, input, approve, newId)
    }

    private suspend fun perform(
        currentWindow: DesktopWindow,
        element: WindowElement,
        operation: WindowOperation,
        input: String,
        approve: suspend (PendingAction) -> Boolean,
        newId: () -> String,
        previousTitle: String? = null,
    ): ToolOutcome {
        val tool = AgentTool.WINDOW_CONTROL
        val title = element.name.ifBlank { element.kind }
        val requiresVisualCheck = operation == WindowOperation.SUBMIT_TEXT && draft?.requiresVisualCheck == true
        if (automatic && requiresVisualCheck) throw WorkspaceException("Черновик нужно сверить вручную.")
        val changesInput = operation in setOf(WindowOperation.INVOKE, WindowOperation.SET_TEXT, WindowOperation.SUBMIT_TEXT)
        if (changesInput) {
            val verb = when (operation) {
                WindowOperation.INVOKE -> "Нажать"
                WindowOperation.SUBMIT_TEXT -> "Нажать Enter (может отправить сообщение)"
                else -> "Ввести текст"
            }
            val description = buildString {
                append(currentWindow.app).append(" · ").append(currentWindow.title).append("\n").append(verb).append(" — ").append(title)
                if (previousTitle != null) append("\n\nЗаголовок изменился. Было: ").append(previousTitle)
                if (input.isNotEmpty()) append("\n\n").append(input)
                if (requiresVisualCheck) append("\n\nПодтверждайте, только если этот текст виден в поле и открыт нужный получатель. Enter может отправить сообщение.")
                if (input.isNotEmpty()) append("\n\nЕсли Windows не откроет окно сама, переключитесь в него сразу после подтверждения.")
            }
            val preview = if (requiresVisualCheck) approvalPreview(currentWindow) else null
            if (!approve(PendingAction(newId(), tool, title, description, screenPreview = preview))) {
                return ToolOutcome("The user declined this action.", AgentStep(tool, title, description, AgentStepStatus.REJECTED))
            }
        }
        elements = emptyList()
        val result = try {
            if (requiresVisualCheck) {
                control.submitVisuallyChecked(currentWindow, element, input)
                WindowActionResult.DONE
            } else control.actWithResult(currentWindow, element, operation, input)
        } catch (e: WorkspaceException) {
            val refreshed = try { inspect() } catch (_: WorkspaceException) { "Window unavailable. Inspect it later." }
            val refreshedElement = elements.firstOrNull { it.id == element.id && it.kind == element.kind && operation.wireName in it.actions }
            if (e is WindowTitleChanged && automatic) destinationChanged = true
            if (!automatic && e is WindowTitleChanged && changesInput && previousTitle == null && refreshedElement != null) {
                return perform(target(), refreshedElement, operation, input, approve, newId, previousTitle = currentWindow.title)
            }
            if (changesInput && e !is WindowTitleChanged) draft = null
            val error = e.message.orEmpty()
            return ToolOutcome("Action failed: " + clean(error) + "\nLatest window snapshot:\n" + refreshed,
                AgentStep(tool, title, error, AgentStepStatus.FAILED))
        }
        if (changesInput) draft = null
        val typedUnverified = result == WindowActionResult.TYPED_UNVERIFIED
        if (operation == WindowOperation.SET_TEXT) draft = Draft(currentWindow.handle, element.id, input, typedUnverified)
        val observed = try { inspect() } catch (_: WorkspaceException) {
            "The action ran, but the selected window is no longer readable. Do not assume the intended result."
        }
        val detail = when (operation) {
            WindowOperation.INVOKE -> "Активирован элемент окна"
            WindowOperation.SET_TEXT -> if (typedUnverified) "Клавиши набраны. Проверьте отображаемый черновик." else "Текст подтверждён в поле"
            WindowOperation.SUBMIT_TEXT -> if (requiresVisualCheck) "Нажата клавиша Enter после подтверждения черновика пользователем" else "Нажата клавиша Enter в поле с проверенным текстом"
            WindowOperation.SCROLL_UP -> "Прокрутка вверх"
            WindowOperation.SCROLL_DOWN -> "Прокрутка вниз"
        }
        val note = when {
            typedUnverified -> "Keyboard input completed but the accessibility provider could not read the draft. Do not type it again. For a send request use submit_text, which asks the user to visually check the draft and recipient.\n"
            operation == WindowOperation.SET_TEXT -> "Text is present in the draft field. It has not been sent. If the task requests sending, use the visible Send button or submit_text for this field if available.\n"
            else -> ""
        }
        return ToolOutcome(note + observed, AgentStep(tool, title, detail, AgentStepStatus.DONE), inputPerformed = changesInput)
    }

    private suspend fun approvalPreview(window: DesktopWindow): String? {
        val store = approvalImages ?: return null
        return try {
            store.save(control.capture(window)).fileName.also { captures += it }
        } catch (_: WorkspaceException) {
            null
        } catch (_: ai.prism.domain.repository.ImageException) {
            null
        }
    }

    private fun target(): DesktopWindow = observedWindow ?: throw WorkspaceException("Call list_windows and use_window first.")

    private suspend fun observe(): ToolOutcome {
        val contents = inspect()
        return ToolOutcome(contents, AgentStep(AgentTool.WINDOW_CONTROL, target().title, "Окно прочитано", AgentStepStatus.DONE))
    }

    private suspend fun listWindows(): ToolOutcome {
        available = emptyList()
        available = control.windows().take(40)
        val listing = available.mapIndexed { index, item ->
            (index + 1).toString() + ". " + clean(item.app) + " — " + clean(item.title)
        }.joinToString("\n").ifBlank { "No accessible windows. Take a desktop screenshot if vision is available." }
        return ToolOutcome(listing, AgentStep(AgentTool.WINDOW_CONTROL, "", "Открытые окна перечислены", AgentStepStatus.DONE))
    }

    private fun clean(text: String): String = text.replace('<', '‹').replace('>', '›').replace('\n', ' ').replace('\r', ' ')

    private suspend fun inspect(): String {
        elements = emptyList()
        val requestedWindow = target()
        val observation = control.inspect(requestedWindow)
        if (automatic && observation.title != requestedWindow.title) destinationChanged = true
        observedWindow = requestedWindow.copy(title = observation.title)
        elements = observation.elements
        return elements.mapIndexed { index, element ->
            val name = element.name.replace('<', '‹').replace('>', '›').replace('\n', ' ').replace('\r', ' ')
            val actions = element.actions.filter {
                when (it) {
                    "submit_text" -> draft?.elementId == element.id
                    "set_text", "invoke" -> draft?.requiresVisualCheck != true
                    else -> true
                }
            }
            "${index + 1}. ${element.kind}: $name" +
                if (actions.isEmpty()) "" else " [${actions.joinToString()}]"
        }.joinToString("\n").ifEmpty { "No accessible controls were found in the selected window." }
    }
}
