package ai.prism.presentation.root

import ai.prism.domain.model.AppSettings
import ai.prism.domain.model.PlatformCapabilities
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.repository.SettingsRepository
import ai.prism.presentation.chat.ChatComponent
import ai.prism.presentation.chat.DefaultChatComponent
import ai.prism.presentation.chats.ChatListComponent
import ai.prism.presentation.chats.DefaultChatListComponent
import ai.prism.presentation.models.DefaultModelsComponent
import ai.prism.presentation.models.ModelsComponent
import ai.prism.presentation.settings.DefaultSettingsComponent
import ai.prism.presentation.settings.SettingsComponent
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.childContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext

/**
 * Корневой компонент навигации.
 *
 * Стек всегда имеет вид `[Home]` или `[Home, экран]`, поэтому «Назад» с любого
 * экрана возвращает к списку чатов. Список чатов живёт отдельно от стека:
 * на широком экране он постоянно виден в боковой панели.
 */
interface RootComponent {
    val stack: Value<ChildStack<*, Child>>
    val chatList: ChatListComponent
    val settings: StateFlow<AppSettings>

    fun onBack()

    sealed interface Child {
        /** Стартовый экран: список чатов на телефоне, приветствие на широком экране. */
        data object Home : Child
        class Chat(val component: ChatComponent) : Child
        class Models(val component: ModelsComponent) : Child
        class Settings(val component: SettingsComponent) : Child
    }
}

class DefaultRootComponent(
    componentContext: ComponentContext,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val capabilities: PlatformCapabilities,
    private val mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : RootComponent, ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    override val settings: StateFlow<AppSettings> = settingsRepository.settings

    override val chatList: ChatListComponent = DefaultChatListComponent(
        componentContext = childContext(key = "chatList"),
        chatRepository = chatRepository,
        mainContext = mainContext,
        onOpenChat = { chatId -> openScreen(Config.Chat(chatId)) },
        onOpenModels = { openScreen(Config.Models) },
        onOpenSettings = { openScreen(Config.Settings) },
    )

    override val stack: Value<ChildStack<*, RootComponent.Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.Home,
        handleBackButton = true,
        childFactory = ::createChild,
    )

    override fun onBack() {
        navigation.pop()
    }

    private fun openScreen(config: Config) {
        navigation.replaceAll(Config.Home, config)
    }

    private fun createChild(config: Config, componentContext: ComponentContext): RootComponent.Child =
        when (config) {
            Config.Home -> RootComponent.Child.Home
            is Config.Chat -> RootComponent.Child.Chat(
                DefaultChatComponent(
                    componentContext = componentContext,
                    chatId = config.chatId,
                    chatRepository = chatRepository,
                    mainContext = mainContext,
                    onOpenModels = { openScreen(Config.Models) },
                    onBack = ::onBack,
                ),
            )
            Config.Models -> RootComponent.Child.Models(
                DefaultModelsComponent(componentContext = componentContext, onBack = ::onBack),
            )
            Config.Settings -> RootComponent.Child.Settings(
                DefaultSettingsComponent(
                    componentContext = componentContext,
                    settingsRepository = settingsRepository,
                    capabilities = capabilities,
                    mainContext = mainContext,
                    onBack = ::onBack,
                ),
            )
        }

    @Serializable
    private sealed interface Config {
        @Serializable
        data object Home : Config

        @Serializable
        data class Chat(val chatId: String) : Config

        @Serializable
        data object Models : Config

        @Serializable
        data object Settings : Config
    }
}

/** Создаёт корневой компонент с зависимостями из Koin. */
class RootComponentFactory(
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val capabilities: PlatformCapabilities,
) {
    fun create(componentContext: ComponentContext): RootComponent = DefaultRootComponent(
        componentContext = componentContext,
        chatRepository = chatRepository,
        settingsRepository = settingsRepository,
        capabilities = capabilities,
    )
}
