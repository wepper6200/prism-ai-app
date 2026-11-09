package ai.prism.presentation

import ai.prism.data.chat.InMemoryChatRepository
import ai.prism.data.settings.DefaultSettingsRepository
import ai.prism.data.settings.MapKeyValueStore
import ai.prism.desktop.runOnUiThread
import ai.prism.domain.model.PlatformCapabilities
import ai.prism.domain.model.ThemeStyle
import ai.prism.presentation.chat.ChatIntent
import ai.prism.presentation.chats.ChatListIntent
import ai.prism.presentation.root.DefaultRootComponent
import ai.prism.presentation.root.RootComponent
import ai.prism.presentation.settings.SettingsIntent
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RootComponentTest {

    private val settingsRepository = DefaultSettingsRepository(MapKeyValueStore())

    private fun createRoot(): RootComponent {
        val lifecycle = LifecycleRegistry()
        return DefaultRootComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
            chatRepository = InMemoryChatRepository(),
            settingsRepository = settingsRepository,
            capabilities = PlatformCapabilities(supportsDynamicColor = false),
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
    fun chatDraftIsKeptInState(): Unit = runOnUiThread {
        val root = createRoot()
        root.chatList.onIntent(ChatListIntent.NewChat)
        val chat = assertIs<RootComponent.Child.Chat>(root.active).component

        chat.onIntent(ChatIntent.DraftChanged("Привет"))

        assertEquals("Привет", chat.state.value.draft)
    }

    @Test
    fun settingsScreenChangesTheme(): Unit = runOnUiThread {
        val root = createRoot()
        root.chatList.onIntent(ChatListIntent.OpenSettings)
        val settings = assertIs<RootComponent.Child.Settings>(root.active).component

        settings.onIntent(SettingsIntent.SelectThemeStyle(ThemeStyle.LIQUID_GLASS))

        assertEquals(ThemeStyle.LIQUID_GLASS, root.settings.value.themeStyle)
        assertEquals(ThemeStyle.LIQUID_GLASS, settings.state.value.settings.themeStyle)
    }
}
