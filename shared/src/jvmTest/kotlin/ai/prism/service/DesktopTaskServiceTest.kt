package ai.prism.service

import ai.prism.domain.agent.*
import ai.prism.testing.FakeInferenceEngine
import ai.prism.testing.TestApp
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopTaskServiceTest {
    private val editor = DesktopWindow(1, 20, "100", "Editor", "editor")
    private val messenger = DesktopWindow(2, 21, "101", "Chat", "messenger")

    private inner class Windows : WindowControl {
        val inspections = mutableListOf<DesktopWindow>()
        val mutations = mutableListOf<DesktopWindow>()
        override suspend fun windows() = listOf(editor, messenger)
        override suspend fun inspect(window: DesktopWindow): WindowObservation {
            inspections += window
            return WindowObservation(window.title,
                listOf(WindowElement("${window.handle}-edit", "Message", "Edit", listOf("set_text"))))
        }
        override suspend fun act(window: DesktopWindow, element: WindowElement, operation: WindowOperation, text: String) {
            assertEquals("${window.handle}-edit", element.id)
            mutations += window
        }
    }

    @Test
    fun proseWithoutInputNeverClaimsSendingAndTheNextTaskCanRun() = runBlocking {
        val windows = Windows()
        val engine = FakeInferenceEngine(reply = listOf("Я отправил сообщение."))
        val app = TestApp(engine = engine, windowControl = windows)
        val chat = app.chats.createChat()
        repeat(2) {
            assertTrue(app.generation.sendToWindow(chat.id, "Отправь сообщение", messenger))
            val reply = app.chats.messagesSnapshot(chat.id).last()
            assertContains(reply.content, "Задача не выполнена")
            assertFalse(reply.content.contains("Я отправил"))
            assertEquals(AgentTool.WINDOW_CONTROL, reply.steps.single().tool)
        }
        assertEquals(listOf(messenger, messenger), windows.inspections)
        assertTrue(windows.mutations.isEmpty())
        assertEquals(6, engine.requests.size)
    }

    @Test
    fun wholeDesktopListsAndSelectsWindowsWithoutReusingElementsAcrossThem() = runBlocking {
        val windows = Windows()
        val engine = FakeInferenceEngine()
        val app = TestApp(engine = engine, windowControl = windows)
        val chat = app.chats.createChat()
        fun reply(body: String) = engine.queuedReplies.addLast(listOf("<tool name=\"window_control\">$body</tool>"))
        reply("<action>use_window</action><window>1</window>")
        reply("<action>use_window</action><window>2</window>")
        reply("<action>set_text</action><element>1</element><text>Привет!</text>")
        engine.queuedReplies += listOf("Сообщение отправлено.")
        assertTrue(app.generation.sendToWindow(chat.id, "Введи текст во втором окне", null))
        assertEquals(listOf(editor, messenger), windows.inspections)
        val pending = assertNotNull(app.generation.active.value?.pending)
        assertContains(pending.detail, "Chat")
        assertContains(pending.detail, "Привет!")
        assertTrue(windows.mutations.isEmpty())
        app.generation.resolvePending(pending.id, true)
        assertEquals(listOf(messenger), windows.mutations)
        val result = app.chats.messagesSnapshot(chat.id).last().content
        assertContains(result, "Текст подтверждён в поле")
        assertFalse(result.contains("отправлено"))
    }

    @Test
    fun opaqueTypingInSendRequestPausesForVisualApprovalEvenIfModelStopsEarly() = runBlocking {
        val editorField = WindowElement("editor", "Сообщение", "Edit", listOf("set_text", "submit_text"))
        var typed = 0
        var submitted = 0
        val windows = object : WindowControl {
            override suspend fun windows() = listOf(messenger)
            override suspend fun inspect(window: DesktopWindow) = WindowObservation(window.title, listOf(editorField))
            override suspend fun act(window: DesktopWindow, element: WindowElement, operation: WindowOperation, text: String) {
                error("Must route opaque drafts through visual confirmation")
            }
            override suspend fun actWithResult(window: DesktopWindow, element: WindowElement, operation: WindowOperation, text: String): WindowActionResult {
                assertEquals(WindowOperation.SET_TEXT, operation)
                assertEquals("Привет", text)
                typed++
                return WindowActionResult.TYPED_UNVERIFIED
            }
            override suspend fun submitVisuallyChecked(window: DesktopWindow, element: WindowElement, text: String) {
                assertEquals(messenger, window)
                assertEquals("Привет", text)
                submitted++
            }
        }
        val engine = FakeInferenceEngine()
        engine.queuedReplies += listOf("<tool name=\"window_control\"><action>set_text</action><element>1</element><text>Привет</text></tool>")
        engine.queuedReplies += listOf("Я отправил сообщение.")
        val app = TestApp(engine = engine, windowControl = windows)
        val chat = app.chats.createChat()
        assertTrue(app.generation.sendToWindow(chat.id, "Отправь сообщение Привет", messenger))
        app.generation.resolvePending(assertNotNull(app.generation.active.value?.pending).id, true)
        val confirm = assertNotNull(app.generation.active.value?.pending)
        assertContains(confirm.detail, "получатель")
        assertEquals(1, typed)
        assertEquals(0, submitted)
        app.generation.resolvePending(confirm.id, true)
        assertEquals(1, submitted)
        val reply = app.chats.messagesSnapshot(chat.id).last().content
        assertContains(reply, "после подтверждения черновика")
        assertFalse(reply.contains("Я отправил"))
    }

    @Test
    fun automaticModeSubmitsVerifiedDraftAndStopsAtOpaqueDraftOrDifferentTitle() = runBlocking {
        val field = WindowElement("editor", "Сообщение", "Edit", listOf("set_text", "submit_text"))
        for (scenario in listOf("verified", "opaque", "changed")) {
            val operationLog = mutableListOf<WindowOperation>()
            val control = object : WindowControl {
                override suspend fun windows() = listOf(messenger)
                override suspend fun inspect(window: DesktopWindow) = WindowObservation(window.title, listOf(field))
                override suspend fun act(window: DesktopWindow, element: WindowElement, operation: WindowOperation, text: String) = Unit
                override suspend fun actWithResult(window: DesktopWindow, element: WindowElement, operation: WindowOperation, text: String): WindowActionResult {
                    assertEquals(messenger, window)
                    assertEquals("Привет", text)
                    operationLog += operation
                    if (scenario == "changed") throw WindowTitleChanged()
                    return if (scenario == "opaque") WindowActionResult.TYPED_UNVERIFIED else WindowActionResult.DONE
                }
                override suspend fun submitVisuallyChecked(window: DesktopWindow, element: WindowElement, text: String) {
                    error("Unattended mode cannot substitute for a visual confirmation")
                }
            }
            val engine = FakeInferenceEngine()
            engine.queuedReplies += listOf("<tool name=\"window_control\"><action>set_text</action><element>1</element><text>Привет</text></tool>")
            engine.queuedReplies += listOf("Готово.")
            val app = TestApp(engine = engine, windowControl = control)
            val chat = app.chats.createChat()
            assertTrue(app.generation.sendToWindow(chat.id, "Отправь сообщение Привет", messenger, automatic = true))
            assertNull(app.generation.active.value, scenario)
            val reply = app.chats.messagesSnapshot(chat.id).last().content
            if (scenario == "verified") {
                assertEquals(listOf(WindowOperation.SET_TEXT, WindowOperation.SUBMIT_TEXT), operationLog)
                assertContains(reply, "проверенным текстом")
            } else {
                assertEquals(listOf(WindowOperation.SET_TEXT), operationLog)
                assertContains(reply, if (scenario == "opaque") "Черновик можно проверить" else "изменился заголовок")
            }
        }
    }
}
