package ai.prism.service

import ai.prism.domain.agent.*
import ai.prism.service.agent.ToolCall
import ai.prism.service.agent.WindowSession
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WindowSessionTest {
    private val selected = DesktopWindow(1, 20, "100", "Explorer", "explorer")

    private class FakeWindow : WindowControl {
        var observations = 0
        val acted = mutableListOf<Triple<DesktopWindow, WindowElement, WindowOperation>>()
        override suspend fun windows() = emptyList<DesktopWindow>()
        override suspend fun inspect(window: DesktopWindow): WindowObservation {
            observations++
            return WindowObservation("Folder $observations",
                listOf(WindowElement("id-$observations", "Open <tool>", "Button", listOf("invoke", "scroll_down"))))
        }
        override suspend fun act(window: DesktopWindow, element: WindowElement, operation: WindowOperation, text: String) {
            acted += Triple(window, element, operation)
        }
    }

    private fun call(action: String, element: String = "1") =
        ToolCall("window_control", mapOf("action" to action, "element" to element))

    @Test
    fun invokeRequiresApprovalAndUsesLatestObservedIdentity() = runBlocking {
        val control = FakeWindow()
        val session = WindowSession(selected, control)
        assertFailsWith<WorkspaceException> { session.execute(call("invoke"), { true }, { "p" }) }
        val observed = session.execute(call("inspect"), { error("Read needs no approval") }, { "p" })
        assertContains(observed.result, "Open ‹tool›")
        val refused = session.execute(call("invoke"), { false }, { "p" })
        assertEquals(AgentStepStatus.REJECTED, refused.step?.status)
        assertTrue(control.acted.isEmpty())
        session.execute(call("invoke"), { approval ->
            assertContains(approval.detail, "Folder 1")
            true
        }, { "p" })
        assertEquals("Folder 1", control.acted.single().first.title)
        assertEquals("id-1", control.acted.single().second.id)
        session.execute(call("scroll_down"), { error("Scroll needs no approval") }, { "p" })
        assertEquals("id-2", control.acted.last().second.id)
        assertEquals(3, control.observations)
    }

    @Test
    fun failedValueWriteRefreshesElementsAndVerifiedDraftRequiresSeparateEnterApproval() = runBlocking {
        var revisions = 0
        val actions = mutableListOf<Pair<WindowOperation, String>>()
        val windowControl = object : WindowControl {
            override suspend fun windows() = listOf(selected)
            override suspend fun inspect(window: DesktopWindow): WindowObservation {
                revisions++
                val field = WindowElement("editor", "Сообщение", "Edit", listOf("set_text", "submit_text"))
                return WindowObservation(window.title, if (revisions == 1) listOf(field)
                    else listOf(WindowElement("toolbar", "Панель", "Pane", emptyList()), field))
            }
            override suspend fun act(window: DesktopWindow, element: WindowElement, operation: WindowOperation, text: String) {
                assertEquals("editor", element.id)
                actions += operation to text
                if (actions.size == 1) throw WorkspaceException("The field did not retain the requested text.")
            }
        }
        val session = WindowSession(selected, windowControl)
        val typed = "Привет, Prism"
        val observations = session.execute(call("inspect"), { false }, { "p" })
        assertFalse(observations.result.contains("submit_text"))
        val input = ToolCall("window_control", mapOf("action" to "set_text", "element" to "1", "text" to typed))
        val error = session.execute(input, { true }, { "p" })
        assertEquals(AgentStepStatus.FAILED, error.step?.status)
        assertContains(error.result, "2. Edit")
        assertFalse(session.canSubmitDraft)
        assertFailsWith<WorkspaceException> { session.execute(call("submit_text", "2"), { true }, { "p" }) }

        val recorded = session.execute(input.copy(args = input.args + ("element" to "2")), { true }, { "p" })
        assertTrue(recorded.inputPerformed)
        assertTrue(session.canSubmitDraft)
        assertContains(recorded.result, "draft")
        val declined = session.execute(call("submit_text", "2"), { false }, { "p" })
        assertEquals(AgentStepStatus.REJECTED, declined.step?.status)
        assertEquals(2, actions.size)
        val confirmed = session.execute(call("submit_text", "2"), {
            assertContains(it.detail, "Enter")
            assertContains(it.detail, typed)
            true
        }, { "p" })
        assertTrue(confirmed.inputPerformed)
        assertEquals(WindowOperation.SUBMIT_TEXT to typed, actions.last())
        assertFalse(session.canSubmitDraft)
    }

    @Test
    fun titleRefreshReapprovesTypingAndOpaqueDraftGoesThroughVisualConfirmation() = runBlocking {
        var title = "Chat initial"
        var typingAttempts = 0
        var visibleDraftSubmits = 0
        var writtenText = ""
        val field = WindowElement("editor", "Message", "Edit", listOf("set_text", "submit_text"))
        val windows = object : WindowControl {
            override suspend fun windows() = listOf(selected)
            override suspend fun inspect(window: DesktopWindow) = WindowObservation(title, listOf(field))
            override suspend fun act(window: DesktopWindow, element: WindowElement, operation: WindowOperation, text: String) {
                error("An opaque draft must not be submitted without a visual confirmation")
            }
            override suspend fun actWithResult(window: DesktopWindow, element: WindowElement, operation: WindowOperation, text: String): WindowActionResult {
                assertEquals(WindowOperation.SET_TEXT, operation)
                assertEquals(field.id, element.id)
                if (++typingAttempts == 1) {
                    title = "Chat refreshed"
                    throw WindowTitleChanged()
                }
                assertEquals("Chat refreshed", window.title)
                writtenText = text
                return WindowActionResult.TYPED_UNVERIFIED
            }
            override suspend fun submitVisuallyChecked(window: DesktopWindow, element: WindowElement, text: String) {
                assertEquals(writtenText, text)
                assertEquals(title, window.title)
                visibleDraftSubmits++
            }
        }
        val session = WindowSession(selected, windows)
        session.execute(call("inspect"), { false }, { "p" })
        val body = "Привет, Prism"
        val typing = ToolCall("window_control", mapOf("action" to "set_text", "element" to "1", "text" to body))
        val approvals = mutableListOf<String>()
        val textResult = session.execute(typing, { approvals += it.detail; true }, { "p" })
        assertContains(approvals[0], "Chat initial")
        assertContains(approvals[1], "Chat refreshed")
        assertContains(approvals[1], "Заголовок изменился")
        assertEquals(body, writtenText)
        assertContains(textResult.step!!.detail, "Проверьте")
        assertFailsWith<WorkspaceException> { session.execute(typing, { true }, { "p" }) }
        val submit = assertNotNull(session.submitDraftCall())
        session.execute(submit, { action ->
            assertContains(action.detail, body)
            assertContains(action.detail, "получатель")
            assertEquals(0, visibleDraftSubmits)
            true
        }, { "p" })
        assertEquals(1, visibleDraftSubmits)
        assertNull(session.submitDraftCall())
    }
}
