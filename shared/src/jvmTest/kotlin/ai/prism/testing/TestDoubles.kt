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
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelLicense
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.model.PlatformCapabilities
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
)

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
    private val cancelled = AtomicBoolean(false)

    override suspend fun load(model: CatalogModel, filePath: String) {
        loadError?.let { throw it }
        mutableState.value = EngineState.Ready(ModelInfo(model.id, model.name, "fake", 4096, "CPU"))
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
        models.value.firstOrNull { it.model.id == modelId && it.status == ModelStatus.Downloaded }?.let { "/models/${it.model.fileName}" }
}

fun inMemoryChatRepository(): SqlChatRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    PrismDatabase.Schema.create(driver)
    return SqlChatRepository(PrismDatabase(driver), Dispatchers.Unconfined)
}

/** Набор зависимостей приложения на подставных реализациях; всё выполняется синхронно. */
class TestApp(
    val engine: FakeInferenceEngine = FakeInferenceEngine(),
    val models: FakeModelRepository = FakeModelRepository(),
) {
    val settings = DefaultSettingsRepository(MapKeyValueStore())
    val chats = inMemoryChatRepository()
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
        scope = scope,
    )
    val dependencies = AppDependencies(
        chatRepository = chats,
        modelRepository = models,
        settingsRepository = settings,
        generationService = generation,
        engine = engine,
        capabilities = PlatformCapabilities(supportsDynamicColor = false),
        deviceInfo = FakeDeviceInfo(),
    )
}
