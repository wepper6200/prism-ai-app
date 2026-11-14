package ai.prism.di

import ai.prism.data.agent.FileWorkspaceProvider
import ai.prism.data.agent.HttpWebClient
import ai.prism.data.agent.ProcessCommandRunner
import ai.prism.data.agent.DesktopComputer
import ai.prism.data.agent.RobotScreenControl
import ai.prism.data.mcp.DesktopMcpTransportFactory
import ai.prism.domain.mcp.McpTransportFactory
import ai.prism.domain.agent.ScreenControl
import ai.prism.domain.agent.CommandRunner
import ai.prism.domain.agent.Computer
import ai.prism.domain.agent.WebClient
import ai.prism.domain.agent.WorkspaceProvider
import ai.prism.data.documents.FileTextExtractor
import ai.prism.data.documents.PdfBoxTextReader
import ai.prism.data.db.PrismDatabase
import ai.prism.data.images.DesktopImageCodec
import ai.prism.data.images.FileImageStore
import ai.prism.data.images.ImageCodec
import ai.prism.data.models.FileModelStorage
import ai.prism.data.models.HttpModelDownloader
import ai.prism.data.models.ModelDownloader
import ai.prism.data.models.ModelStorage
import ai.prism.data.settings.KeyValueStore
import ai.prism.data.settings.PreferencesKeyValueStore
import ai.prism.desktop.AppDirectories
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
import ai.prism.platform.DesktopDeviceInfo
import ai.prism.platform.DesktopNativeLibraryLoader
import ai.prism.domain.voice.AudioInput
import ai.prism.domain.voice.AudioOutput
import ai.prism.domain.voice.SpeechEngine
import ai.prism.voice.DesktopSherpaSpeechEngine
import ai.prism.voice.JavaSoundAudioInput
import ai.prism.voice.JavaSoundAudioOutput
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ai.prism.domain.update.AppUpdater
import ai.prism.update.DesktopAppUpdater
import ai.prism.domain.model.ModelFolder
import ai.prism.data.models.ChoosableModelFolder
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
    single { PlatformCapabilities(supportsDynamicColor = false, supportsComputer = true) }
    single<DeviceInfo> { DesktopDeviceInfo() }
    // Папку моделей можно выбрать в настройках (например, на другом диске); по умолчанию — папка данных.
    single { FileModelStorage(ChoosableModelFolder.initialDirectory(get(), defaultModelsDir()).first) }
    single<ModelStorage> { get<FileModelStorage>() }
    single<ModelDownloader> { HttpModelDownloader(get()) }
    single<ImageCodec> { DesktopImageCodec() }
    single<ImageStore> { FileImageStore(File(AppDirectories.dataDir(), IMAGES_DIR), get()) }
    single<ImageGenerator> {
        ProcessImageGenerator(
            executable = { DesktopNativeLibraryLoader.findImageGenerator() },
            workDir = File(AppDirectories.dataDir(), IMAGEGEN_DIR),
            threads = get<DeviceInfo>().inferenceThreads,
        )
    }
    single<InferenceEngine> { LlamaInferenceEngine(DesktopNativeLibraryLoader(), get(), get()) }
    single<ai.prism.domain.music.MusicGenerator> {
        ai.prism.music.ProcessMusicGenerator(
            executable = { DesktopNativeLibraryLoader.findMusicGenerator() },
            workDir = File(AppDirectories.dataDir(), "musicgen"),
        )
    }
    single<DocumentTextExtractor> { FileTextExtractor(PdfBoxTextReader()) }
    single<WebClient> { HttpWebClient() }
    single<WorkspaceProvider> { FileWorkspaceProvider() }
    single<CommandRunner> { ProcessCommandRunner() }
    single<Computer> { DesktopComputer() }
    single<ScreenControl> { RobotScreenControl() }
    single<McpTransportFactory> { DesktopMcpTransportFactory() }
    single<TextEmbedder> { LlamaTextEmbedder(DesktopNativeLibraryLoader(), get()) }
    single<SpeechEngine> { DesktopSherpaSpeechEngine(threads = get<DeviceInfo>().inferenceThreads.coerceIn(1, MAX_SPEECH_THREADS)) }
    single<AudioInput> { JavaSoundAudioInput() }
    single<AudioOutput> { JavaSoundAudioOutput() }
    single<AppUpdater> { DesktopAppUpdater() }
    single<ModelFolder> {
        val engine = get<InferenceEngine>()
        val imageGenerator = get<ImageGenerator>()
        val speech = get<SpeechEngine>()
        val embedder = get<TextEmbedder>()
        ChoosableModelFolder(
            storage = get(),
            defaultDir = defaultModelsDir(),
            store = get(),
            repository = get(),
            scope = get(),
            unavailable = ChoosableModelFolder.initialDirectory(get(), defaultModelsDir()).second,
            // Windows не даёт переместить открытый файл: перед переносом модели выгружаются из памяти.
            beforeMove = {
                engine.unload()
                imageGenerator.release()
                speech.release()
                embedder.release()
            },
        )
    }
}

private const val DATABASE_NAME = "prism.db"
private const val MODELS_DIR = "models"
private const val IMAGES_DIR = "images"
private const val IMAGEGEN_DIR = "imagegen"

/** Модели речи небольшие: больше 6 потоков их не ускоряют. */
private const val MAX_SPEECH_THREADS = 6

private fun defaultModelsDir(): File = File(AppDirectories.dataDir(), MODELS_DIR)
