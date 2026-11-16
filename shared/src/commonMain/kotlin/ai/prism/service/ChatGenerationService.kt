package ai.prism.service

import ai.prism.core.newId
import ai.prism.core.todayText
import ai.prism.domain.agent.AgentStep
import ai.prism.domain.agent.AgentStepStatus
import ai.prism.domain.agent.AgentTool
import ai.prism.service.agent.WorkspaceActionPolicy
import ai.prism.domain.model.ChatFormat
import ai.prism.domain.agent.CommandRunner
import ai.prism.domain.agent.Computer
import ai.prism.domain.agent.PendingAction
import ai.prism.domain.agent.RunningTool
import ai.prism.domain.agent.ScreenControl
import ai.prism.domain.agent.WebClient
import ai.prism.domain.agent.WorkspaceProvider
import ai.prism.domain.documents.DocumentChunk
import ai.prism.domain.documents.DocumentException
import ai.prism.domain.documents.MessageSource
import ai.prism.domain.imagegen.ImageGenerationError
import ai.prism.domain.imagegen.ImageGenerationException
import ai.prism.domain.imagegen.ImageGenerationProgress
import ai.prism.domain.imagegen.ImageGenerator
import ai.prism.domain.imagegen.ImageRequest
import ai.prism.domain.imagegen.VideoRequest
import ai.prism.domain.music.MusicGenerator
import ai.prism.domain.music.MusicRequest
import ai.prism.domain.inference.GenerationRequest
import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.inference.InferenceError
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.inference.PromptImage
import ai.prism.domain.inference.PromptMessage
import ai.prism.domain.inference.SamplingParams
import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.ChatDigest
import ai.prism.domain.model.ChatMessage
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.ImageAttachment
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.model.ModelKind
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.model.TextEncoderMode
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.repository.DraftRepository
import ai.prism.domain.repository.MemoryRepository
import ai.prism.domain.repository.ImageException
import ai.prism.domain.repository.ImageStore
import ai.prism.domain.repository.ModelRepository
import ai.prism.domain.repository.SettingsRepository
import ai.prism.inference.ChatPromptFormatter
import ai.prism.service.agent.AgentToolbox
import ai.prism.service.agent.ToolCall
import ai.prism.service.agent.ToolCallProtocol
import ai.prism.service.agent.ToolOutcome
import ai.prism.service.mcp.McpManager
import ai.prism.service.documents.DocumentRetriever
import ai.prism.service.video.VideoClipBuilder
import ai.prism.service.video.VideoPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/** Личность ассистента: системный промпт и образец ответа на вопрос «кто ты?». */
data class AssistantPersona(
    val systemPrompt: String,
    val primer: List<PromptMessage>,
    /** Короткое напоминание о личности в конце последнего сообщения пользователя. */
    val reminder: String = "",
    val profileInstructions: Map<ai.prism.domain.model.GenerationProfile, String> = emptyMap(),
    val benchmarkPrompt: String = "Кратко объясни, почему сменяются времена года.",
    val verificationRetry: String = "No successful test/build command has run. Read the project configuration and use run_command for a suitable verification. Wait for permission. Do not invent a successful check.",
    val verificationIncomplete: String = "Project verification was not confirmed successful. Check the tool results.",
    val branchTitle: String = "Ветка: {title}",
    val verificationRequest: String = "Проверь результат последних изменений в проекте. Прочитай изменённые файлы. Найди подходящую команду тестов или сборки и предложи её запуск. Не меняй файлы; сообщи, какие проверки действительно выполнены и что не удалось проверить.",

    /**
     * Сегодняшняя дата и справка о свежих версиях (`{date}` — дата на устройстве). Модели знают мир только
     * до конца своего обучения и без этого считают текущим его год. Пусто — не добавляется.
     */
    val currentDateNote: String = "",
    /** Инструкция для перевода описания картинки на английский (генераторы лучше понимают английский). */
    val imagePromptInstruction: String = "",
    /** Отдельная подготовка описания музыки и текста песни. */
    val musicPromptInstruction: String = "",
    val musicSafetyInstruction: String = "",
    /** Безопасный режим: правило для модели чата (добавляется к системному промпту). */
    val safeModeRule: String = "",
    /** Безопасный режим: проверить описание картинки и перевести его (или ответить BLOCKED). */
    val imageSafetyInstruction: String = "",
    /** Безопасный режим: вопрос к модели с модулем зрения о готовой картинке (ответ YES/NO). */
    val imageReviewInstruction: String = "",
    /**
     * Сообщение пользователя с фрагментами файлов диалога: `{fragments}` — пронумерованные фрагменты,
     * `{question}` — само сообщение. Только в промпте, в историю не попадает.
     */
    val documentContext: String = "{fragments}\n\n{question}",
    /** Подпись номера страницы PDF в заголовке фрагмента («с.»). */
    val pageLabel: String = "p.",
    /** Как пользоваться инструментами: `{context}` — папка проекта и интернет, `{tools}` — список инструментов. */
    val agentInstructions: String = "{context}\n\n{tools}",
    /** Инструкция инструментов в родном формате Qwen3-Coder (`<tool_call><function=…><parameter=…>`). */
    val agentInstructionsQwenCoder: String = "",
    /** Про папку проекта: `{name}` — имя папки. */
    val agentWorkspaceNote: String = "{name}",
    /** Про включённый интернет. */
    val agentInternetNote: String = "",
    /** Про доступ к компьютеру (файлы вне проекта, команды, программы); `{home}` — домашняя папка. */
    val agentComputerNote: String = "",
    /** Про управление экраном (снимки, мышь, клавиатура). */
    val agentScreenNote: String = "",
    /** Про инструменты MCP-серверов пользователя. */
    val agentMcpNote: String = "",
    /** Описание каждого инструмента для модели. */
    val toolDescriptions: Map<AgentTool, String> = emptyMap(),
    /** Добавляется к результату последнего разрешённого шага: инструментов больше нет, нужно подвести итог. */
    val agentStepLimit: String = "",
    /** Видимая пользователю подсказка, если модель и после предела шагов не подвела итог. */
    val agentStepLimitNotice: String = "",
    val agentWorkspaceActionRetry: String = "No successful file write or edit has been recorded for this request. Use the available tools to perform the requested change, reading existing files first. A textual description or code block does not change files. Do not claim success without a successful tool result. If blocked or clarification is needed, explain that honestly.",
    val agentWorkspaceActionIncomplete: String = "No successful file changes were recorded for this request. The requested change has not been confirmed.",
    val agentWorkspaceActionRejected: String = "An action was rejected. Further actions for this request were stopped. Earlier successful changes remain; see the tool steps.",
    /** Факты о пользователе в системном промпте; `{facts}` — список. */
    val memoryNote: String = "",
    /** Инструкция: выписать из сообщения пользователя устойчивые факты о нём (или «нет»). */
    val memoryExtraction: String = "",
    /** Инструкция: кратко пересказать начало диалога. */
    val summaryInstruction: String = "",
    /** Краткое содержание начала диалога в системном промпте; `{summary}` — текст. */
    val summaryNote: String = "",
)

/** Личность Prism AI на языке интерфейса. */
fun interface PersonaProvider {
    suspend fun persona(): AssistantPersona
}

/** Описание для генератора изображений: готово ([Ready]) или рисовать нельзя ([Rejected]). */
private sealed interface PreparedPrompt {
    data class Ready(val prompt: String) : PreparedPrompt

    data class Rejected(val error: GenerationError) : PreparedPrompt
}

/** Этап создания изображения. */
enum class DrawingStage {
    TRANSLATING,

    /** Один раз сжимается текстовый энкодер видео (настройка «Текстовый энкодер»). */
    COMPRESSING,
    LOADING,
    DRAWING,
}

/** Ход создания изображения: этап и шаг из скольких. */
data class DrawingProgress(
    val stage: DrawingStage,
    val step: Int = 0,
    val steps: Int = 0,
    /** Создаётся видео, а не изображение. */
    val video: Boolean = false,
    /** Длинное видео снимается кусками: какой сейчас и сколько всего (0 — один кусок). */
    val part: Int = 0,
    val parts: Int = 0,
    val music: Boolean = false,
)

/**
 * Ответ, который генерируется прямо сейчас. [messageId] пуст, пока сообщение ещё не создано.
 * [drawing] — не `null`, пока модель рисует изображение.
 */
data class ActiveGeneration(
    val chatId: String,
    val messageId: String,
    val text: String,
    val drawing: DrawingProgress? = null,
    /** Шаги ассистента с инструментами (поиск, файлы, команды) в этом ответе. */
    val steps: List<AgentStep> = emptyList(),
    /** Действие, которое ждёт разрешения пользователя. */
    val pending: PendingAction? = null,
    /** Инструмент, который выполняется сейчас (долгая команда) — с последними строками вывода. */
    val running: RunningTool? = null,
)

/**
 * Генерация ответов в области приложения: переживает уход с экрана чата и пересоздание Activity.
 * Независимые текстовые сессии ограничены настройкой; операции с медиа выполняются отдельно.
 */
