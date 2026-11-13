package ai.prism.testing

import ai.prism.domain.update.AppRelease
import ai.prism.domain.update.AppUpdater
import ai.prism.domain.update.ReleaseAsset
import ai.prism.service.update.UpdateService
import ai.prism.data.drafts.KeyValueDraftRepository
import ai.prism.data.memory.SqlMemoryRepository
import ai.prism.data.agent.FileWorkspaceProvider
import ai.prism.data.chat.SqlChatRepository
import ai.prism.data.documents.SqlDocumentRepository
import ai.prism.data.db.PrismDatabase
import ai.prism.data.settings.DefaultSettingsRepository
import ai.prism.data.settings.MapKeyValueStore
import ai.prism.data.agent.DesktopComputer
import ai.prism.domain.agent.CommandResult
import ai.prism.domain.agent.CommandRunner
import ai.prism.domain.agent.Computer
import ai.prism.domain.agent.MouseButton
import ai.prism.domain.agent.ScreenCapture
import ai.prism.domain.agent.ScreenControl
import ai.prism.domain.agent.Shell
import ai.prism.domain.agent.WebClient
import ai.prism.domain.agent.WebException
import ai.prism.domain.agent.WebResult
import ai.prism.domain.documents.DocumentException
import ai.prism.domain.documents.DocumentFormat
import ai.prism.domain.documents.DocumentTextExtractor
import ai.prism.domain.documents.TextEmbedder
import ai.prism.domain.documents.TextSection
import ai.prism.domain.imagegen.GeneratedImage
import ai.prism.domain.imagegen.GeneratedVideo
import ai.prism.domain.imagegen.VideoRequest
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
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.model.PlatformCapabilities
import ai.prism.domain.model.SpeechEngineType
import ai.prism.domain.model.SpeechModelSettings
import ai.prism.domain.repository.ImageException
import ai.prism.domain.repository.ImageStore
import ai.prism.domain.repository.ModelRepository
import ai.prism.presentation.root.AppDependencies
import ai.prism.domain.inference.PromptMessage
import ai.prism.domain.model.MessageRole
import ai.prism.service.AssistantPersona
import ai.prism.service.ChatGenerationService
import ai.prism.core.MjpegAvi
import ai.prism.service.voice.VoiceService
import ai.prism.service.documents.DocumentService
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
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

/** Модель распознавания речи для тестов: модель, словарь и общий детектор речи. */
fun testRecognizer(id: String = "gigaam-test", name: String = "GigaAM v3") = testModel(id = id, name = name).copy(
    kind = ModelKind.SPEECH_RECOGNITION,
    url = "https://example.com/$id.onnx",
    fileName = "$id.onnx",
    sizeBytes = 224_893_661,
    components = listOf(
        ModelComponent("tokens", "https://example.com/$id-tokens.txt", "$id-tokens.txt", 2007, "3".repeat(64)),
        ModelComponent("vad", "https://example.com/silero_vad.onnx", "silero_vad.onnx", 643_854, "4".repeat(64)),
    ),
    speech = SpeechModelSettings(SpeechEngineType.NEMO_CTC, language = "ru", languages = listOf("ru")),
)

