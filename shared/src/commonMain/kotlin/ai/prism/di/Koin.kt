package ai.prism.di

import ai.prism.core.BuildInfo
import ai.prism.data.mcp.KeyValueMcpServerRepository
import ai.prism.domain.mcp.McpServerRepository
import ai.prism.domain.mcp.McpTransportFactory
import ai.prism.service.mcp.McpManager
import ai.prism.data.catalog.ModelCatalog
import ai.prism.data.chat.SqlChatRepository
import ai.prism.data.db.PrismDatabase
import ai.prism.data.documents.SqlDocumentRepository
import ai.prism.data.models.DefaultModelRepository
import ai.prism.data.drafts.KeyValueDraftRepository
import ai.prism.data.memory.SqlMemoryRepository
import ai.prism.data.settings.DefaultSettingsRepository
import ai.prism.domain.model.DeviceInfo
import ai.prism.domain.model.PlatformCapabilities
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.repository.DocumentRepository
import ai.prism.domain.repository.DraftRepository
import ai.prism.domain.repository.MemoryRepository
import ai.prism.domain.repository.ModelRepository
import ai.prism.domain.repository.SettingsRepository
import ai.prism.presentation.root.AppDependencies
import ai.prism.presentation.root.RootComponentFactory
import ai.prism.service.ChatGenerationService
import ai.prism.service.PersonaProvider
import ai.prism.service.documents.DocumentService
import ai.prism.service.voice.VoiceService
import ai.prism.ui.ResourcePersonaProvider
import kotlinx.coroutines.CoroutineScope
import ai.prism.service.update.UpdateService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Общие зависимости. Платформенный модуль обязан предоставить
 * `KeyValueStore`, `SqlDriver`, `PlatformCapabilities`, `DeviceInfo`,
 * `ModelStorage`, `ModelDownloader`, `ImageStore`, `ImageGenerator`, `InferenceEngine`,
 * `DocumentTextExtractor`, `TextEmbedder`, `WebClient`, `WorkspaceProvider`, `CommandRunner`,
 * `SpeechEngine`, `AudioInput` и `AudioOutput`; по желанию — `Computer`, `ScreenControl` (Desktop) и `McpTransportFactory`.
 */
val sharedModule: Module = module {
    // Область приложения: загрузки моделей и генерация ответов живут дольше экранов.
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<SettingsRepository> { DefaultSettingsRepository(store = get()) }
    single<DraftRepository> { KeyValueDraftRepository(store = get()) }
    single { PrismDatabase(get()) }
    single<ChatRepository> { SqlChatRepository(database = get(), imageStore = get()) }
    single { ModelCatalog() }
    single<ModelRepository> {
        DefaultModelRepository(
            catalog = get(),
            storage = get(),
            downloader = get(),
            store = get(),
            deviceInfo = get(),
            scope = get(),
            platform = get<PlatformCapabilities>().platform,
        )
    }
    single<PersonaProvider> { ResourcePersonaProvider() }
    single<DocumentRepository> { SqlDocumentRepository(database = get()) }
    single<MemoryRepository> { SqlMemoryRepository(database = get()) }
    single<McpServerRepository> { KeyValueMcpServerRepository(store = get()) }
    single {
        McpManager(
            repository = get(),
            transports = getOrNull<McpTransportFactory>() ?: McpTransportFactory.None,
            scope = get(),
            clientVersion = BuildInfo.VERSION_NAME,
        )
    }
    single {
        DocumentService(
            repository = get(),
            models = get(),
            extractor = get(),
            embedder = get(),
            scope = get(),
        )
    }
    single {
        ChatGenerationService(
            engine = get(),
            chats = get(),
            models = get(),
            personaProvider = get(),
            images = get(),
            imageGenerator = get(),
            settings = get(),
            scope = get(),
            documents = get<DocumentService>(),
            web = get(),
            workspaces = get(),
            commands = get(),
            // Есть только на Desktop.
            computer = getOrNull(),
            screen = getOrNull(),
            mcp = get(),
            totalRamBytes = get<DeviceInfo>().totalRamBytes,
            drafts = get(),
            memories = get(),
        )
    }
    single {
        VoiceService(
            models = get(),
            settings = get(),
            engine = get(),
            input = get(),
            output = get(),
            generation = get(),
            chats = get(),
            scope = get(),
            platform = get<PlatformCapabilities>().platform,
        )
    }
    single { UpdateService(updater = get(), scope = get()) }
    single {
        AppDependencies(
            chatRepository = get(),
            modelRepository = get(),
            settingsRepository = get(),
            generationService = get(),
            documentService = get(),
            documentRepository = get(),
            workspaceProvider = get(),
            engine = get(),
            imageStore = get(),
            capabilities = get(),
            deviceInfo = get(),
            voiceService = get(),
            audioOutput = get(),
            updateService = get(),
            modelFolder = get(),
            drafts = get(),
            memories = get(),
            mcp = get(),
        )
    }
    single { RootComponentFactory(dependencies = get()) }
}

fun initKoin(platformModule: Module): KoinApplication = startKoin {
    modules(sharedModule, platformModule)
}
