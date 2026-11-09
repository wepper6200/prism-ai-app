package ai.prism.presentation

import ai.prism.desktop.runOnUiThread
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.model.ThemeStyle
import ai.prism.presentation.chat.ChatIntent
import ai.prism.presentation.chats.ChatListIntent
import ai.prism.presentation.root.DefaultRootComponent
import ai.prism.presentation.root.RootComponent
import ai.prism.presentation.settings.SettingsIntent
import ai.prism.testing.TestApp
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RootComponentTest {

    private val app = TestApp()

    private fun createRoot(): RootComponent {
        val lifecycle = LifecycleRegistry()
        return DefaultRootComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
            deps = app.dependencies,
            mainContext = Dispatchers.Unconfined,
        ).also { lifecycle.resume() }
    }

    private val RootComponent.active get() = stack.value.active.instance

    @Test
    fun startsOnHome(): Unit = runOnUiThread {
        assertIs<RootComponent.Child.Home>(createRoot().active)
    }

    @Test
    fun newChatOpensChatAndBackReturnsHome(): Unit = runOnUiThread {
        val root = createRoot()

        root.chatList.onIntent(ChatListIntent.NewChat)

        val chat = assertIs<RootComponent.Child.Chat>(root.active)
        assertEquals(1, root.chatList.state.value.chats.size)
        assertEquals(root.chatList.state.value.chats.first().id, chat.component.state.value.chatId)

        chat.component.onIntent(ChatIntent.Back)
        assertIs<RootComponent.Child.Home>(root.active)
    }

    @Test
    fun emptyChatIsReusedInsteadOfCreatingAnother(): Unit = runOnUiThread {
        val root = createRoot()
        root.chatList.onIntent(ChatListIntent.NewChat)
        root.chatList.onIntent(ChatListIntent.NewChat)
        assertEquals(1, root.chatList.state.value.chats.size)
    }

    @Test
    fun sendingMessageStreamsReplyAndNamesChat(): Unit = runOnUiThread {
        val root = createRoot()
        root.chatList.onIntent(ChatListIntent.NewChat)
        val chat = assertIs<RootComponent.Child.Chat>(root.active).component

        chat.onIntent(ChatIntent.DraftChanged("Кто ты?"))
        chat.onIntent(ChatIntent.Send)

        val state = chat.state.value
        assertEquals("", state.draft)
        assertEquals("Кто ты?", state.title)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), state.messages.map { it.role })
        assertEquals("Привет! Я Prism AI.", state.messages.last().text)
        assertEquals(MessageStatus.COMPLETE, state.messages.last().status)
        assertTrue(!state.isGenerating)
    }

    @Test
    fun deletingOpenChatReturnsHome(): Unit = runOnUiThread {
        val root = createRoot()
        root.chatList.onIntent(ChatListIntent.NewChat)
        val chatId = root.chatList.state.value.chats.first().id

        root.chatList.onIntent(ChatListIntent.DeleteChat(chatId))

        assertIs<RootComponent.Child.Home>(root.active)
        assertTrue(root.chatList.state.value.chats.isEmpty())
    }

    @Test
    fun topLevelScreensKeepShallowBackStack(): Unit = runOnUiThread {
        val root = createRoot()

        root.chatList.onIntent(ChatListIntent.OpenSettings)
        assertIs<RootComponent.Child.Settings>(root.active)

        root.chatList.onIntent(ChatListIntent.OpenModels)
        assertIs<RootComponent.Child.Models>(root.active)
        assertEquals(2, root.stack.value.items.size)

        root.onBack()
        assertIs<RootComponent.Child.Home>(root.active)
    }

    @Test
    fun settingsScreenChangesThemeAndListsModelLicenses(): Unit = runOnUiThread {
        val root = createRoot()
        root.chatList.onIntent(ChatListIntent.OpenSettings)
        val settings = assertIs<RootComponent.Child.Settings>(root.active).component

        settings.onIntent(SettingsIntent.SelectThemeStyle(ThemeStyle.LIQUID_GLASS))

        assertEquals(ThemeStyle.LIQUID_GLASS, root.settings.value.themeStyle)
        assertEquals(ThemeStyle.LIQUID_GLASS, settings.state.value.settings.themeStyle)
        assertEquals(listOf("Apache 2.0"), settings.state.value.modelLicenses.map { it.licenseName })
    }
}