/** Голос Piper для тестов: один архив с моделью, словарём и espeak-ng-data. */
fun testVoice(id: String = "piper-test", name: String = "Дмитрий") = testModel(id = id, name = name).copy(
    kind = ModelKind.SPEECH_SYNTHESIS,
    url = "https://example.com/$id.tar.bz2",
    fileName = "$id.tar.bz2",
    sizeBytes = 67_188_551,
    archive = true,
    mainRole = "voice",
    speech = SpeechModelSettings(SpeechEngineType.PIPER, language = "ru", languages = listOf("ru")),
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

    /** Файлы, которые уже «сжаты», и вызовы сжатия: вход, выход, тип. */
    val compressed = mutableSetOf<String>()
    val compressions = mutableListOf<Triple<String, String, String>>()
    var compressError: ImageGenerationException? = null

    override suspend fun compressWeights(input: String, output: String, type: String, onStarted: () -> Unit) {
        if (output in compressed) return
        onStarted()
        compressions += Triple(input, output, type)
        compressError?.let { throw it }
        compressed += output
    }

    /** Байты «видео», которое вернёт [generateVideo]. */
    var avi: ByteArray = byteArrayOf(7, 7, 7)
    var lastVideoRequest: VideoRequest? = null
    val videoRequests = mutableListOf<VideoRequest>()

    /** Отдавать настоящий MJPEG AVI: кадр — байты «номер куска, номер кадра» (для проверки склейки). */
    var realVideo = false

    /** Остановить генерацию, когда начнётся этот по счёту кусок (1 — первый). */
    var cancelOnSegment = 0

    override suspend fun generateVideo(
        model: CatalogModel,
        files: Map<String, String>,
        request: VideoRequest,
        onProgress: (ImageGenerationProgress) -> Unit,
    ): GeneratedVideo {
        lastVideoRequest = request
        videoRequests += request
        lastFiles = files
        cancelled = videoRequests.size == cancelOnSegment
        onProgress(ImageGenerationProgress.Loading)
        for (step in 1..request.steps) {
            if (cancelled) throw ImageGenerationException(ImageGenerationError.CANCELLED, "остановлено")
            onProgress(ImageGenerationProgress.Step(step, request.steps))
        }
        error?.let { throw it }
        if (realVideo) {
            val segment = videoRequests.size
            val frames = List(request.frames) { byteArrayOf(segment.toByte(), it.toByte()) }
            return GeneratedVideo(MjpegAvi.write(frames, request.width, request.height, request.fps), request.width, request.height, request.frames, request.fps, 42)
        }
        return GeneratedVideo(avi, request.width, request.height, request.frames, request.fps, 42)
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

    override suspend fun saveVideo(bytes: ByteArray, width: Int, height: Int): ImageAttachment {
        counter += 1
        val id = "video-$counter"
        val fileName = id + ImageAttachment.VIDEO_EXTENSION
        files[fileName] = bytes
        return ImageAttachment(id, fileName, width, height)
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

    var refreshes = 0

    override fun refresh() {
        refreshes++
    }

    override fun catalogModel(modelId: String): CatalogModel? = models.value.firstOrNull { it.model.id == modelId }?.model

    override fun downloadedFilePath(modelId: String): String? =
        models.value.firstOrNull { it.model.id == modelId && it.chatReady }?.let { "/models/${it.model.fileName}" }

    override fun imageModelFiles(modelId: String): Map<String, String>? =
        models.value.firstOrNull {
            it.model.id == modelId && it.model.kind in setOf(ModelKind.IMAGE, ModelKind.VIDEO) && it.status == ModelStatus.Downloaded
        }
            ?.model?.let { model -> resolveImageModelFiles(model) { "/models/${it.fileName}" } }

    override fun downloadedProjectorPath(modelId: String): String? =
        models.value.firstOrNull { it.model.id == modelId && it.visionReady }?.model?.vision?.let { "/models/${it.fileName}" }

    override suspend fun speechModelFiles(modelId: String): Map<String, String>? =
        models.value.firstOrNull {
            it.model.id == modelId &&
                it.model.kind in setOf(ModelKind.SPEECH_RECOGNITION, ModelKind.SPEECH_SYNTHESIS) &&
                it.status == ModelStatus.Downloaded
        }?.model?.let { model -> resolveImageModelFiles(model) { "/models/${it.fileName}" } }
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

/**
 * Команды для тестов: запоминает команды, отдаёт вывод по строкам и отвечает им. [whileRunning] вызывается после
 * вывода (можно проверить, что видит пользователь); [hang] — команда не заканчивается, пока её не отменят.
 */
class FakeCommandRunner(var output: String = "BUILD SUCCESSFUL", var exitCode: Int = 0) : CommandRunner {
    val commands = mutableListOf<Pair<String, String>>()
    val shells = mutableListOf<Shell>()
    val timeouts = mutableListOf<Long>()
    var whileRunning: () -> Unit = {}
    var hang = false
    var cancelled = false
    override val isSupported: Boolean = true

    override suspend fun run(
        directory: String,
        command: String,
        timeoutMs: Long,
        shell: Shell,
        onOutput: (String) -> Unit,
    ): CommandResult {
        commands += directory to command
        shells += shell
        timeouts += timeoutMs
        output.lines().forEach(onOutput)
        whileRunning()
        if (hang) {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        return CommandResult(exitCode, output, timedOut = false)
    }
}

/** Экран для тестов: 1920×1080, запоминает действия; снимок — непустые байты. */
class FakeScreenControl : ScreenControl {
    val actions = mutableListOf<String>()
    private fun shot() = ScreenCapture(byteArrayOf(1, 2, 3), 1920, 1080)

    override suspend fun capture(): ScreenCapture = shot().also { actions += "capture" }

    override suspend fun click(x: Int, y: Int, button: MouseButton, double: Boolean): ScreenCapture =
        shot().also { actions += "click $x,$y $button${if (double) " double" else ""}" }

    override suspend fun scroll(x: Int, y: Int, amount: Int): ScreenCapture = shot().also { actions += "scroll $x,$y $amount" }

    override suspend fun type(text: String): ScreenCapture = shot().also { actions += "type $text" }

    override suspend fun press(keys: String): ScreenCapture = shot().also { actions += "press $keys" }
}

/** Компьютер для тестов: настоящие файлы и сведения о системе, но программы не запускаются, а запоминаются. */
class FakeComputer(home: File) : Computer {
    private val real = DesktopComputer(Dispatchers.Unconfined, homePath = home.path, windows = false)
    val opened = mutableListOf<String>()
    override val homePath: String = home.path

    override fun isAbsolute(path: String) = real.isAbsolute(path)

    override suspend fun locate(path: String) = real.locate(path)

    override fun relativeTo(root: String, path: String) = real.relativeTo(root, path)

    override suspend fun systemInfo(): String = "OS: Test OS\nRAM: 16.0 GB total"

    override suspend fun open(target: String, arguments: String) {
        opened += listOf(target, arguments).filter { it.isNotBlank() }.joinToString(" ")
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
    /** Компьютер для инструментов «Компьютер»; `null` — как на Android. */
    val computer: Computer? = null,
    val screen: ScreenControl? = null,
    /** Справка о дате в персоне; пусто — системный промпт без даты. */
    currentDateNote: String = "",
    val speechEngine: FakeSpeechEngine = FakeSpeechEngine(),
    val audioInput: FakeAudioInput = FakeAudioInput(),
    val audioOutput: FakeAudioOutput = FakeAudioOutput(),
    val updater: AppUpdater = AppUpdater.None,
    /** Объём оперативной памяти «устройства» (0 — неизвестен). */
    totalRamBytes: Long = 0L,
) {
    val settings = DefaultSettingsRepository(MapKeyValueStore())
    val draftStore = MapKeyValueStore()
    val drafts = KeyValueDraftRepository(draftStore)
    val images = MemoryImageStore()
    val database = inMemoryDatabase()
    val chats = inMemoryChatRepository(images, database)
    val documentRepository = SqlDocumentRepository(database, Dispatchers.Unconfined)
    val memories = SqlMemoryRepository(database, Dispatchers.Unconfined)
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
                currentDateNote = currentDateNote,
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
                agentComputerNote = "Компьютер: {home}",
                agentScreenNote = "Экран доступен",
                agentStepLimit = "Шаги закончились.",
                agentStepLimitNotice = "Сделано {steps} шагов.",
                memoryNote = "О пользователе:\n{facts}",
                memoryExtraction = "Выпиши факты.",
                summaryInstruction = "Перескажи кратко.",
                summaryNote = "Начало диалога:\n{summary}",
            )
        },
        images = images,
        imageGenerator = imageGenerator,
        settings = settings,
        scope = scope,
        documents = documents,
        web = web,
        workspaces = FileWorkspaceProvider(Dispatchers.Unconfined),
        commands = commands,
        computer = computer,
        screen = screen,
        totalRamBytes = totalRamBytes,
        drafts = drafts,
        memories = memories,
        today = { "среда, 11 ноября 2026 г. (2026-11-11)" },
    )
    val voice = VoiceService(
        models = models,
        settings = settings,
        engine = speechEngine,
        input = audioInput,
        output = audioOutput,
        generation = generation,
        chats = chats,
        scope = scope,
        platform = "android",
        idleReleaseMs = 0,
    )
    val dependencies = AppDependencies(
        chatRepository = chats,
        modelRepository = models,
        settingsRepository = settings,
        generationService = generation,
        documentService = documents,
        documentRepository = documentRepository,
        workspaceProvider = FileWorkspaceProvider(Dispatchers.Unconfined),
        engine = engine,
        imageStore = images,
        capabilities = PlatformCapabilities(supportsDynamicColor = false),
        deviceInfo = FakeDeviceInfo(),
        voiceService = voice,
        audioOutput = audioOutput,
        updateService = UpdateService(updater, scope, currentVersion = "0.8.0"),
        drafts = drafts,
        memories = memories,
    )
}

/** Релиз 0.9.0 с APK; загрузка и установка только запоминаются. */
class FakeAppUpdater : AppUpdater {
    val installed = mutableListOf<String>()
    override val supported: Boolean = true

    override suspend fun latestRelease(): AppRelease = AppRelease(
        version = "0.9.0",
        notes = "- Qwen3.8-27B в каталоге\n- Обновление из приложения",
        pageUrl = "https://example.org/releases/v0.9.0",
        asset = ReleaseAsset("PrismAI-0.9.0-android-arm64.apk", "https://example.org/app.apk", 120L * 1024 * 1024, "0".repeat(64)),
    )

    override suspend fun download(asset: ReleaseAsset, onProgress: (Long, Long) -> Unit): String {
        onProgress(asset.sizeBytes, asset.sizeBytes)
        return "/updates/" + asset.name
    }

    override suspend fun install(path: String, version: String) {
        installed += path
    }
}
