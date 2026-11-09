package ai.prism.di

import ai.prism.data.db.PrismDatabase
import ai.prism.data.models.FileModelStorage
import ai.prism.data.models.HttpModelDownloader
import ai.prism.data.models.ModelDownloader
import ai.prism.data.models.ModelStorage
import ai.prism.data.settings.KeyValueStore
import ai.prism.data.settings.PreferencesKeyValueStore
import ai.prism.desktop.AppDirectories
import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.model.DeviceInfo
import ai.prism.domain.model.PlatformCapabilities
import ai.prism.inference.llama.LlamaInferenceEngine
import ai.prism.platform.DesktopDeviceInfo
import ai.prism.platform.DesktopNativeLibraryLoader
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import java.util.Properties

fun desktopPlatformModule(): Module = module {
    single<KeyValueStore> { PreferencesKeyValueStore() }
    single<SqlDriver> {
        val file = File(AppDirectories.dataDir(), DATABASE_NAME)
        JdbcSqliteDriver(
            url = "jdbc:sqlite:${file.absolutePath}",
            properties = Properties(),
            schema = PrismDatabase.Schema,
        )
    }
    single { PlatformCapabilities(supportsDynamicColor = false) }
    single<DeviceInfo> { DesktopDeviceInfo() }
    single { FileModelStorage(File(AppDirectories.dataDir(), MODELS_DIR)) }
    single<ModelStorage> { get<FileModelStorage>() }
    single<ModelDownloader> { HttpModelDownloader(get()) }
    single<InferenceEngine> { LlamaInferenceEngine(DesktopNativeLibraryLoader(), get()) }
}

private const val DATABASE_NAME = "prism.db"
private const val MODELS_DIR = "models"
