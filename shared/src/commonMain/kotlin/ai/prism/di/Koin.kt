package ai.prism.di

import ai.prism.data.chat.InMemoryChatRepository
import ai.prism.data.db.PrismDatabase
import ai.prism.data.settings.DefaultSettingsRepository
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.repository.SettingsRepository
import ai.prism.presentation.root.RootComponentFactory
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Общие зависимости. Платформенный модуль обязан предоставить
 * `KeyValueStore`, `SqlDriver` и `PlatformCapabilities`.
 */
val sharedModule: Module = module {
    single<SettingsRepository> { DefaultSettingsRepository(store = get()) }
    single<ChatRepository> { InMemoryChatRepository() }
    // База открывается лениво — при первом обращении к ней.
    single { PrismDatabase(get()) }
    single {
        RootComponentFactory(
            chatRepository = get(),
            settingsRepository = get(),
            capabilities = get(),
        )
    }
}

fun initKoin(platformModule: Module): KoinApplication = startKoin {
    modules(sharedModule, platformModule)
}
