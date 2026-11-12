package ai.prism.presentation.root

import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.model.AppSettings
import ai.prism.domain.model.DeviceInfo
import ai.prism.domain.model.PlatformCapabilities
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.agent.WorkspaceProvider
import ai.prism.domain.repository.DocumentRepository
import ai.prism.domain.repository.ImageStore
import ai.prism.domain.repository.ModelRepository
import ai.prism.domain.repository.SettingsRepository
import ai.prism.presentation.chat.ChatComponent
import ai.prism.presentation.chat.DefaultChatComponent
import ai.prism.presentation.chats.ChatListComponent
import ai.prism.presentation.chats.DefaultChatListComponent
import ai.prism.presentation.models.DefaultModelsComponent
import ai.prism.presentation.models.ModelsComponent
import ai.prism.presentation.settings.DefaultSettingsComponent
import ai.prism.presentation.settings.SettingsComponent
import ai.prism.service.ChatGenerationService
import ai.prism.service.voice.VoiceService
import ai.prism.service.update.UpdateService
import ai.prism.domain.update.AppUpdater
import ai.prism.domain.model.ModelFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import ai.prism.domain.voice.AudioOutput
import ai.prism.service.documents.DocumentService
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

/** Зависимости уровня приложения, которые получают экраны. */
class AppDependencies(
    val chatRepository: ChatRepository,
    val modelRepository: ModelRepository,
    val settingsRepository: SettingsRepository,
    val generationService: ChatGenerationService,
    val documentService: DocumentService,
    val documentRepository: DocumentRepository,
    val workspaceProvider: WorkspaceProvider,
    val engine: InferenceEngine,
    val imageStore: ImageStore,
    val capabilities: PlatformCapabilities,
    val deviceInfo: DeviceInfo,
    val voiceService: VoiceService,
    /** Динамик: звук роликов со звуком (LTX). */
    val audioOutput: AudioOutput,
    /** Обновление приложения по кнопке в настройках. */
    val updateService: UpdateService = UpdateService(AppUpdater.None, CoroutineScope(SupervisorJob())),
    /** Где хранятся модели (Android: можно в общей папке, которая переживает удаление приложения). */
    val modelFolder: ModelFolder = ModelFolder.None,
)

class DefaultRootComponent(
    componentContext: ComponentContext,
    private val deps: AppDependencies,
    private val mainContext: CoroutineContext = Dispatchers.Main.immediate,
) : RootComponent, ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    override val settings: StateFlow<AppSettings> = deps.settingsRepository.settings

    override val chatList: ChatListComponent = DefaultChatListComponent(
        componentContext = childContext(key = "chatList"),
        chatRepository = deps.chatRepository,
        generation = deps.generationService,
        mainContext = mainContext,
        onOpenChat = { chatId -> openScreen(Config.Chat(chatId)) },
        onChatDeleted = ::onChatDeleted,
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

    private fun onChatDeleted(chatId: String) {
        if (stack.value.active.configuration == Config.Chat(chatId)) {
            navigation.replaceAll(Config.Home)
        }
    }

    private fun createChild(config: Config, componentContext: ComponentContext): RootComponent.Child =
        when (config) {
            Config.Home -> RootComponent.Child.Home
            is Config.Chat -> RootComponent.Child.Chat(
                DefaultChatComponent(
                    componentContext = componentContext,
                    chatId = config.chatId,
                    chatRepository = deps.chatRepository,
                    modelRepository = deps.modelRepository,
                    engine = deps.engine,
                    generation = deps.generationService,
                    documentService = deps.documentService,
                    documentRepository = deps.documentRepository,
                    workspaceProvider = deps.workspaceProvider,
                    settingsRepository = deps.settingsRepository,
                    imageStore = deps.imageStore,
                    voice = deps.voiceService,
                    audioOutput = deps.audioOutput,
                    mainContext = mainContext,
                    onOpenModels = { openScreen(Config.Models) },
                    onBack = ::onBack,
                ),
            )
            Config.Models -> RootComponent.Child.Models(
                DefaultModelsComponent(
                    componentContext = componentContext,
                    modelRepository = deps.modelRepository,
                    engine = deps.engine,
                    generation = deps.generationService,
                    settingsRepository = deps.settingsRepository,
                    deviceInfo = deps.deviceInfo,
                    mainContext = mainContext,
                    onBack = ::onBack,
                ),
            )
            Config.Settings -> RootComponent.Child.Settings(
                DefaultSettingsComponent(
                    componentContext = componentContext,
                    settingsRepository = deps.settingsRepository,
                    modelRepository = deps.modelRepository,
                    capabilities = deps.capabilities,
                    mainContext = mainContext,
                    onBack = ::onBack,
                    updates = deps.updateService,
                    modelFolder = deps.modelFolder,
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
    private val dependencies: AppDependencies,
) {
    fun create(componentContext: ComponentContext): RootComponent =
        DefaultRootComponent(componentContext = componentContext, deps = dependencies)
}
