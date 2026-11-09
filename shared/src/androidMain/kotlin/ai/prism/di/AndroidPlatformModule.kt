package ai.prism.di

import ai.prism.data.db.PrismDatabase
import ai.prism.data.images.AndroidImageCodec
import ai.prism.data.images.FileImageStore
import ai.prism.data.images.ImageCodec
import ai.prism.data.models.FileModelStorage
import ai.prism.data.models.HttpModelDownloader
import ai.prism.data.models.ModelDownloader
import ai.prism.data.models.ModelStorage
import ai.prism.data.settings.KeyValueStore
import ai.prism.data.settings.SharedPreferencesKeyValueStore
import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.model.DeviceInfo
import ai.prism.domain.model.PlatformCapabilities
import ai.prism.domain.repository.ImageStore
import ai.prism.inference.llama.LlamaInferenceEngine
import ai.prism.platform.AndroidDeviceInfo
import ai.prism.platform.AndroidNativeLibraryLoader
import android.content.Context
import android.os.Build
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

fun androidPlatformModule(context: Context): Module = module {
    val appContext = context.applicationContext
    single<KeyValueStore> { SharedPreferencesKeyValueStore(appContext) }
    single<SqlDriver> { AndroidSqliteDriver(schema = PrismDatabase.Schema, context = appContext, name = DATABASE_NAME) }
    single {
        PlatformCapabilities(supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    }
    single<DeviceInfo> { AndroidDeviceInfo(appContext) }
    // Модели — во внутреннем хранилище приложения: не видны другим приложениям и удаляются вместе с ним.
    single { FileModelStorage(File(appContext.filesDir, MODELS_DIR)) }
    single<ModelStorage> { get<FileModelStorage>() }
    single<ModelDownloader> { HttpModelDownloader(get()) }
    single<ImageCodec> { AndroidImageCodec() }
    single<ImageStore> { FileImageStore(File(appContext.filesDir, IMAGES_DIR), get()) }
    single<InferenceEngine> { LlamaInferenceEngine(AndroidNativeLibraryLoader(appContext), get(), get()) }
}

private const val DATABASE_NAME = "prism.db"
private const val MODELS_DIR = "models"
private const val IMAGES_DIR = "images"
