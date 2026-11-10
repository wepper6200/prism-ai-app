package ai.prism.testing

import ai.prism.data.agent.FileWorkspaceProvider
import ai.prism.data.chat.SqlChatRepository
import ai.prism.data.documents.SqlDocumentRepository
import ai.prism.data.db.PrismDatabase
import ai.prism.data.settings.DefaultSettingsRepository
import ai.prism.data.settings.MapKeyValueStore
import ai.prism.domain.agent.CommandResult
import ai.prism.domain.agent.CommandRunner
import ai.prism.domain.agent.WebClient
import ai.prism.domain.agent.WebException
import ai.prism.domain.agent.WebResult
import ai.prism.domain.documents.DocumentException
import ai.prism.domain.documents.DocumentFormat
import ai.prism.domain.documents.DocumentTextExtractor
import ai.prism.domain.documents.TextEmbedder
import ai.prism.domain.documents.TextSection
import ai.prism.domain.imagegen.GeneratedImage
import ai.prism.domain.imagegen.ImageGenerationError
import ai.prism.domain.imagegen.ImageGenerationException
import ai.prism.domain.imagegen.ImageGenerationProgress
import ai.prism.domain.imagegen.ImageGenerator
import ai.prism.domain.imagegen.ImageRequest
import ai.prism.domain.imagegen.resolveImageModelFiles
import ai.prism.domain.inference.EngineState
import ai.prism.domain.inference.GenerationRequest
import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.inference.ModelInfo
import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.ChatFormat
import ai.prism.domain.model.DeviceInfo
import ai.prism.domain.model.EmbeddingSettings
import ai.prism.domain.model.ImageAttachment
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ImageModelSettings
import ai.prism.domain.model.ModelComponent
import ai.prism.domain.model.ModelFile
import ai.prism.domain.model.ModelKind
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
import ai.prism.service.documents.DocumentService
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

/** Модель рисования для тестов: основной файл и VAE. */
fun testImageModel(id: String = "flux-test", name: String = "FLUX.1 [schnell]") = testModel(id = id, name = name).copy(
    kind = ModelKind.IMAGE,
    platforms = listOf("desktop"),
    mainRole = "diffusion_model",
    sizeBytes = 6_884_606_880,
    components = listOf(ModelComponent("vae", "https://example.com/ae.safetensors", "$id-ae.safetensors", 335_304_388, "2".repeat(64))),
    image = ImageModelSettings(width = 1024, height = 1024, steps = 4),
)

/** Генератор изображений, который «рисует» заданные байты и сообщает о шагах. */
class FakeImageGenerator(var supported: Boolean = true) : ImageGenerator {
    override val isSupported: Boolean get() = supported
    var png: ByteArray = byteArrayOf(9, 9, 9)
    var error: ImageGenerationException? = null
    var lastRequest: ImageRequest? = null
    var lastFiles: Map<String, String>? = null
    var releases = 0
    private var cancelled = false

    override suspend fun generate(
        model: CatalogModel,
        files: Map<String, String>,
        request: ImageRequest,
        onProgress: (ImageGenerationProgress) -> Unit,
    ): GeneratedImage {
        lastRequest = request
        lastFiles = files
        cancelled = false
        onProgress(ImageGenerationProgress.Loading)
        for (step in 1..request.steps) {
            if (cancelled) throw ImageGenerationException(ImageGenerationError.CANCELLED, "остановлено")
            onProgress(ImageGenerationProgress.Step(step, request.steps))
        }
        error?.let { throw it }
        return GeneratedImage(png, request.width, request.height, 42)
    }

    override fun cancel() {
        cancelled = true
    }

    override suspend fun release() {
        releases += 1
    }
}

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

    /** Файлы моделей, которые загружались, по порядку. */
    val loadedPaths = mutableListOf<String>()

    /** Ответы на очередные запросы; когда закончатся — [reply]. */
    val queuedReplies = ArrayDeque<List<String>>()
    val requests = mutableListOf<GenerationRequest>()
    private val cancelled = AtomicBoolean(false)

    override suspend fun load(model: CatalogModel, filePath: String, projectorPath: String?) {
        loadError?.let { throw it }
        loadedPaths += filePath
        lastProjectorPath = projectorPath
        mutableState.value = EngineState.Ready(
            ModelInfo(model.id, model.name, "fake", 4096, "CPU", supportsImages = projectorPath != null),
        )
    }

    override fun generate(request: GenerationRequest): Flow<String> = flow {
        lastRequest = request
        requests += request
        cancelled.set(false)
        for (piece in queuedReplies.removeFirstOrNull() ?: reply) {
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
    activeId: String? = entries.firstOrNull { it.status == ModelStatus.Downloaded && it.model.kind == ModelKind.CHAT }?.model?.id,
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

    override fun imageModelFiles(modelId: String): Map<String, String>? =
        models.value.firstOrNull { it.model.id == modelId && it.model.kind == ModelKind.IMAGE && it.status == ModelStatus.Downloaded }
            ?.model?.let { model -> resolveImageModelFiles(model) { "/models/${it.fileName}" } }

    override fun downloadedProjectorPath(modelId: String): String? =
        models.value.firstOrNull { it.model.id == modelId && it.visionReady }?.model?.vision?.let { "/models/${it.fileName}" }
}

fun inMemoryDatabase(): PrismDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    PrismDatabase.Schema.create(driver)
    return PrismDatabase(driver)
}

fun inMemoryChatRepository(images: ImageStore = MemoryImageStore(), database: PrismDatabase = inMemoryDatabase()): SqlChatRepository =
    SqlChatRepository(database, images, Dispatchers.Unconfined)

