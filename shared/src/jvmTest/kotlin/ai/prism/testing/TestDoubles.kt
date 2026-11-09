package ai.prism.testing

import ai.prism.data.chat.SqlChatRepository
import ai.prism.data.db.PrismDatabase
import ai.prism.data.settings.DefaultSettingsRepository
import ai.prism.data.settings.MapKeyValueStore
import ai.prism.domain.inference.EngineState
import ai.prism.domain.inference.GenerationRequest
import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.inference.ModelInfo
import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.ChatFormat
import ai.prism.domain.model.DeviceInfo
import ai.prism.domain.model.ImageAttachment
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelFile
import ai.prism.domain.model.ModelLicense
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.model.PlatformCapabilities
import ai.prism.domain.repository.ImageException
import ai.prism.domain.repository.ImageStore
import ai.prism.domain.repository.ModelRepository
import ai.prism.presentation.root.AppDependencies
import ai.prism.domain.inference.PromptMessage
import ai.prism.domain.model.MessageRole
import ai.prism.service.AssistantPersona
import ai.prism.service.ChatGenerationService
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean

fun testModel(
    id: String = "test-model",
    name: String = "Gemma 4 E2B",
    minRamMb: Int = 6144,
    recommendedFor: List<String> = listOf("android"),
    vision: ModelFile? = null,
) = CatalogModel(
    id = id,
    name = name,
    family = "Gemma 4",
    parameters = "E2B",
    quantization = "Q4_0",
    description = mapOf("ru" to "Быстрая модель для телефона: хорошо понимает русский."),
    url = "https://example.com/$id.gguf",
    fileName = "$id.gguf",
    sizeBytes = 2_841_481_184,
    sha256 = "0".repeat(64),
    contextLength = 8192,
    minRamMb = minRamMb,
    chatFormat = ChatFormat.GEMMA4,
    license = ModelLicense("Apache 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    recommendedFor = recommendedFor,
    vision = vision,
)

fun testVision(id: String = "test-model") = ModelFile(
    url = "https://example.com/mmproj-$id.gguf",
    fileName = "mmproj-$id.gguf",
    sizeBytes = 557_368_096,
    sha256 = "1".repeat(64),
)

/** Изображения в памяти: сохраняет байты как есть, размер — из [size]. */
class MemoryImageStore(var size: Pair<Int, Int> = 640 to 480) : ImageStore {
    val files = linkedMapOf<String, ByteArray>()
    val deleted = mutableListOf<String>()
    private var counter = 0

    override suspend fun save(bytes: ByteArray): ImageAttachment {
        if (bytes.isEmpty()) throw ImageException("пустой файл")
        counter += 1
        val id = "image-$counter"
        val fileName = "$id.jpg"
        files[fileName] = bytes
        return ImageAttachment(id, fileName, size.first, size.second)
    }

    override fun path(fileName: String): String = "/images/$fileName"

    override suspend fun read(fileName: String): ByteArray? = files[fileName]

    override suspend fun delete(fileNames: Collection<String>) {
        fileNames.forEach { files.remove(it) }
        deleted += fileNames
    }

    override suspend fun deleteOrphans(referenced: Set<String>, olderThanMillis: Long) = Unit
}

class FakeDeviceInfo(override val totalRamBytes: Long = 8L * 1024 * 1024 * 1024) : DeviceInfo {
    override fun availableRamBytes(): Long = totalRamBytes / 2
    override val inferenceThreads: Int = 2
    override val preferGpu: Boolean = false
}

/** Движок, который «отвечает» заранее заданными фрагментами. */
class FakeInferenceEngine(
    var reply: List<String> = listOf("Привет", "! Я ", "Prism AI."),
) : InferenceEngine {
    private val mutableState = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = mutableState

    var loadError: InferenceException? = null
    var generateError: InferenceException? = null
    var lastRequest: GenerationRequest? = null
    var lastProjectorPath: String? = null
    private val cancelled = AtomicBoolean(false)

    override suspend fun load(model: CatalogModel, filePath: String, projectorPath: String?) {
        loadError?.let { throw it }
        lastProjectorPath = projectorPath
        mutableState.value = EngineState.Ready(
            ModelInfo(model.id, model.name, "fake", 4096, "CPU", supportsImages = projectorPath != null),
        )
    }

    override fun generate(request: GenerationRequest): Flow<String> = flow {
        lastRequest = request
        cancelled.set(false)
        for (piece in reply) {
            if (cancelled.get()) break
            emit(piece)
        }
        generateError?.let { throw it }
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override suspend fun unload() {
        mutableState.value = EngineState.Idle
    }

    override fun modelInfo(): ModelInfo? = (state.value as? EngineState.Ready)?.info
}

class FakeModelRepository(
    entries: List<ModelEntry> = listOf(ModelEntry(testModel(), ModelStatus.Downloaded, fitsInMemory = true)),
    activeId: String? = entries.firstOrNull { it.status == ModelStatus.Downloaded }?.model?.id,
) : ModelRepository {
    override val models = MutableStateFlow(entries)
    override val activeModelId = MutableStateFlow(activeId)
    val downloadRequests = mutableListOf<String>()

    override fun download(modelId: String) {
        downloadRequests += modelId
    }

    override fun pause(modelId: String) = Unit

    override fun delete(modelId: String) {
        models.value = models.value.map { if (it.model.id == modelId) it.copy(status = ModelStatus.NotDownloaded) else it }
    }

    override fun setActive(modelId: String) {
        activeModelId.value = modelId
    }

    override fun catalogModel(modelId: String): CatalogModel? = models.value.firstOrNull { it.model.id == modelId }?.model

    override fun downloadedFilePath(modelId: String): String? =
        models.value.firstOrNull { it.model.id == modelId && it.chatReady }?.let { "/models/${it.model.fileName}" }

    override fun downloadedProjectorPath(modelId: String): String? =
        models.value.firstOrNull { it.model.id == modelId && it.visionReady }?.model?.vision?.let { "/models/${it.fileName}" }
}

fun inMemoryChatRepository(images: ImageStore = MemoryImageStore()): SqlChatRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    PrismDatabase.Schema.create(driver)
    return SqlChatRepository(PrismDatabase(driver), images, Dispatchers.Unconfined)
}

/** Набор зависимостей приложения на подставных реализациях; всё выполняется синхронно. */
class TestApp(
    val engine: FakeInferenceEngine = FakeInferenceEngine(),
    val models: FakeModelRepository = FakeModelRepository(),
) {
    val settings = DefaultSettingsRepository(MapKeyValueStore())
    val images = MemoryImageStore()
    val chats = inMemoryChatRepository(images)
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val generation = ChatGenerationService(
        engine = engine,
        chats = chats,
        models = models,
        personaProvider = {
            AssistantPersona(
                systemPrompt = "Ты — Prism AI.",
                primer = listOf(
                    PromptMessage(MessageRole.USER, "Кто ты?"),
                    PromptMessage(MessageRole.ASSISTANT, "Я Prism AI."),
                ),
            )
        },
        images = images,
        scope = scope,
    )
    val dependencies = AppDependencies(
        chatRepository = chats,
        modelRepository = models,
        settingsRepository = settings,
        generationService = generation,
        engine = engine,
        imageStore = images,
        capabilities = PlatformCapabilities(supportsDynamicColor = false),
        deviceInfo = FakeDeviceInfo(),
    )
}
