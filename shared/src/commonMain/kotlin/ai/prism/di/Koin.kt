package ai.prism.di

import ai.prism.data.catalog.ModelCatalog
import ai.prism.data.chat.SqlChatRepository
import ai.prism.data.db.PrismDatabase
import ai.prism.data.models.DefaultModelRepository
import ai.prism.data.settings.DefaultSettingsRepository
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.repository.ModelRepository
import ai.prism.domain.repository.SettingsRepository
import ai.prism.presentation.root.AppDependencies
import ai.prism.presentation.root.RootComponentFactory
import ai.prism.service.ChatGenerationService
import ai.prism.service.SystemPromptProvider
import ai.prism.ui.ResourceSystemPromptProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Общие зависимости. Платформенный модуль обязан предоставить
 * `KeyValueStore`, `SqlDriver`, `PlatformCapabilities`, `DeviceInfo`,
 * `ModelStorage`, `ModelDownloader` и `InferenceEngine`.
 */
val sharedModule: Module = module {
    // Область приложения: загрузки моделей и генерация ответов живут дольше экранов.
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<SettingsRepository> { DefaultSettingsRepository(store = get()) }
    single { PrismDatabase(get()) }
    single<ChatRepository> { SqlChatRepository(database = get()) }
    single { ModelCatalog() }
    single<ModelRepository> {
        DefaultModelRepository(
            catalog = get(),
            storage = get(),
            downloader = get(),
            store = get(),
            deviceInfo = get(),
            scope = get(),
        )
    }
    single<SystemPromptProvider> { ResourceSystemPromptProvider() }
    single {
        ChatGenerationService(
            engine = get(),
            chats = get(),
            models = get(),
            systemPrompt = get(),
            scope = get(),
        )
    }
    single {
        AppDependencies(
            chatRepository = get(),
            modelRepository = get(),
            settingsRepository = get(),
            generationService = get(),
            engine = get(),
            capabilities = get(),
            deviceInfo = get(),
        )
    }
    single { RootComponentFactory(dependencies = get()) }
}

fun initKoin(platformModule: Module): KoinApplication = startKoin {
    modules(sharedModule, platformModule)
}