/** Интернет для тестов: заданные результаты поиска и страницы. */
class FakeWebClient(
    var results: List<WebResult> = listOf(WebResult("Погода в Москве", "https://weather.example/msk", "Сегодня +5, облачно")),
    val pages: MutableMap<String, String> = mutableMapOf("https://weather.example/msk" to "Москва: +5, ветер 3 м/с"),
) : WebClient {
    val queries = mutableListOf<String>()
    val fetched = mutableListOf<String>()

    override suspend fun search(query: String): List<WebResult> {
        queries += query
        return results
    }

    override suspend fun fetchText(url: String, maxChars: Int): String {
        fetched += url
        return pages[url] ?: throw WebException("server answered 404 for $url")
    }

    override suspend fun download(url: String, maxBytes: Long): ByteArray =
        (pages[url] ?: throw WebException("server answered 404 for $url")).encodeToByteArray()
}

/** Команды для тестов: запоминает команды и отвечает заданным выводом. */
class FakeCommandRunner(var output: String = "BUILD SUCCESSFUL", var exitCode: Int = 0) : CommandRunner {
    val commands = mutableListOf<Pair<String, String>>()
    override val isSupported: Boolean = true

    override suspend fun run(directory: String, command: String, timeoutMs: Long): CommandResult {
        commands += directory to command
        return CommandResult(exitCode, output, timedOut = false)
    }
}

/** Модель поиска по файлам для тестов. */
fun testEmbeddingModel(id: String = "embed-test") = testModel(id = id, name = "EmbeddingGemma 300M").copy(
    kind = ModelKind.EMBEDDING,
    sizeBytes = 328_576_000,
    embedding = EmbeddingSettings(queryTemplate = "query: {text}", documentTemplate = "title: {title} | text: {text}"),
)

/**
 * Эмбеддинги по ключевым словам: измерение i — есть ли в тексте слово [vocabulary][i].
 * Так в тестах «похожие по смыслу» тексты — это тексты с общими словами.
 */
class FakeTextEmbedder(
    private val vocabulary: List<String> = listOf("кот", "собака", "отпуск", "зарплата", "договор"),
) : TextEmbedder {
    val requests = mutableListOf<String>()
    var releases = 0
    var error: InferenceException? = null

    override suspend fun embed(
        model: CatalogModel,
        filePath: String,
        texts: List<String>,
        onProgress: (Int) -> Unit,
    ): List<FloatArray> {
        error?.let { throw it }
        return texts.mapIndexed { index, text ->
            requests += text
            onProgress(index + 1)
            val lower = text.lowercase()
            val raw = FloatArray(vocabulary.size + 1) { i -> if (i < vocabulary.size && vocabulary[i] in lower) 1f else 0f }
            raw[vocabulary.size] = 0.01f
            val norm = kotlin.math.sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
            FloatArray(raw.size) { raw[it] / norm }
        }
    }

    override suspend fun release() {
        releases++
    }
}

/** Текст файла — его байты в UTF-8, по «странице» на каждый абзац, разделённый строкой `---`. */
class FakeTextExtractor : DocumentTextExtractor {
    var error: DocumentException? = null

    override suspend fun extract(bytes: ByteArray, format: DocumentFormat): List<TextSection> {
        error?.let { throw it }
        return bytes.decodeToString().split("\n---\n").mapIndexed { index, text ->
            TextSection(text, page = if (format == DocumentFormat.PDF) index + 1 else null)
        }.filter { it.text.isNotBlank() }
    }
}

/** Набор зависимостей приложения на подставных реализациях; всё выполняется синхронно. */
class TestApp(
    val engine: FakeInferenceEngine = FakeInferenceEngine(),
    val models: FakeModelRepository = FakeModelRepository(),
    val imageGenerator: FakeImageGenerator = FakeImageGenerator(),
    val embedder: FakeTextEmbedder = FakeTextEmbedder(),
    val extractor: FakeTextExtractor = FakeTextExtractor(),
    val web: FakeWebClient = FakeWebClient(),
    val commands: FakeCommandRunner = FakeCommandRunner(),
) {
    val settings = DefaultSettingsRepository(MapKeyValueStore())
    val images = MemoryImageStore()
    val database = inMemoryDatabase()
    val chats = inMemoryChatRepository(images, database)
    val documentRepository = SqlDocumentRepository(database, Dispatchers.Unconfined)
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val documents = DocumentService(
        repository = documentRepository,
        models = models,
        extractor = extractor,
        embedder = embedder,
        scope = scope,
        workContext = Dispatchers.Unconfined,
    )
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
                imagePromptInstruction = "Переведи на английский.",
                safeModeRule = "Безопасный режим.",
                imageSafetyInstruction = "Проверь и переведи.",
                imageReviewInstruction = "Есть недопустимое?",
                documentContext = "Фрагменты:\n{fragments}\n\nВопрос: {question}",
                pageLabel = "с.",
                agentInstructions = "Инструменты.\n{context}\n{tools}",
                agentWorkspaceNote = "Папка: {name}",
                agentInternetNote = "Интернет включён",
            )
        },
        images = images,
        imageGenerator = imageGenerator,
        settings = settings,
        scope = scope,
        documents = documents,
        web = web,
        workspaces = FileWorkspaceProvider(),
        commands = commands,
    )
    val dependencies = AppDependencies(
        chatRepository = chats,
        modelRepository = models,
        settingsRepository = settings,
        generationService = generation,
        documentService = documents,
        documentRepository = documentRepository,
        workspaceProvider = FileWorkspaceProvider(),
        engine = engine,
        imageStore = images,
        capabilities = PlatformCapabilities(supportsDynamicColor = false),
        deviceInfo = FakeDeviceInfo(),
    )
}
