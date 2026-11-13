package ai.prism.presentation

import ai.prism.desktop.runOnUiThread
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.model.ThemeStyle
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelStatus
import ai.prism.presentation.chat.AttachmentError
import ai.prism.presentation.chat.ChatIntent
import ai.prism.presentation.chat.ChatState
import ai.prism.presentation.chat.VisionSupport
import ai.prism.presentation.chats.ChatListIntent
import ai.prism.presentation.root.DefaultRootComponent
import ai.prism.presentation.root.RootComponent
import ai.prism.presentation.settings.SettingsIntent
import ai.prism.testing.FakeModelRepository
import ai.prism.data.drafts.KeyValueDraftRepository
import ai.prism.domain.repository.ChatDraft
import ai.prism.testing.TestApp
import ai.prism.testing.testModel
import ai.prism.testing.testVision
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RootComponentTest {

    private var app = TestApp()

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
    fun unsentTextAndPhotosStayInChatAfterLeavingIt(): Unit = runOnUiThread {
        val root = createRoot()
        root.chatList.onIntent(ChatListIntent.NewChat)
        val chatId = root.chatList.state.value.chats.first().id
        val chat = assertIs<RootComponent.Child.Chat>(root.active).component
        chat.onIntent(ChatIntent.DraftChanged("Недописанный вопрос"))
        chat.onIntent(ChatIntent.AttachImages(listOf(byteArrayOf(1, 2, 3))))

        chat.onIntent(ChatIntent.Back)
        root.chatList.onIntent(ChatListIntent.OpenChat(chatId))

        val reopened = assertIs<RootComponent.Child.Chat>(root.active).component.state.value
        assertEquals("Недописанный вопрос", reopened.draft)
        assertEquals(1, reopened.attachments.size)

        // После перезапуска приложения черновик тоже на месте, а его фото не считаются брошенными.
        assertEquals("Недописанный вопрос", KeyValueDraftRepository(app.draftStore).draft(chatId).text)
        assertEquals(setOf(reopened.attachments.single().fileName), app.drafts.referencedImageFiles())

        // Отправленное сообщение черновик очищает.
        val again = assertIs<RootComponent.Child.Chat>(root.active).component
        again.onIntent(ChatIntent.Send)
        again.onIntent(ChatIntent.Back)
        assertEquals(ChatDraft(), app.drafts.draft(chatId))
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

    @Test
    fun imagesAreAttachedRemovedAndSent(): Unit = runOnUiThread {
        val model = testModel(vision = testVision())
        app = TestApp(models = FakeModelRepository(listOf(ModelEntry(model, ModelStatus.Downloaded, fitsInMemory = true))))
        val root = createRoot()
        root.chatList.onIntent(ChatListIntent.NewChat)
        val chat = assertIs<RootComponent.Child.Chat>(root.active).component
        assertEquals(VisionSupport.READY, chat.state.value.vision)

        chat.onIntent(ChatIntent.AttachImages(List(ChatState.MAX_ATTACHMENTS + 1) { byteArrayOf(it.toByte(), 1) }))
        assertEquals(ChatState.MAX_ATTACHMENTS, chat.state.value.attachments.size)
        assertEquals(AttachmentError.TOO_MANY, chat.state.value.attachmentError)

        val removed = chat.state.value.attachments.first()
        chat.onIntent(ChatIntent.RemoveAttachment(removed.id))
        assertEquals(listOf(removed.fileName), app.images.deleted)

        chat.onIntent(ChatIntent.AttachImages(listOf(ByteArray(0))))
        assertEquals(AttachmentError.UNREADABLE, chat.state.value.attachmentError)
        chat.onIntent(ChatIntent.DismissAttachmentError)

        // Сообщение из одних изображений тоже отправляется.
        assertTrue(chat.state.value.canSend)
        chat.onIntent(ChatIntent.Send)
        val state = chat.state.value
        assertTrue(state.attachments.isEmpty())
        assertEquals(ChatState.MAX_ATTACHMENTS - 1, state.messages.first().images.size)
        assertEquals(MessageStatus.COMPLETE, state.messages.last().status)
    }
}
