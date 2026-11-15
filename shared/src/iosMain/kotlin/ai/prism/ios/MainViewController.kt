@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package ai.prism.ios

import ai.prism.data.db.PrismDatabase
import ai.prism.data.images.ImageCodec
import ai.prism.data.models.ModelDownloader
import ai.prism.data.models.ModelStorage
import ai.prism.data.settings.KeyValueStore
import ai.prism.di.initKoin
import ai.prism.domain.agent.*
import ai.prism.domain.documents.*
import ai.prism.domain.imagegen.*
import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.model.*
import ai.prism.domain.music.MusicGenerator
import ai.prism.domain.repository.ImageStore
import ai.prism.domain.update.AppUpdater
import ai.prism.domain.voice.*
import ai.prism.inference.llama.*
import ai.prism.presentation.root.RootComponent
import ai.prism.presentation.root.RootComponentFactory
import ai.prism.ui.PrismApp
import androidx.compose.ui.window.ComposeUIViewController
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.pause
import org.koin.dsl.module
import platform.UIKit.UIViewController

private val appLifecycle = LifecycleRegistry()
private var appRoot: RootComponent? = null
fun mainViewController(host: IosHost): UIViewController {
    appleHost = host
    val root = appRoot ?: run {
        val koin = initKoin(iosPlatformModule(host)).koin
        koin.get<RootComponentFactory>().create(DefaultComponentContext(appLifecycle)).also { appRoot = it }
    }
    appLifecycle.resume()
    return ComposeUIViewController { PrismApp(root) }
}
fun setApplicationActive(active: Boolean) { if (active) appLifecycle.resume() else appLifecycle.pause() }

private fun iosPlatformModule(host: IosHost) = module {
    single<KeyValueStore> { IosPreferences(host) }
    single<SqlDriver> {
        NativeSqliteDriver(PrismDatabase.Schema, "prism.db", onConfiguration = { config ->
            config.copy(extendedConfig = config.extendedConfig.copy(basePath = host.dataDirectory()))
        })
    }
    single { PlatformCapabilities(supportsDynamicColor = false, platform = "ios") }
    single<DeviceInfo> { object : DeviceInfo {
        override val totalRamBytes = host.totalMemory()
        override fun availableRamBytes() = host.availableMemory()
        override val inferenceThreads = host.processorCount().coerceIn(1, 4)
        override val preferGpu = true
    } }
    single { IosModelStorage(host) }
    single<ModelStorage> { get<IosModelStorage>() }
    single<ModelDownloader> { IosModelDownloader(host, get()) }
    single<ModelFolder> { ModelFolder.None }
    single<ImageCodec> { IosImageCodec(host) }
    single<ImageStore> { IosImageStore(host, get()) }
    single<NativeLibraryLoader> { NativeLibraryLoader { "" } }
    single<InferenceEngine> { LlamaInferenceEngine(get(), get(), get()) }
    single<TextEmbedder> { LlamaTextEmbedder(get(), get()) }
    single<DocumentTextExtractor> { IosDocumentExtractor(host) }
    single<WebClient> { IosWebClient(host) }
    single<WorkspaceProvider> { IosWorkspaceProvider(host) }
    single<CommandRunner> { CommandRunner.None }
    single<MusicGenerator> { MusicGenerator.None }
    single<ImageGenerator> { IosUnavailableImageGenerator }
    single<AppUpdater> { AppUpdater.None }
    single<SpeechEngine> { IosSpeechEngine(host, get<DeviceInfo>().inferenceThreads) }
    single<AudioInput> { IosAudioInput(host) }
    single<AudioOutput> { IosAudioOutput(host) }
}

private object IosUnavailableImageGenerator : ImageGenerator {
    override val isSupported = false
    private fun unavailable(): Nothing = throw ImageGenerationException(ImageGenerationError.NOT_SUPPORTED, "Media generation is unavailable on iOS")
    override suspend fun generate(model: CatalogModel, files: Map<String, String>, request: ImageRequest, onProgress: (ImageGenerationProgress) -> Unit): GeneratedImage = unavailable()
    override suspend fun generateVideo(model: CatalogModel, files: Map<String, String>, request: VideoRequest, onProgress: (ImageGenerationProgress) -> Unit): GeneratedVideo = unavailable()
    override suspend fun compressWeights(input: String, output: String, type: String, onStarted: () -> Unit): Unit = unavailable()
    override fun cancel() = Unit
    override suspend fun release() = Unit
}
