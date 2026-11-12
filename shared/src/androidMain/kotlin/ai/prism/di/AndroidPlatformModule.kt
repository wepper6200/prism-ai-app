package ai.prism.di

import ai.prism.data.agent.HttpWebClient
import ai.prism.data.agent.SafWorkspaceProvider
import ai.prism.domain.agent.CommandRunner
import ai.prism.domain.agent.WebClient
import ai.prism.domain.agent.WorkspaceProvider
import ai.prism.data.documents.FileTextExtractor
import ai.prism.data.documents.PdfBoxAndroidTextReader
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
import ai.prism.domain.documents.DocumentTextExtractor
import ai.prism.domain.documents.TextEmbedder
import ai.prism.domain.imagegen.ImageGenerator
import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.model.DeviceInfo
import ai.prism.domain.model.PlatformCapabilities
import ai.prism.domain.repository.ImageStore
import ai.prism.imagegen.ProcessImageGenerator
import ai.prism.inference.llama.LlamaInferenceEngine
import ai.prism.inference.llama.LlamaTextEmbedder
import ai.prism.platform.AndroidDeviceInfo
import ai.prism.platform.AndroidNativeLibraryLoader
import ai.prism.domain.voice.AudioInput
import ai.prism.domain.voice.AudioOutput
import ai.prism.domain.voice.SpeechEngine
import ai.prism.voice.AndroidAudioInput
import ai.prism.voice.AndroidAudioOutput
import ai.prism.voice.AndroidSherpaSpeechEngine
import android.content.Context
import android.os.Build
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import ai.prism.domain.update.AppUpdater
import ai.prism.update.AndroidAppUpdater
import ai.prism.data.models.SharedStorageAccess
import ai.prism.data.models.SwitchableModelFolder
import ai.prism.domain.model.ModelFolder
import ai.prism.platform.AndroidSharedStorageAccess
import ai.prism.platform.AndroidImageGenBinary
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File

fun androidPlatformModule(context: Context): Module = module {
    val appContext = context.applicationContext
    single<KeyValueStore> { SharedPreferencesKeyValueStore(appContext) }
    single<SqlDriver> { AndroidSqliteDriver(schema = PrismDatabase.Schema, context = appContext, name = DATABASE_NAME) }
    single {
        PlatformCapabilities(supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S, platform = "android")
    }
    single<DeviceInfo> { AndroidDeviceInfo(appContext) }
    // Модели — во внутреннем хранилище приложения (удаляются вместе с ним) или, по выбору пользователя,
    // в общей папке Download/PrismAI, которая остаётся после удаления.
    single<SharedStorageAccess> { AndroidSharedStorageAccess(appContext) }
    single {
        FileModelStorage(
            SwitchableModelFolder.initialDirectory(
                store = get(),
                access = get(),
                internalDir = File(appContext.filesDir, MODELS_DIR),
                sharedDir = AndroidSharedStorageAccess.sharedModelsDir(),
            ),
        )
    }
    single<ModelFolder> {
        SwitchableModelFolder(
            storage = get(),
            internalDir = File(appContext.filesDir, MODELS_DIR),
            sharedDir = AndroidSharedStorageAccess.sharedModelsDir(),
            sharedLabel = AndroidSharedStorageAccess.SHARED_LABEL,
            access = get(),
            store = get(),
            repository = get(),
            scope = get(),
        )
    }
    single<ModelStorage> { get<FileModelStorage>() }
    single<ModelDownloader> { HttpModelDownloader(get()) }
    single<ImageCodec> { AndroidImageCodec() }
    single<ImageStore> { FileImageStore(File(appContext.filesDir, IMAGES_DIR), get()) }
    single<ImageGenerator> {
        // Программа генерации упакована как lib*.so: только такие файлы Android разрешает запускать.
        ProcessImageGenerator(
            executable = { AndroidImageGenBinary.pick(File(appContext.applicationInfo.nativeLibraryDir)) },
            workDir = File(appContext.cacheDir, IMAGEGEN_DIR),
            threads = get<DeviceInfo>().inferenceThreads,
        )
    }
    single<InferenceEngine> { LlamaInferenceEngine(AndroidNativeLibraryLoader(appContext), get(), get()) }
    single<DocumentTextExtractor> { FileTextExtractor(PdfBoxAndroidTextReader(appContext)) }
    single<WebClient> { HttpWebClient() }
    single<WorkspaceProvider> { SafWorkspaceProvider(appContext) }
    single<CommandRunner> { CommandRunner.None }
    single<TextEmbedder> { LlamaTextEmbedder(AndroidNativeLibraryLoader(appContext), get()) }
    single<SpeechEngine> { AndroidSherpaSpeechEngine(threads = get<DeviceInfo>().inferenceThreads.coerceIn(1, MAX_SPEECH_THREADS)) }
    single<AudioInput> { AndroidAudioInput(appContext) }
    single<AudioOutput> { AndroidAudioOutput() }
    single<AppUpdater> { AndroidAppUpdater(appContext) }
}

private const val DATABASE_NAME = "prism.db"
private const val MODELS_DIR = "models"
private const val IMAGES_DIR = "images"
private const val IMAGEGEN_DIR = "imagegen"

/** Модели речи небольшие: больше 4 потоков не ускоряют их на телефоне. */
private const val MAX_SPEECH_THREADS = 4
