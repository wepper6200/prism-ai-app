package ai.prism.presentation

import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.presentation.chats.DefaultChatListComponent
import ai.prism.presentation.chats.ChatListIntent
import ai.prism.testing.TestApp
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.destroy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatManagementTest {
    @Test
    fun undoPreservesHistoryAndDeletionCommitsAfterGracePeriod() = runTest {
        val app = TestApp()
        val id = app.chats.createChat().id
        app.chats.addMessage(id, MessageRole.USER, "Не потерять", MessageStatus.COMPLETE, null)
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        val component = DefaultChatListComponent(
            DefaultComponentContext(lifecycle = lifecycle), app.chats, app.generation,
            UnconfinedTestDispatcher(testScheduler), {}, {}, {}, {},
        )
        try {
            component.onIntent(ChatListIntent.DeleteChat(id))
            assertTrue(app.chats.chats.first().isEmpty())
            advanceTimeBy(1000)
            component.onIntent(ChatListIntent.UndoDelete)
            advanceTimeBy(7000)
            runCurrent()
            assertEquals(id, app.chats.chats.first().single().id)
            assertEquals("Не потерять", app.chats.messagesSnapshot(id).single().content)
            component.onIntent(ChatListIntent.DeleteChat(id))
            advanceTimeBy(6001)
            runCurrent()
            assertTrue(app.chats.messagesSnapshot(id).isEmpty())
            assertEquals(null, component.state.value.pendingDeleteId)
        } finally { lifecycle.destroy() }
    }

    @Test
    fun pinnedChatStaysFirstAfterNewerMessages() = runTest {
        val app = TestApp()
        val first = app.chats.createChat().id
        val second = app.chats.createChat().id
        app.chats.setPinned(first, true)
        app.chats.addMessage(second, MessageRole.USER, "Новое", MessageStatus.COMPLETE, null)
        assertEquals(first, app.chats.chats.first().first().id)
        app.chats.setTrashed(first, true)
        app.chats.markInterruptedGenerations()
        assertEquals(first, app.chats.chats.first().first().id)
    }
    @Test
    fun componentRecreationRestoresInterruptedUndoWithoutChangingMessages() = runTest {
        val app = TestApp()
        val id = app.chats.createChat().id
        app.chats.addMessage(id, MessageRole.USER, "Сохранить при повороте", MessageStatus.COMPLETE, null)
        fun component(lifecycle: LifecycleRegistry) = DefaultChatListComponent(
            DefaultComponentContext(lifecycle = lifecycle), app.chats, app.generation,
            UnconfinedTestDispatcher(testScheduler), {}, {}, {}, {},
        )
        val firstLifecycle = LifecycleRegistry().apply { resume() }
        val first = component(firstLifecycle)
        first.onIntent(ChatListIntent.DeleteChat(id))
        assertTrue(app.chats.chats.first().isEmpty())
        firstLifecycle.destroy()
        val secondLifecycle = LifecycleRegistry().apply { resume() }
        component(secondLifecycle)
        try {
            advanceTimeBy(7000)
            runCurrent()
            assertEquals(id, app.chats.chats.first().single().id)
            assertEquals("Сохранить при повороте", app.chats.messagesSnapshot(id).single().content)
        } finally { secondLifecycle.destroy() }
    }

}