class ChatGenerationService(
    private val engine: InferenceEngine,
    private val chats: ChatRepository,
    private val models: ModelRepository,
    private val personaProvider: PersonaProvider,
    private val images: ImageStore,
    private val imageGenerator: ImageGenerator,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    /** Поиск фрагментов в файлах диалога для ответа. */
    private val documents: DocumentRetriever = DocumentRetriever.None,
    /** Интернет для ассистента (используется, только если он включён в настройках). */
    private val web: WebClient? = null,
    /** Папки проектов, выбранные для диалогов. */
    private val workspaces: WorkspaceProvider? = null,
    private val commands: CommandRunner = CommandRunner.None,
    /** Компьютер пользователя вне папки проекта (Desktop; используется, только если включён «Компьютер»). */
    private val computer: Computer? = null,
    /** Управление экраном (Desktop; с «Компьютер» и моделью, которая видит изображения). */
    private val screen: ScreenControl? = null,
    /** MCP-серверы пользователя: их инструменты доступны ассистенту. */
    private val mcp: McpManager? = null,
    /** Сегодняшняя дата на устройстве для промпта. */
    private val today: () -> String = ::todayText,
    /** Объём оперативной памяти: от него зависит текстовый энкодер видео по умолчанию (0 — неизвестен). */
    private val totalRamBytes: Long = 0L,
    /** Черновики диалогов: их фото не удаляются как брошенные. */
    private val drafts: DraftRepository = DraftRepository.None,
    /** Память о пользователе между диалогами. */
    private val memories: MemoryRepository = MemoryRepository.None,
    private val musicGenerator: MusicGenerator = MusicGenerator.None,
    private val changeJournal: ai.prism.service.agent.WorkspaceChangeJournal = ai.prism.service.agent.WorkspaceChangeJournal(),
    private val benchmarkHistory: ModelBenchmarkHistory = ModelBenchmarkHistory(),
    private val branchDocuments: ai.prism.domain.repository.DocumentRepository? = null,
    private val coordinator: ChatGenerationService? = null,
) {
    private val mutableActive = MutableStateFlow<ActiveGeneration?>(null)
    val active: StateFlow<ActiveGeneration?> = mutableActive.asStateFlow()

    private val mutableWorkbench = MutableStateFlow(WorkbenchState(benchmarks = benchmarkHistory.load()))
    val workbench: StateFlow<WorkbenchState> = mutableWorkbench.asStateFlow()

    fun dismissWorkbenchNotice() { mutableWorkbench.update { it.copy(notice = null) } }

    private enum class OperationKind { TEXT, EXCLUSIVE }
    private val manager: ChatGenerationService get() = coordinator ?: this
    private val workerId = newId()
    private val children = MutableStateFlow<List<ChatGenerationService>>(emptyList())
    private val running = MutableStateFlow<Map<String, ActiveGeneration>>(emptyMap())
    private val admission = kotlinx.coroutines.sync.Mutex()
    private val maintenance = kotlinx.coroutines.sync.Mutex()
    private val releasing = MutableStateFlow(false)
    private var operationKind = OperationKind.TEXT
    private var operationJob: Job? = null

    /** Every live answer, keyed by its independent session. */
    val activeGenerations: StateFlow<Map<String, ActiveGeneration>> get() = manager.running.asStateFlow()
    fun activeFor(chatId: String): kotlinx.coroutines.flow.Flow<ActiveGeneration?> = activeGenerations
        .map { states -> states.values.firstOrNull { it.chatId == chatId } }.distinctUntilChanged()
    fun isRunning(): Boolean = manager.workers().any { it.mutableActive.value != null }
    fun canStartChat(chatId: String): Boolean {
        val owner = manager
        if (owner.releasing.value) return false
        val busy = owner.workers().filter { it.mutableActive.value != null }
        return busy.none { it.mutableActive.value?.chatId == chatId || it.operationKind == OperationKind.EXCLUSIVE } &&
            busy.size < if (owner.engine.supportsParallelSessions) owner.settings.settings.value.maxParallelReplies else 1
    }
    private fun workers(): List<ChatGenerationService> = listOf(this) + children.value
    private fun publishActive() {
        manager.running.update { before ->
            val latest = mutableActive.value
            if (latest == null) before - workerId else before + (workerId to latest)
        }
    }
    private fun setActive(value: ActiveGeneration?) { mutableActive.value = value; publishActive() }
    private fun updateActive(transform: (ActiveGeneration?) -> ActiveGeneration?) { mutableActive.update(transform); publishActive() }
    private fun launchOperation(block: suspend CoroutineScope.() -> Unit) {
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY, block = block)
        operationJob = job
        job.invokeOnCompletion { if (operationJob === job) operationJob = null }
        job.start()
    }
    private suspend fun finishOperation() {
        try {
            if (coordinator != null) withContext(NonCancellable) { engine.unload() }
        } finally { setActive(null) }
    }
    private fun newWorker(): ChatGenerationService? {
        val fork = engine.forkForParallelism(settings.settings.value.maxParallelReplies) ?: return null
        return ChatGenerationService(fork, chats, models, personaProvider, images, imageGenerator, settings, scope,
            documents, web, workspaces, commands, computer, screen, mcp, today, totalRamBytes, drafts, memories,
            musicGenerator, changeJournal, benchmarkHistory, branchDocuments, coordinator = this)
    }
    private fun dispatch(chatId: String, kind: OperationKind, action: (ChatGenerationService) -> Boolean): Boolean {
        val owner = manager
        if (owner !== this) return owner.dispatch(chatId, kind, action)
        if (!admission.tryLock()) return false
        try {
            if (releasing.value) return false
            val busy = workers().filter { it.mutableActive.value != null }
            if (busy.any { it.mutableActive.value?.chatId == chatId }) return false
            if (kind == OperationKind.EXCLUSIVE && busy.isNotEmpty()) return false
            if (busy.any { it.operationKind == OperationKind.EXCLUSIVE }) return false
            val limit = if (engine.supportsParallelSessions) settings.settings.value.maxParallelReplies else 1
            if (busy.size >= limit) return false
            val worker = workers().firstOrNull { it.mutableActive.value == null }
                ?: newWorker()?.also { children.value = children.value + it } ?: return false
            worker.operationKind = kind
            return action(worker)
        } finally { admission.unlock() }
    }
    fun send(chatId: String, text: String, attachments: List<ImageAttachment> = emptyList(), verification: Boolean = false): Boolean =
        dispatch(chatId, OperationKind.TEXT) { it.sendHere(chatId, text, attachments, verification) }
    fun draw(chatId: String, text: String, source: ImageAttachment? = null): Boolean =
        dispatch(chatId, OperationKind.EXCLUSIVE) { it.drawHere(chatId, text, source) }
    fun animate(chatId: String, text: String, source: ImageAttachment? = null): Boolean =
        dispatch(chatId, OperationKind.EXCLUSIVE) { it.animateHere(chatId, text, source) }
    fun composeMusic(chatId: String, text: String): Boolean =
        dispatch(chatId, OperationKind.EXCLUSIVE) { it.composeMusicHere(chatId, text) }
    fun retry(chatId: String): Boolean = dispatch(chatId, OperationKind.EXCLUSIVE) { it.retryHere(chatId) }
    fun undoChanges(chatId: String, replyId: String): Boolean = dispatch(chatId, OperationKind.EXCLUSIVE) { it.undoChangesHere(chatId, replyId) }
    fun forkChat(chatId: String, messageId: String, onCreated: (String) -> Unit): Boolean =
        dispatch(chatId, OperationKind.EXCLUSIVE) { it.forkChatHere(chatId, messageId, onCreated) }
    fun benchmark(chatId: String, modelId: String): Boolean = dispatch(chatId, OperationKind.EXCLUSIVE) { it.benchmarkHere(chatId, modelId) }
    fun stop(chatId: String? = null) {
        manager.workers().filter { chatId == null || it.mutableActive.value?.chatId == chatId }.forEach { it.stopHere() }
    }
    suspend fun releaseAll() {
        val owner = manager
        owner.maintenance.withLock {
            val jobs = owner.admission.withLock {
                owner.releasing.value = true
                owner.stop()
                owner.workers().mapNotNull { it.operationJob }
            }
            try {
                jobs.forEach { it.join() }
                owner.workers().forEach { it.engine.unload() }
            } finally { owner.releasing.value = false }
        }
    }
    fun usesModel(modelId: String): Boolean = manager.workers().any { worker ->
        when (val value = worker.engine.state.value) {
            is ai.prism.domain.inference.EngineState.Ready -> value.info.modelId == modelId
            is ai.prism.domain.inference.EngineState.Loading -> value.modelId == modelId
            else -> false
        }
    }

    private fun undoChangesHere(chatId: String, replyId: String): Boolean {
        val provider = workspaces ?: return false
        if (!reserve(chatId)) return false
        mutableWorkbench.update { it.copy(busy = true, notice = null) }
        launchOperation {
            try {
                changeJournal.undo(chatId, replyId, provider)
                chats.messagesSnapshot(chatId).firstOrNull { it.id == replyId }?.let { message ->
                    chats.attachSteps(chatId, replyId, message.steps.map { step ->
                        if (step.status == AgentStepStatus.DONE && step.tool in setOf(AgentTool.WRITE_FILE, AgentTool.EDIT_FILE)) step.copy(status = AgentStepStatus.UNDONE, verified = false) else step
                    })
                }
                mutableWorkbench.update { it.copy(notice = WorkbenchNotice.UNDONE) }
            } catch (e: ai.prism.domain.agent.WorkspaceException) {
                mutableWorkbench.update { it.copy(notice = WorkbenchNotice.CONFLICT) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { mutableWorkbench.update { it.copy(notice = WorkbenchNotice.FAILED) }
            } finally {
                mutableWorkbench.update { it.copy(busy = false) }
                finishOperation()
            }
        }
        return true
    }

    private fun forkChatHere(chatId: String, messageId: String, onCreated: (String) -> Unit): Boolean {
        if (!reserve(chatId)) return false
        mutableWorkbench.update { it.copy(busy = true, notice = null) }
        launchOperation {
            try {
                val title = chats.chats.first().firstOrNull { it.id == chatId }?.title.orEmpty()
                val name = personaProvider.persona().branchTitle.replace("{title}", title)
                val id = ChatBranchService(chats, images, branchDocuments).fork(chatId, messageId, name)
                onCreated(id)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                mutableWorkbench.update { it.copy(notice = WorkbenchNotice.BRANCH_FAILED) }
            } finally {
                mutableWorkbench.update { it.copy(busy = false) }
                finishOperation()
            }
        }
        return true
    }

    fun verifyProject(chatId: String) {
        scope.launch { send(chatId, personaProvider.persona().verificationRequest, verification = true) }
    }

    private fun benchmarkHere(chatId: String, modelId: String): Boolean {
        val model = models.models.value.firstOrNull { it.model.id == modelId && it.model.kind == ModelKind.CHAT && it.chatReady && it.fitsInMemory }?.model ?: return false
        val path = models.downloadedFilePath(modelId) ?: return false
        if (!reserve(chatId)) return false
        mutableWorkbench.update { it.copy(busy = true, benchmarking = modelId, notice = null) }
        launchOperation {
            try {
                imageGenerator.release()
                engine.unload()
                val loadStart = kotlin.time.TimeSource.Monotonic.markNow()
                val context = model.contextLength.takeIf { it > 0 }?.coerceAtMost(4096) ?: 4096
                engine.load(model.copy(contextLength = context), path, null)
                val measuredContext = engine.modelInfo()?.contextSize?.takeIf { it > 0 } ?: context
                val loadMillis = loadStart.elapsedNow().inWholeMilliseconds
                val started = kotlin.time.TimeSource.Monotonic.markNow()
                var first: Long? = null
                var characters = 0
                if (!stopRequested) engine.generate(GenerationRequest(
                    systemPrompt = personaProvider.persona().systemPrompt,
                    messages = listOf(PromptMessage(MessageRole.USER, personaProvider.persona().benchmarkPrompt)),
                    maxTokens = 64, sampling = SamplingParams(temperature = 0f, seed = 42), thinkingBudget = 0,
                )).collect { piece ->
                    if (piece.isNotBlank() && first == null) first = started.elapsedNow().inWholeMilliseconds
                    characters += piece.length
                }
                if (stopRequested) mutableWorkbench.update { it.copy(notice = WorkbenchNotice.CANCELLED) }
                else if (characters == 0 || first == null) mutableWorkbench.update { it.copy(notice = WorkbenchNotice.BENCHMARK_FAILED) }
                else {
                    val result = ModelBenchmarkResult(modelId, loadMillis, first, started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1), characters, model.minRamMb, ai.prism.core.currentTimeMillis(), measuredContext)
                    val results = (mutableWorkbench.value.benchmarks.filterNot { it.modelId == modelId } + result).takeLast(12)
                    benchmarkHistory.save(results)
                    mutableWorkbench.update { it.copy(benchmarks = results) }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { mutableWorkbench.update { it.copy(notice = WorkbenchNotice.BENCHMARK_FAILED) }
            } finally {
                try { withContext(NonCancellable) { engine.unload() } } finally {
                    mutableWorkbench.update { it.copy(busy = false, benchmarking = null) }
                    finishOperation()
                }
            }
        }
        return true
    }

    @Volatile
    private var stopRequested = false

    @Volatile
    private var decision: CompletableDeferred<Boolean>? = null

    /** Инструмент, который выполняется сейчас: «Стоп» отменяет его (и завершает долгую команду). */
    @Volatile
    private var toolJob: Job? = null

    init {
        scope.launch { changeJournal.replies.collect { ids -> mutableWorkbench.update { it.copy(undoReplies = ids) } } }
        if (coordinator == null) scope.launch {
            chats.markInterruptedGenerations()
            // Черновики удалённых диалогов больше не нужны.
            drafts.retainChats(chats.chats.first().mapTo(mutableSetOf()) { it.id })
            // Изображения, на которые не ссылаются ни сообщения, ни черновики.
            images.deleteOrphans(chats.referencedImageFiles() + drafts.referencedImageFiles(), ORPHAN_IMAGE_AGE_MS)
        }
    }

    /**
     * Отправляет сообщение (текст и/или изображения) и запускает ответ.
     * `false` — уже идёт другая генерация или сообщение пустое.
     */
    private fun sendHere(chatId: String, text: String, attachments: List<ImageAttachment> = emptyList(), verification: Boolean = false): Boolean {
        val content = text.trim()
        if ((content.isEmpty() && attachments.isEmpty()) || !reserve(chatId)) return false
        launchOperation {
            try {
                chats.addMessage(chatId, MessageRole.USER, content, MessageStatus.COMPLETE, modelId = null, images = attachments)
                if (content.isNotEmpty()) chats.setTitleIfEmpty(chatId, titleFrom(content))
                generateReply(chatId, verification)
            } finally {
                finishOperation()
            }
        }
        return true
    }

    /**
     * Просит модель изображений нарисовать картинку по описанию [text]; с [source] — перерисовать это фото.
     * `false` — уже идёт другая генерация или описание пустое.
     */
    private fun drawHere(chatId: String, text: String, source: ImageAttachment? = null): Boolean {
        val prompt = text.trim()
        if (prompt.isEmpty() || !reserve(chatId)) return false
        launchOperation {
            try {
                chats.addMessage(chatId, MessageRole.USER, prompt, MessageStatus.COMPLETE, modelId = null, images = listOfNotNull(source))
                chats.setTitleIfEmpty(chatId, titleFrom(prompt))
                drawReply(chatId, prompt, source)
            } finally {
                finishOperation()
            }
        }
        return true
    }

    /**
     * Просит модель видео (Wan) снять ролик по описанию [text]; с [source] — оживить это фото.
     * `false` — уже идёт другая генерация или описание пустое.
     */
    private fun animateHere(chatId: String, text: String, source: ImageAttachment? = null): Boolean {
        val prompt = text.trim()
        if (prompt.isEmpty() || !reserve(chatId)) return false
        launchOperation {
            try {
                chats.addMessage(chatId, MessageRole.USER, prompt, MessageStatus.COMPLETE, modelId = null, images = listOfNotNull(source))
                chats.setTitleIfEmpty(chatId, titleFrom(prompt))
                videoReply(chatId, prompt, source)
            } finally {
                finishOperation()
            }
        }
        return true
    }

    private fun composeMusicHere(chatId: String, text: String): Boolean {
        val prompt = text.trim()
        if (prompt.isEmpty() || !reserve(chatId)) return false
        launchOperation {
            try {
                chats.addMessage(chatId, MessageRole.USER, prompt, MessageStatus.COMPLETE, modelId = null)
                chats.setTitleIfEmpty(chatId, titleFrom(prompt))
                musicReply(chatId, prompt)
            } finally {
                finishOperation()
            }
        }
        return true
    }

    /** Повторяет последний неудачный или остановленный ответ. */
    private fun retryHere(chatId: String): Boolean {
        if (!reserve(chatId)) return false
        launchOperation {
            try {
                val messages = chats.messagesSnapshot(chatId)
                val last = messages.lastOrNull()
                val lastKind = last?.takeIf { it.role == MessageRole.ASSISTANT }?.modelId?.let(models::catalogModel)?.kind
                val wasDrawing = lastKind == ModelKind.IMAGE
                val wasVideo = lastKind == ModelKind.VIDEO
                val wasMusic = lastKind == ModelKind.MUSIC
                if (last != null && last.role == MessageRole.ASSISTANT && last.status != MessageStatus.COMPLETE) {
                    chats.deleteMessage(last.id)
                }
                val request = messages.lastOrNull { it.role == MessageRole.USER }
                if (wasDrawing && request != null && request.content.isNotBlank()) {
                    drawReply(chatId, request.content, request.images.firstOrNull())
                } else if (wasVideo && request != null && request.content.isNotBlank()) {
                    videoReply(chatId, request.content, request.images.firstOrNull())
                } else if (wasMusic && request != null && request.content.isNotBlank()) {
                    musicReply(chatId, request.content)
                } else {
                    generateReply(chatId)
                }
            } finally {
                finishOperation()
            }
        }
        return true
    }

    private fun stopHere() {
        stopRequested = true
        decision?.complete(false)
        toolJob?.cancel()
        engine.cancel()
        imageGenerator.cancel()
        musicGenerator.cancel()
    }

    /** Ответ пользователя на запрос разрешения [actionId] (изменение файлов, скачивание, команда). */
    fun resolvePending(actionId: String, approved: Boolean) {
        manager.workers().firstOrNull { it.mutableActive.value?.pending?.id == actionId }?.decision?.complete(approved)
    }

    /** На этой платформе есть программа генерации изображений. */
    val canGenerateImages: Boolean get() = imageGenerator.isSupported

    val canGenerateMusic: Boolean get() = musicGenerator.isSupported

    fun musicModel(): CatalogModel? = models.models.value
        .firstOrNull { it.model.kind == ModelKind.MUSIC && it.status == ModelStatus.Downloaded }?.model

    private suspend fun musicReply(chatId: String, prompt: String) {
        val model = musicModel() ?: models.models.value.firstOrNull { it.model.kind == ModelKind.MUSIC }?.model
        val reply = chats.addMessage(chatId, MessageRole.ASSISTANT, "", MessageStatus.GENERATING, model?.id)
        fun progress(stage: DrawingStage, step: Int = 0, steps: Int = 0) {
            setActive(ActiveGeneration(chatId, reply.id, "", DrawingProgress(stage, step, steps, music = true)))
        }
        val files = model?.let { models.imageModelFiles(it.id) }
        if (files == null || !musicGenerator.isSupported) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.NO_MUSIC_MODEL)
            return
        }
        try {
            progress(DrawingStage.TRANSLATING)
            val prepared = prepareMusicPrompt(prompt, settings.settings.value.safeMode, settings.settings.value.musicSeconds)
            engine.unload()
            imageGenerator.release()
            if (stopRequested) {
                chats.updateMessage(reply.id, "", MessageStatus.STOPPED, null)
                return
            }
            if (prepared is PreparedMusic.Rejected) {
                chats.updateMessage(reply.id, "", MessageStatus.FAILED, prepared.error)
                return
            }
            val request = (prepared as PreparedMusic.Ready).request
            val bytes = musicGenerator.generate(files, request) { event ->
                when (event) {
                    ImageGenerationProgress.Loading -> progress(DrawingStage.LOADING)
                    is ImageGenerationProgress.Step -> progress(DrawingStage.DRAWING, event.step, event.steps)
                }
            }
            if (stopRequested) {
                chats.updateMessage(reply.id, "", MessageStatus.STOPPED, null)
                return
            }
            val attachment = images.saveAudio(bytes)
            try {
                chats.attachImages(chatId, reply.id, listOf(attachment))
            } catch (e: Exception) {
                withContext(NonCancellable) { images.delete(listOf(attachment.fileName)) }
                throw e
            }
            chats.updateMessage(reply.id, request.lyrics.takeUnless { it == "[Instrumental]" }.orEmpty(), MessageStatus.COMPLETE, null)
        } catch (e: ImageGenerationException) {
            val stopped = stopRequested || e.error == ImageGenerationError.CANCELLED
            val error = when (e.error) {
                ImageGenerationError.NOT_ENOUGH_MEMORY -> GenerationError.NOT_ENOUGH_MEMORY
                ImageGenerationError.GPU_DRIVER_MISSING -> GenerationError.GPU_DRIVER_MISSING
                else -> GenerationError.MUSIC_GENERATION_FAILED
            }
            chats.updateMessage(reply.id, "", if (stopped) MessageStatus.STOPPED else MessageStatus.FAILED, error.takeUnless { stopped })
        } catch (e: CancellationException) {
            withContext(NonCancellable) { chats.updateMessage(reply.id, "", MessageStatus.STOPPED, null) }
            throw e
        } catch (e: Exception) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.MUSIC_GENERATION_FAILED)
        } finally {
            withContext(NonCancellable) { musicGenerator.release() }
        }
    }

    /** Модель видео: выбранная в настройках, иначе первая скачанная для этой платформы. */
    fun videoModel(): CatalogModel? {
        val ready = models.models.value.filter { it.model.kind == ModelKind.VIDEO && it.status == ModelStatus.Downloaded }
        val selected = settings.settings.value.videoModelId
        return (ready.firstOrNull { it.model.id == selected } ?: ready.firstOrNull())?.model
    }

    /** Длительность ролика для [model], секунды: выбранная в настройках, если модель её умеет, иначе ближайшая. */
    fun videoSeconds(model: CatalogModel): Int {
        val video = model.video ?: return 1
        val wanted = settings.settings.value.videoSeconds.takeIf { it > 0 } ?: video.defaultSeconds
        return video.availableDurations.minByOrNull { kotlin.math.abs(it - wanted) } ?: video.defaultSeconds
    }

    /** Скачанная модель изображений для этой платформы (каталог уже отфильтрован по платформе). */
    fun imageModel(): CatalogModel? = models.models.value
        .firstOrNull { it.model.kind == ModelKind.IMAGE && it.status == ModelStatus.Downloaded }
        ?.model

    private fun reserve(chatId: String): Boolean {
        stopRequested = false
        if (!mutableActive.compareAndSet(null, ActiveGeneration(chatId = chatId, messageId = "", text = ""))) return false
        publishActive()
        return true
    }

    private suspend fun generateReply(chatId: String, verification: Boolean = false) {
        val messages = chats.messagesSnapshot(chatId)
        val modelId = models.activeModelId.value
        val reply = chats.addMessage(chatId, MessageRole.ASSISTANT, "", MessageStatus.GENERATING, modelId)
        setActive(ActiveGeneration(chatId, reply.id, ""))

        val model = modelId?.let(models::catalogModel)
        if (model == null) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.NO_MODEL)
            return
        }
        val path = models.downloadedFilePath(model.id)
        if (path == null) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.MODEL_NOT_DOWNLOADED)
            return
        }
        // Без модуля зрения изображения из истории не передаются; на новое изображение ответить нельзя.
        val projectorPath = models.downloadedProjectorPath(model.id)
        val lastUser = messages.lastOrNull { it.role == MessageRole.USER }
        if (projectorPath == null && lastUser != null && lastUser.images.isNotEmpty()) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.IMAGES_NOT_SUPPORTED)
            return
        }
        val persona = personaProvider.persona()
        val found = try {
            lastUser?.content?.takeIf { it.isNotBlank() }
                ?.let { documents.retrieve(chatId, it, documentBudget(withChosenContext(model))) }
                .orEmpty()
        } catch (e: DocumentException) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.NO_EMBEDDING_MODEL)
            return
        } catch (e: InferenceException) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, e.error.toGenerationError())
            return
        }
        if (found.isNotEmpty()) {
            chats.attachSources(chatId, reply.id, found.map { MessageSource(it.documentName, it.page) }.distinct())
        }

        val live = StringBuilder()
        val steps = mutableListOf<AgentStep>()
        try {
            // Чат и рисование не держат модели в памяти одновременно: телефону не хватит памяти на обе.
            imageGenerator.release()
            engine.load(withChosenContext(model), path, projectorPath)
            if (!stopRequested) {
                val current = settings.settings.value
                val safeRule = persona.safeModeRule.takeIf { current.safeMode && it.isNotBlank() }
                // Старое начало длинного диалога заменяется кратким содержанием, а не обрезается.
                val (digest, recent) = compactHistory(chatId, messages, persona, withChosenContext(model).contextLength)
                val history = promptHistory(recent, withImages = projectorPath != null)
                    .let { if (found.isEmpty()) it else withDocuments(it, found, persona) }
                val memoryNote = if (current.memoryEnabled) memoryNote(persona) else null
                val digestNote = digest?.let { persona.summaryNote.takeIf { note -> note.isNotBlank() }?.replace("{summary}", it.text) }
                val request = GenerationRequest(
                    systemPrompt = listOfNotNull(persona.systemPrompt, persona.profileInstructions[current.generationProfile], dateNote(persona), safeRule, memoryNote, digestNote)
                        .joinToString("\n\n"),
                    messages = history,
                    primer = persona.primer,
                    reminder = persona.reminder,
                    sampling = current.generationProfile.sampling,
                    maxTokens = if (current.generationProfile == ai.prism.domain.model.GenerationProfile.STANDARD) 2048 else minOf(current.generationProfile.maxTokens, (withChosenContext(model).contextLength / 2).coerceAtLeast(256)),
                    thinkingBudget = settings.settings.value.thinkingLevel.budgetTokens,
                )
                val toolbox = agentToolbox(chatId, reply.id, vision = projectorPath != null)
                if (toolbox == null) {
                    engine.generate(request).collect { piece ->
                        live.append(piece)
                        setActive(ActiveGeneration(chatId, reply.id, live.toString()))
                    }
                } else {
                    val userRequests = messages.filter { it.role == MessageRole.USER }.map { it.content }
                    runAgent(chatId, reply.id, request, persona, toolbox, live, steps,
                        writeRequested = WorkspaceActionPolicy.requiresWrite(lastUser?.content.orEmpty(), userRequests.dropLast(1)),
                        verificationRequested = verification, qwenCoderTools = model.chatFormat == ChatFormat.CHATML)
                }
            }
            val status = if (stopRequested) MessageStatus.STOPPED else MessageStatus.COMPLETE
            if (steps.isNotEmpty()) chats.attachSteps(chatId, reply.id, steps)
            chats.updateMessage(reply.id, live.toString().trim(), status, null)
            if (status == MessageStatus.COMPLETE && settings.settings.value.memoryEnabled) {
                lastUser?.content?.let { learnFacts(it, persona) }
            }
        } catch (e: InferenceException) {
            if (steps.isNotEmpty()) chats.attachSteps(chatId, reply.id, steps)
            chats.updateMessage(reply.id, live.toString().trim(), MessageStatus.FAILED, e.error.toGenerationError())
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (steps.isNotEmpty()) chats.attachSteps(chatId, reply.id, steps)
                chats.updateMessage(reply.id, live.toString().trim(), MessageStatus.STOPPED, null)
            }
            throw e
        }
    }

    /** Факты о пользователе для системного промпта или `null`, если их нет. */
    private suspend fun memoryNote(persona: AssistantPersona): String? {
        val facts = memories.snapshot()
        if (facts.isEmpty() || persona.memoryNote.isBlank()) return null
        return persona.memoryNote.replace("{facts}", facts.joinToString("\n") { "- " + it.text })
    }

    /**
     * Если в сообщении могут быть сведения о пользователе («меня зовут…», «запомни…»), модель выписывает
     * из него устойчивые факты — они попадут в промпт других диалогов. Ошибка не мешает ответу.
     */
    private suspend fun learnFacts(text: String, persona: AssistantPersona) {
        if (persona.memoryExtraction.isBlank() || !MemoryCues.mayContainFacts(text)) return
        val answer = try {
            engine.generate(
                GenerationRequest(
                    systemPrompt = persona.memoryExtraction,
                    messages = listOf(PromptMessage(MessageRole.USER, text)),
                    maxTokens = MEMORY_MAX_TOKENS,
                    sampling = SamplingParams(temperature = 0.1f),
                ),
            ).toList().joinToString("")
        } catch (e: InferenceException) {
            return
        }
        MemoryCues.parseFacts(answer).forEach { memories.add(it) }
    }

    /**
     * Сообщения для промпта: если диалог не помещается в контекст, его начало пересказывается моделью
     * (краткое содержание хранится в диалоге и дополняется), а в промпт идут последние сообщения.
     */
    private suspend fun compactHistory(
        chatId: String,
        messages: List<ChatMessage>,
        persona: AssistantPersona,
        contextTokens: Int,
    ): Pair<ChatDigest?, List<ChatMessage>> {
        val saved = chats.summaryOf(chatId)
        val start = saved?.let { digest -> messages.indexOfFirst { it.id == digest.untilMessageId } + 1 } ?: 0
        val digest = saved?.takeIf { start > 0 }
        val rest = messages.drop(start)
        val budget = (contextTokens.coerceAtLeast(MIN_SUMMARY_CONTEXT) * HISTORY_CHARS_PER_TOKEN).toInt()
        if (rest.sumOf { it.content.length } <= budget || rest.size <= KEEP_RECENT_MESSAGES || persona.summaryInstruction.isBlank()) {
            return digest to rest
        }
        val old = rest.dropLast(KEEP_RECENT_MESSAGES)
        val transcript = buildString {
            digest?.let { append(it.text).append("\n\n") }
            old.forEach { message ->
                append(if (message.role == MessageRole.USER) "Пользователь: " else "Ассистент: ")
                append(message.content.trim()).append('\n')
            }
        }
        val summary = try {
            engine.generate(
                GenerationRequest(
                    systemPrompt = persona.summaryInstruction,
                    messages = listOf(PromptMessage(MessageRole.USER, transcript.takeLast(budget))),
                    maxTokens = SUMMARY_MAX_TOKENS,
                    sampling = SamplingParams(temperature = 0.2f),
                ),
            ).toList().joinToString("").trim()
        } catch (e: InferenceException) {
            ""
        }
        if (summary.isEmpty() || stopRequested) return digest to rest
        val updated = ChatDigest(summary, old.last().id)
        chats.setSummary(chatId, updated)
        return updated to rest.takeLast(KEEP_RECENT_MESSAGES)
    }

    /** Текстовый энкодер видео: выбранный в настройках или по объёму оперативной памяти. */
    fun textEncoderMode(): TextEncoderMode =
        settings.settings.value.textEncoderMode ?: TextEncoderMode.auto(totalRamBytes)

    /**
     * Файлы модели видео с выбранным текстовым энкодером: вместо полного файла — его сжатая копия,
     * которая создаётся при первом использовании и потом лежит рядом с ним.
     */
    private suspend fun withTextEncoder(
        model: CatalogModel,
        files: Map<String, String>,
        onCompressing: () -> Unit,
    ): Map<String, String> {
        val path = files[TextEncoderMode.ROLE] ?: return files
        val component = model.components.firstOrNull { it.role == TextEncoderMode.ROLE } ?: return files
        if (!TextEncoderMode.canCompress(component.file.fileName)) return files
        val mode = textEncoderMode()
        val type = mode.weightType ?: return files
        val compressed = TextEncoderMode.pathFor(path, mode)
        imageGenerator.compressWeights(path, compressed, type, onCompressing)
        return files + (TextEncoderMode.ROLE to compressed)
    }

    private suspend fun videoReply(chatId: String, prompt: String, source: ImageAttachment?) {
        val model = videoModel()
        val reply = chats.addMessage(chatId, MessageRole.ASSISTANT, "", MessageStatus.GENERATING, model?.id)
        fun progress(drawing: DrawingProgress) {
            setActive(ActiveGeneration(chatId, reply.id, "", drawing.copy(video = true)))
        }
        progress(DrawingProgress(DrawingStage.TRANSLATING))
        val files = model?.let { models.imageModelFiles(it.id) }
        val videoSettings = model?.video
        if (model == null || files == null || videoSettings == null || !imageGenerator.isSupported) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.NO_VIDEO_MODEL)
            return
        }
        val safeMode = settings.settings.value.safeMode
        if (safeMode && ImagePromptPolicy.containsBlockedWords(prompt)) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.BLOCKED_BY_SAFE_MODE)
            return
        }
        try {
            val englishPrompt = when (val prepared = prepareImagePrompt(prompt, safeMode)) {
                is PreparedPrompt.Ready -> prepared.prompt
                is PreparedPrompt.Rejected -> {
                    engine.unload()
                    val status = if (stopRequested) MessageStatus.STOPPED else MessageStatus.FAILED
                    chats.updateMessage(reply.id, "", status, prepared.error.takeIf { !stopRequested })
                    return
                }
            }
            engine.unload()
            if (stopRequested) {
                chats.updateMessage(reply.id, "", MessageStatus.STOPPED, null)
                return
            }
            val modelFiles = withTextEncoder(model, files) { progress(DrawingProgress(DrawingStage.COMPRESSING)) }
            // Фото оживляет только модель, которая это умеет; размер кадра — как в настройках модели.
            val photo = source?.takeIf { videoSettings.imageToVideo }
            val segments = VideoPlan.segments(videoSettings, videoSeconds(model))
            val clip = VideoClipBuilder()
            var startImage: ImageAttachment? = photo
            // Последний кадр куска — временная картинка, от которой снимается следующий кусок.
            var lastFrame: ImageAttachment? = null
            var stopped = false
            try {
                for ((index, frames) in segments.withIndex()) {
                    if (stopRequested) {
                        stopped = true
                        break
                    }
                    val part = if (segments.size > 1) index + 1 else 0
                    val video = imageGenerator.generateVideo(
                        model = model,
                        files = modelFiles,
                        request = VideoRequest(
                            prompt = englishPrompt,
                            negativePrompt = videoSettings.negativePrompt,
                            width = videoSettings.width,
                            height = videoSettings.height,
                            frames = frames,
                            fps = videoSettings.fps,
                            steps = videoSettings.steps,
                            cfg = videoSettings.cfg,
                            flowShift = videoSettings.flowShift,
                            sampler = videoSettings.sampler,
                            scheduler = videoSettings.scheduler,
                            vaeTiling = videoSettings.vaeTiling,
                            mmap = videoSettings.mmap,
                            offloadToCpu = videoSettings.offloadToCpu,
                            flashAttention = videoSettings.flashAttention,
                            initImagePath = startImage?.let { images.path(it.fileName) },
                        ),
                        onProgress = { event ->
                            when (event) {
                                ImageGenerationProgress.Loading -> progress(DrawingProgress(DrawingStage.LOADING, part = part, parts = segments.size))
                                is ImageGenerationProgress.Step ->
                                    progress(DrawingProgress(DrawingStage.DRAWING, event.step, event.steps, part = part, parts = segments.size))
                            }
                        },
                    )
                    if (segments.size == 1) {
                        clip.single(video)
                        break
                    }
                    val last = clip.append(video) ?: throw ImageGenerationException(ImageGenerationError.FAILED, "Пустой кусок видео")
                    if (index < segments.lastIndex) {
                        lastFrame?.let { images.delete(listOf(it.fileName)) }
                        lastFrame = images.save(last)
                        startImage = lastFrame
                    }
                }
            } catch (e: ImageGenerationException) {
                // Остановлено после первых кусков — сохраняем то, что уже снято.
                if (!(e.error == ImageGenerationError.CANCELLED || stopRequested) || clip.isEmpty) throw e
                stopped = true
            } finally {
                lastFrame?.let { withContext(NonCancellable) { images.delete(listOf(it.fileName)) } }
            }
            val result = clip.build() ?: throw ImageGenerationException(ImageGenerationError.CANCELLED, "Видео остановлено")
            val attachment = images.saveVideo(result.avi, result.width, result.height)
            chats.attachImages(chatId, reply.id, listOf(attachment))
            chats.updateMessage(reply.id, "", if (stopped) MessageStatus.STOPPED else MessageStatus.COMPLETE, null)
        } catch (e: ImageGenerationException) {
            if (e.error == ImageGenerationError.CANCELLED || stopRequested) {
                chats.updateMessage(reply.id, "", MessageStatus.STOPPED, null)
            } else {
                val error = e.error.toGenerationError()
                    .let { if (it == GenerationError.IMAGE_GENERATION_FAILED) GenerationError.VIDEO_GENERATION_FAILED else it }
                    .let { if (it == GenerationError.NO_IMAGE_MODEL) GenerationError.NO_VIDEO_MODEL else it }
                chats.updateMessage(reply.id, "", MessageStatus.FAILED, error)
            }
        } catch (e: ImageException) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.VIDEO_GENERATION_FAILED)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { chats.updateMessage(reply.id, "", MessageStatus.STOPPED, null) }
            throw e
        }
    }

    /**
     * Инструменты для ответа: папка проекта диалога (если выбрана и доступна) и интернет (если включён).
     * `null` — инструментов нет, ассистент отвечает обычным текстом.
     */
    private suspend fun agentToolbox(chatId: String, replyId: String, vision: Boolean): AgentToolbox? {
        val current = settings.settings.value
        val internet = web?.takeIf { current.internetEnabled }
        val location = chats.workspaceOf(chatId)
        val folder = location?.let { workspaces?.open(it) }
        val pc = computer?.takeIf { current.computerEnabled }
        val mcpTools = mcp?.tools(current.internetEnabled).orEmpty()
        if (internet == null && folder == null && pc == null && mcpTools.isEmpty()) return null
        return AgentToolbox(
            workspace = folder,
            web = internet,
            commands = commands,
            autoApplyEdits = current.autoApplyEdits,
            beforeProjectWrite = { path, before, after ->
                try { changeJournal.record(chatId, replyId, checkNotNull(location), path, before, after) }
                catch (e: CancellationException) { throw e }
                catch (e: ai.prism.domain.agent.WorkspaceException) { throw e }
                catch (e: Exception) { throw ai.prism.domain.agent.WorkspaceException("Cannot save undo backup", e) }
            },
            folderDepth = current.folderDepth,
            computer = pc,
            // Экран без зрения модели бесполезен: она не увидит снимок.
            screen = screen?.takeIf { pc != null && vision },
            imageStore = images,
            mcpTools = mcpTools,
            callMcp = { tool, arguments -> checkNotNull(mcp).call(tool, arguments) },
            onRunning = { running -> updateActive { it?.copy(running = running) } },
            approve = { action -> askApproval(chatId, replyId, action) },
            newId = ::newId,
        ).takeIf { it.tools.isNotEmpty() || it.mcpTools.isNotEmpty() }
    }

    /** Дата и справка о свежих версиях для системного промпта; `null`, если в ресурсах её нет. */
    private fun dateNote(persona: AssistantPersona): String? =
        persona.currentDateNote.takeIf { it.isNotBlank() }?.replace("{date}", today())

    /**
     * Ответ с инструментами: модель пишет вызов `<tool …>`, приложение выполняет его и возвращает результат,
     * пока модель не ответит обычным текстом (не больше [AppSettings.agentSteps] шагов, 0 — без ограничения).
     * В [live] — видимый текст ответа, в [steps] — выполненные шаги.
     */
    private suspend fun runAgent(
        chatId: String,
        replyId: String,
        base: GenerationRequest,
        persona: AssistantPersona,
        toolbox: AgentToolbox,
        live: StringBuilder,
        steps: MutableList<AgentStep>,
        writeRequested: Boolean,
        verificationRequested: Boolean = false,
        qwenCoderTools: Boolean = false,
    ) {
        val workspaceName = chats.workspaceOf(chatId)?.let { location -> workspaces?.open(location)?.name }
        val requiresWrite = workspaceName != null && (writeRequested || verificationRequested)
        val retryNote = if (verificationRequested) persona.verificationRetry else persona.agentWorkspaceActionRetry
        val incompleteNote = if (verificationRequested) persona.verificationIncomplete else persona.agentWorkspaceActionIncomplete
        fun hasWritten() = steps.any {
            it.status == AgentStepStatus.DONE && if (verificationRequested) it.tool == AgentTool.RUN_COMMAND
                else it.tool in setOf(AgentTool.WRITE_FILE, AgentTool.EDIT_FILE, AgentTool.DOWNLOAD_FILE)
        }
        var textRetries = 0
        var actionRejected = false
        val context = listOfNotNull(
            workspaceName?.let { persona.agentWorkspaceNote.replace("{name}", it) },
            persona.agentInternetNote.takeIf { AgentTool.WEB_SEARCH in toolbox.tools && it.isNotBlank() },
            persona.agentComputerNote.takeIf { AgentTool.SYSTEM_INFO in toolbox.tools && it.isNotBlank() }
                ?.replace("{home}", computer?.homePath.orEmpty()),
            persona.agentScreenNote.takeIf { AgentTool.SCREENSHOT in toolbox.tools && it.isNotBlank() },
            persona.agentMcpNote.takeIf { toolbox.mcpTools.isNotEmpty() && it.isNotBlank() },
        ).joinToString("\n")
        val tools = (
            toolbox.tools.map { tool -> "- " + (persona.toolDescriptions[tool] ?: tool.toolName) } +
                toolbox.mcpTools.map { tool ->
                    val parameters = tool.parameters.joinToString { (name, _) -> name }
                    "- ${tool.callName} ($parameters) — " + tool.description.lineSequence().firstOrNull().orEmpty().take(MCP_DESCRIPTION_CHARS)
                }
            ).joinToString("\n")
        val template = if (qwenCoderTools && persona.agentInstructionsQwenCoder.isNotBlank()) {
            persona.agentInstructionsQwenCoder
        } else {
            persona.agentInstructions
        }
        val instructions = template.replace("{context}", context).replace("{tools}", tools)
        // Напоминание о личности остаётся у вопроса пользователя: так при следующих шагах кэш промпта не теряется.
        var dialog = ChatPromptFormatter.withReminder(base.messages, base.reminder)
        val parts = mutableListOf<String>()
        fun show(current: String) {
            live.setLength(0)
            live.append((parts + current).filter { it.isNotBlank() }.joinToString("\n\n"))
            setActive(ActiveGeneration(chatId, replyId, live.toString(), steps = steps.toList()))
        }
        // Предел шагов из настроек; 0 — без ограничения (ответ останавливает только пользователь).
        val maxSteps = settings.settings.value.agentSteps
        var executed = 0
        val recentCalls = ArrayDeque<ToolCall>()
        val failedCounts = mutableMapOf<String, Int>()
        var lastFailedKey: String? = null
        var failedStreak = 0
        var loopStopped = false
        while (true) {
            // После последнего разрешённого шага или при зацикливании модель только подводит итог: новый вызов не выполняется.
            val finalRound = loopStopped || (maxSteps > 0 && executed == maxSteps)
            val raw = StringBuilder()
            val request = base.copy(
                systemPrompt = base.systemPrompt + "\n\n" + instructions,
                messages = dialog,
                reminder = "",
            )
            engine.generate(request)
                .transformWhile { piece ->
                    raw.append(piece)
                    emit(piece)
                    !ToolCallProtocol.isComplete(raw.toString())
                }
                .collect {
                    // Do not stream unverified claims of completed edits before a real write succeeds.
                    if (!requiresWrite || (hasWritten() && !actionRejected)) show(ToolCallProtocol.visibleText(raw.toString()))
                }
            val visible = ToolCallProtocol.visibleText(raw.toString())
            val call = ToolCallProtocol.parse(raw.toString())
            if (stopRequested) return
            // Never ask again after a refusal, including when earlier edits already succeeded.
            if (requiresWrite && actionRejected) {
                parts.clear()
                parts += if (verificationRequested) incompleteNote else if (hasWritten()) persona.agentWorkspaceActionRejected else incompleteNote
                show("")
                return
            }
            if (requiresWrite && !hasWritten() && call == null) {
                if (!finalRound && !actionRejected && textRetries < 2) {
                    textRetries++
                    dialog = dialog + PromptMessage(MessageRole.ASSISTANT, raw.toString()) +
                        PromptMessage(MessageRole.USER, retryNote)
                    continue
                }
                parts.clear()
                parts += incompleteNote
                if (finalRound && !loopStopped && maxSteps > 0) {
                    parts += persona.agentStepLimitNotice.replace("{steps}", maxSteps.toString())
                }
                show("")
                return
            }
            // Pre-tool prose can also falsely claim completion. Keep only grounded output.
            if (!requiresWrite || hasWritten()) parts += visible
            show("")
            if (stopRequested || call == null) return
            if (finalRound) {
                // Модель не остановилась: говорим пользователю, что работу можно продолжить следующим сообщением.
                if (!loopStopped) {
                    persona.agentStepLimitNotice.takeIf { it.isNotBlank() }?.let { notice ->
                        parts += notice.replace("{steps}", maxSteps.toString())
                        show("")
                    }
                }
                return
            }
            val outcome = runTool(toolbox, call) ?: return
            outcome.step?.let { steps += it }
            if (outcome.step?.status == AgentStepStatus.REJECTED || (verificationRequested && outcome.step?.tool == AgentTool.RUN_COMMAND && outcome.step.status == AgentStepStatus.FAILED)) actionRejected = true
            executed++
            show("")
            if (stopRequested) return
            val step = outcome.step
            val failedKey = if (step?.status == AgentStepStatus.FAILED) "${step.tool}:${step.title}" else null
            failedStreak = if (failedKey != null && failedKey == lastFailedKey) failedStreak + 1 else if (failedKey != null) 1 else 0
            lastFailedKey = failedKey
            val failedTotal = if (failedKey != null) {
                val count = (failedCounts[failedKey] ?: 0) + 1
                failedCounts[failedKey] = count
                count
            } else 0
            val identicalCount = recentCalls.count { it == call }
            recentCalls.addLast(call)
            if (recentCalls.size > 6) recentCalls.removeFirst()
            val atLimit = maxSteps > 0 && executed == maxSteps
            val editOrWriteRepeated = (call.name == AgentTool.WRITE_FILE.toolName || call.name == AgentTool.EDIT_FILE.toolName) && identicalCount >= 2
            loopStopped = !atLimit && (failedStreak >= 3 || failedTotal >= 3 || editOrWriteRepeated)
            val repeatNote = when {
                atLimit -> ""
                loopStopped -> "\n\n[Note: tools are disabled because the same call or error repeated. Do not call any tool — summarize what was done and reply to the user in plain text.]"
                failedStreak >= 2 || failedTotal >= 2 -> "\n\n[Note: this tool failed repeatedly. Fix the arguments, read the needed lines with read_file(start_line=...), or move to the next step.]"
                identicalCount >= 1 -> "\n\n[Note: you already made this exact tool call. Do not repeat identical calls — continue with the next step or reply to the user.]"
                else -> ""
            }
            val limitNote = if (atLimit && persona.agentStepLimit.isNotBlank()) {
                "\n\n" + persona.agentStepLimit
            } else {
                ""
            }
            dialog = dialog +
                PromptMessage(MessageRole.ASSISTANT, ToolCallProtocol.callText(raw.toString())) +
                PromptMessage(MessageRole.USER, ToolCallProtocol.result(call.name, outcome.result, qwenCoderFormat = qwenCoderTools) + repeatNote + limitNote, outcome.images)
        }
    }

    /** Выполняет вызов; `null` — пользователь нажал «Стоп», пока инструмент работал (команда завершена). */
    private suspend fun runTool(toolbox: AgentToolbox, call: ToolCall): ToolOutcome? = coroutineScope {
        val job = async { toolbox.execute(call) }
        toolJob = job
        try {
            job.await()
        } catch (e: CancellationException) {
            if (!stopRequested || !isActive) throw e
            null
        } finally {
            toolJob = null
        }
    }

    /** Ждёт, пока пользователь разрешит или отклонит действие (или остановит ответ). */
    private suspend fun askApproval(chatId: String, replyId: String, action: PendingAction): Boolean {
        if (stopRequested) return false
        val waiting = CompletableDeferred<Boolean>()
        decision = waiting
        updateActive { current ->
            (current ?: ActiveGeneration(chatId, replyId, "")).copy(pending = action)
        }
        return try {
            waiting.await()
        } finally {
            decision = null
            updateActive { it?.copy(pending = null) }
        }
    }

    private suspend fun drawReply(chatId: String, prompt: String, source: ImageAttachment?) {
        val model = imageModel()
        val reply = chats.addMessage(chatId, MessageRole.ASSISTANT, "", MessageStatus.GENERATING, model?.id)
        fun progress(drawing: DrawingProgress) {
            setActive(ActiveGeneration(chatId, reply.id, "", drawing))
        }
        progress(DrawingProgress(DrawingStage.TRANSLATING))
        val files = model?.let { models.imageModelFiles(it.id) }
        val imageSettings = model?.image
        if (model == null || files == null || imageSettings == null || !imageGenerator.isSupported) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.NO_IMAGE_MODEL)
            return
        }
        val safeMode = settings.settings.value.safeMode
        if (safeMode && ImagePromptPolicy.containsBlockedWords(prompt)) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.BLOCKED_BY_SAFE_MODE)
            return
        }
        try {
            val englishPrompt = when (val prepared = prepareImagePrompt(prompt, safeMode)) {
                is PreparedPrompt.Ready -> prepared.prompt
                is PreparedPrompt.Rejected -> {
                    engine.unload()
                    val status = if (stopRequested) MessageStatus.STOPPED else MessageStatus.FAILED
                    chats.updateMessage(reply.id, "", status, prepared.error.takeIf { !stopRequested })
                    return
                }
            }
            engine.unload()
            if (stopRequested) {
                chats.updateMessage(reply.id, "", MessageStatus.STOPPED, null)
                return
            }
            val (width, height) = if (source != null) {
                fitImageSize(source.width, source.height, maxOf(imageSettings.width, imageSettings.height))
            } else {
                imageSettings.width to imageSettings.height
            }
            val image = imageGenerator.generate(
                model = model,
                files = files,
                request = ImageRequest(
                    prompt = englishPrompt,
                    width = width,
                    height = height,
                    steps = imageSettings.steps,
                    cfg = imageSettings.cfg,
                    guidance = imageSettings.guidance,
                    sampler = imageSettings.sampler,
                    scheduler = imageSettings.scheduler,
                    vaeTiling = imageSettings.vaeTiling,
                    mmap = imageSettings.mmap,
                    initImagePath = source?.let { images.path(it.fileName) },
                    strength = imageSettings.strength,
                ),
                onProgress = { event ->
                    when (event) {
                        ImageGenerationProgress.Loading -> progress(DrawingProgress(DrawingStage.LOADING))
                        is ImageGenerationProgress.Step -> progress(DrawingProgress(DrawingStage.DRAWING, event.step, event.steps))
                    }
                },
            )
            val attachment = images.save(image.png)
            if (safeMode && isUnsafePicture(attachment)) {
                images.delete(listOf(attachment.fileName))
                chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.BLOCKED_BY_SAFE_MODE)
                return
            }
            chats.attachImages(chatId, reply.id, listOf(attachment))
            chats.updateMessage(reply.id, "", MessageStatus.COMPLETE, null)
        } catch (e: ImageGenerationException) {
            if (e.error == ImageGenerationError.CANCELLED || stopRequested) {
                chats.updateMessage(reply.id, "", MessageStatus.STOPPED, null)
            } else {
                chats.updateMessage(reply.id, "", MessageStatus.FAILED, e.error.toGenerationError())
            }
        } catch (e: ImageException) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.IMAGE_GENERATION_FAILED)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { chats.updateMessage(reply.id, "", MessageStatus.STOPPED, null) }
            throw e
        }
    }

    private sealed interface PreparedMusic {
        data class Ready(val request: MusicRequest) : PreparedMusic
        data class Rejected(val error: GenerationError) : PreparedMusic
    }

    private suspend fun prepareMusicPrompt(prompt: String, safeMode: Boolean, seconds: Int): PreparedMusic {
        val model = translatorModel()
        val path = model?.let { models.downloadedFilePath(it.id) }
        if (model == null || path == null) return PreparedMusic.Rejected(GenerationError.TRANSLATOR_REQUIRED)
        val persona = personaProvider.persona()
        if (persona.musicPromptInstruction.isBlank()) return PreparedMusic.Rejected(GenerationError.MUSIC_GENERATION_FAILED)
        val instruction = persona.musicPromptInstruction.replace("{seconds}", seconds.toString()) + "\n" +
            (if (safeMode) persona.musicSafetyInstruction else "")
        val answer = try {
            imageGenerator.release()
            musicGenerator.release()
            engine.load(withChosenContext(model), path, null)
            if (stopRequested) return PreparedMusic.Rejected(GenerationError.MUSIC_GENERATION_FAILED)
            engine.generate(
                GenerationRequest(
                    systemPrompt = instruction,
                    messages = listOf(PromptMessage(MessageRole.USER,
                        "vocal_language: ${MusicPromptPolicy.language(prompt)}\n\n$prompt")),
                    maxTokens = minOf(maxOf(1200, seconds * 6), (withChosenContext(model).contextLength / 2).coerceAtLeast(256)),
                    sampling = SamplingParams(temperature = 0.3f),
                ),
            ).toList().joinToString("").trim()
        } catch (e: InferenceException) {
            return PreparedMusic.Rejected(if (e.error == InferenceError.NOT_ENOUGH_MEMORY) {
                GenerationError.NOT_ENOUGH_MEMORY
            } else GenerationError.MUSIC_GENERATION_FAILED)
        }
        if (safeMode && ImagePromptPolicy.isBlockedVerdict(answer)) return PreparedMusic.Rejected(GenerationError.BLOCKED_BY_SAFE_MODE)
        return MusicPromptPolicy.parse(prompt, answer, seconds)?.let { PreparedMusic.Ready(it) }
            ?: PreparedMusic.Rejected(GenerationError.MUSIC_GENERATION_FAILED)
    }

    /**
     * Готовит описание для генератора: переводит на английский моделью чата (генераторы понимают только английские
     * подписи — русский текст дал бы картинку не по описанию), в безопасном режиме — заодно проверяет его.
     *
     * Переводит активная модель чата, а если она не выбрана — самая лёгкая скачанная ([translatorModel]).
     * Русское описание без перевода не рисуется: [GenerationError.TRANSLATOR_REQUIRED], если модели чата нет,
     * и [GenerationError.TRANSLATION_FAILED], если перевод не получился. Английское описание без модели чата
     * рисуется как есть (в безопасном режиме его уже проверил список запрещённых слов).
     */
    private suspend fun prepareImagePrompt(prompt: String, safeMode: Boolean): PreparedPrompt {
        val russian = ImagePromptPolicy.hasCyrillic(prompt)
        if (!safeMode && !russian) return PreparedPrompt.Ready(prompt)
        val untranslated = if (russian) PreparedPrompt.Rejected(GenerationError.TRANSLATION_FAILED) else PreparedPrompt.Ready(prompt)
        val model = translatorModel()
        val path = model?.let { models.downloadedFilePath(it.id) }
        if (model == null || path == null) {
            return if (russian) PreparedPrompt.Rejected(GenerationError.TRANSLATOR_REQUIRED) else PreparedPrompt.Ready(prompt)
        }
        val persona = personaProvider.persona()
        val instruction = (if (safeMode) persona.imageSafetyInstruction else persona.imagePromptInstruction)
            .ifBlank { return untranslated }
        val answer = try {
            imageGenerator.release()
            engine.load(withChosenContext(model), path, null)
            if (stopRequested) return untranslated
            engine.generate(
                GenerationRequest(
                    systemPrompt = instruction,
                    messages = listOf(PromptMessage(MessageRole.USER, prompt)),
                    maxTokens = TRANSLATION_MAX_TOKENS,
                    sampling = SamplingParams(temperature = 0f),
                ),
            ).toList().joinToString("").trim().trim('"', '«', '»').trim()
        } catch (e: InferenceException) {
            return if (russian && e.error == InferenceError.NOT_ENOUGH_MEMORY) {
                PreparedPrompt.Rejected(GenerationError.NOT_ENOUGH_MEMORY)
            } else {
                untranslated
            }
        }
        return when {
            safeMode && (ImagePromptPolicy.isBlockedVerdict(answer) || ImagePromptPolicy.isRefusal(answer)) ->
                PreparedPrompt.Rejected(GenerationError.BLOCKED_BY_SAFE_MODE)
            ImagePromptPolicy.isUsableTranslation(prompt, answer) -> PreparedPrompt.Ready(answer)
            else -> untranslated
        }
    }

    /**
     * Модель чата для перевода описаний: активная, а если она не выбрана или не скачана — самая лёгкая из скачанных
     * (сначала среди тех, что помещаются в память устройства).
     */
    private fun translatorModel(): CatalogModel? {
        val ready = models.models.value.filter { it.model.kind == ModelKind.CHAT && it.chatReady }
        val activeId = models.activeModelId.value
        return (
            ready.firstOrNull { it.model.id == activeId }
                ?: ready.filter { it.fitsInMemory }.minByOrNull { it.model.sizeBytes }
                ?: ready.minByOrNull { it.model.sizeBytes }
            )?.model
    }

    /**
     * Безопасный режим: модель чата с модулем зрения смотрит на готовую картинку.
     * Без такой модели проверка пропускается (остаются проверки описания).
     */
    private suspend fun isUnsafePicture(picture: ImageAttachment): Boolean {
        val model = models.activeModelId.value?.let(models::catalogModel)?.takeIf { it.kind == ModelKind.CHAT } ?: return false
        val path = models.downloadedFilePath(model.id) ?: return false
        val projector = models.downloadedProjectorPath(model.id) ?: return false
        val question = personaProvider.persona().imageReviewInstruction.ifBlank { return false }
        return try {
            imageGenerator.release()
            engine.load(withChosenContext(model), path, projector)
            val answer = engine.generate(
                GenerationRequest(
                    systemPrompt = "",
                    messages = listOf(
                        PromptMessage(
                            MessageRole.USER,
                            question,
                            images = listOf(PromptImage(id = picture.id, path = images.path(picture.fileName))),
                        ),
                    ),
                    maxTokens = TRANSLATION_MAX_TOKENS,
                    sampling = SamplingParams(temperature = 0f),
                ),
            ).toList().joinToString("")
            ImagePromptPolicy.isUnsafeReview(answer)
        } catch (e: InferenceException) {
            false
        }
    }

    private fun ImageGenerationError.toGenerationError(): GenerationError = when (this) {
        ImageGenerationError.NOT_SUPPORTED -> GenerationError.NO_IMAGE_MODEL
        ImageGenerationError.GPU_DRIVER_MISSING -> GenerationError.GPU_DRIVER_MISSING
        ImageGenerationError.NOT_ENOUGH_MEMORY -> GenerationError.NOT_ENOUGH_MEMORY
        ImageGenerationError.MODEL_CORRUPTED -> GenerationError.MODEL_CORRUPTED
        ImageGenerationError.CANCELLED, ImageGenerationError.FAILED -> GenerationError.IMAGE_GENERATION_FAILED
    }

    /** Последнее сообщение пользователя с найденными фрагментами файлов (только в промпте). */
    private fun withDocuments(history: List<PromptMessage>, found: List<DocumentChunk>, persona: AssistantPersona): List<PromptMessage> {
        val index = history.indexOfLast { it.role == MessageRole.USER }
        if (index < 0) return history
        val fragments = found.withIndex().joinToString("\n\n") { (number, chunk) ->
            val page = chunk.page?.let { ", ${persona.pageLabel} $it" }.orEmpty()
            "[${number + 1}] ${chunk.documentName}$page\n${chunk.text}"
        }
        val question = history[index]
        val content = persona.documentContext.replace("{fragments}", fragments).replace("{question}", question.content)
        return history.toMutableList().apply { set(index, question.copy(content = content)) }
    }

    /** Модель чата с контекстом из настроек (если выбран); движок уменьшит его до обученного предела модели. */
    private fun withChosenContext(model: CatalogModel): CatalogModel {
        val chosen = settings.settings.value.contextLength.takeIf { it > 0 } ?: settings.settings.value.generationProfile.defaultContext
        return if (chosen > 0 && model.kind == ModelKind.CHAT) model.copy(contextLength = chosen) else model
    }

    /** Сколько символов фрагментов файлов поместить в промпт: около трети контекста модели. */
    private fun documentBudget(model: CatalogModel): Int =
        (model.contextLength * DOCUMENT_CHARS_PER_CONTEXT_TOKEN).toInt().coerceIn(MIN_DOCUMENT_CHARS, MAX_DOCUMENT_CHARS)

    /** Завершённые реплики диалога для промпта; изображения — только если модель их понимает. */
    private fun promptHistory(messages: List<ChatMessage>, withImages: Boolean): List<PromptMessage> = messages
        .filter { it.status == MessageStatus.COMPLETE || it.status == MessageStatus.STOPPED }
        .map { message ->
            val attached = if (withImages && message.role == MessageRole.USER) message.images else emptyList()
            PromptMessage(
                role = message.role,
                content = message.content,
                images = attached.map { PromptImage(id = it.id, path = images.path(it.fileName)) },
            )
        }
        .filter { it.content.isNotBlank() || it.images.isNotEmpty() }

    private fun InferenceError.toGenerationError(): GenerationError = when (this) {
        InferenceError.NATIVE_LIBRARY_MISSING -> GenerationError.NATIVE_LIBRARY_MISSING
        InferenceError.NOT_ENOUGH_MEMORY -> GenerationError.NOT_ENOUGH_MEMORY
        InferenceError.MODEL_CORRUPTED -> GenerationError.MODEL_CORRUPTED
        InferenceError.CONTEXT_FULL -> GenerationError.CONTEXT_FULL
        InferenceError.IMAGES_NOT_SUPPORTED -> GenerationError.IMAGES_NOT_SUPPORTED
        InferenceError.IMAGE_FAILED -> GenerationError.IMAGE_FAILED
        InferenceError.VISION_MODULE_CORRUPTED -> GenerationError.VISION_MODULE_CORRUPTED
        InferenceError.MODEL_NOT_LOADED, InferenceError.GENERATION_FAILED -> GenerationError.UNKNOWN
    }

    internal companion object {
        private const val TITLE_LENGTH = 48

        /** Описание инструмента MCP в промпте — первая строка, не длиннее (длинные описания съедают контекст). */
        private const val MCP_DESCRIPTION_CHARS = 300
        private const val ORPHAN_IMAGE_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val TRANSLATION_MAX_TOKENS = 400
        private const val MEMORY_MAX_TOKENS = 160
        private const val SUMMARY_MAX_TOKENS = 500
        private const val KEEP_RECENT_MESSAGES = 6
        private const val MIN_SUMMARY_CONTEXT = 2048

        /** Сколько символов истории держать в промпте на токен контекста (остальное — ответ, файлы, системный промпт). */
        private const val HISTORY_CHARS_PER_TOKEN = 1.5

        private const val DOCUMENT_CHARS_PER_CONTEXT_TOKEN = 0.9
        private const val MIN_DOCUMENT_CHARS = 2_000
        private const val MAX_DOCUMENT_CHARS = 9_000
        private const val IMAGE_SIZE_STEP = 64
        private const val MIN_IMAGE_SIDE = 256

        /**
         * Размер для перерисовки фото: пропорции исходника, большая сторона — [maxSide],
         * обе стороны кратны 64 (так требуют модели) и не меньше 256.
         */
        fun fitImageSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
            val scale = maxSide.toFloat() / maxOf(width, height, 1)
            fun snap(value: Int): Int = ((value * scale / IMAGE_SIZE_STEP).roundToInt() * IMAGE_SIZE_STEP)
                .coerceIn(MIN_IMAGE_SIDE, maxSide)
            return snap(width) to snap(height)
        }

        /** Заголовок диалога — начало первой строки сообщения. */
        fun titleFrom(text: String): String {
            val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                .replace(Regex("\\s+"), " ").trim()
            return if (line.length <= TITLE_LENGTH) line else line.take(TITLE_LENGTH).trimEnd() + "…"
        }
    }
}
