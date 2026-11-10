package ai.prism.service

import ai.prism.core.newId
import ai.prism.domain.agent.AgentStep
import ai.prism.domain.agent.AgentTool
import ai.prism.domain.agent.CommandRunner
import ai.prism.domain.agent.PendingAction
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
import ai.prism.domain.inference.GenerationRequest
import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.inference.InferenceError
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.inference.PromptImage
import ai.prism.domain.inference.PromptMessage
import ai.prism.domain.inference.SamplingParams
import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.ChatMessage
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.ImageAttachment
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.model.ModelKind
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.repository.ImageException
import ai.prism.domain.repository.ImageStore
import ai.prism.domain.repository.ModelRepository
import ai.prism.domain.repository.SettingsRepository
import ai.prism.inference.ChatPromptFormatter
import ai.prism.service.agent.AgentToolbox
import ai.prism.service.agent.ToolCallProtocol
import ai.prism.service.documents.DocumentRetriever
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** Инструкция для перевода описания картинки на английский (генераторы лучше понимают английский). */
    val imagePromptInstruction: String = "",
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
    /** Про папку проекта: `{name}` — имя папки. */
    val agentWorkspaceNote: String = "{name}",
    /** Про включённый интернет. */
    val agentInternetNote: String = "",
    /** Описание каждого инструмента для модели. */
    val toolDescriptions: Map<AgentTool, String> = emptyMap(),
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
enum class DrawingStage { TRANSLATING, LOADING, DRAWING }

/** Ход создания изображения: этап и шаг из скольких. */
data class DrawingProgress(
    val stage: DrawingStage,
    val step: Int = 0,
    val steps: Int = 0,
    /** Создаётся видео, а не изображение. */
    val video: Boolean = false,
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
)

/**
 * Генерация ответов в области приложения: переживает уход с экрана чата и пересоздание Activity.
 * Одновременно идёт не больше одной генерации.
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
) {
    private val mutableActive = MutableStateFlow<ActiveGeneration?>(null)
    val active: StateFlow<ActiveGeneration?> = mutableActive.asStateFlow()

    @Volatile
    private var stopRequested = false

    @Volatile
    private var decision: CompletableDeferred<Boolean>? = null

    init {
        scope.launch {
            chats.markInterruptedGenerations()
            // Изображения брошенных черновиков, на которые не ссылается ни одно сообщение.
            images.deleteOrphans(chats.referencedImageFiles(), ORPHAN_IMAGE_AGE_MS)
        }
    }

    /**
     * Отправляет сообщение (текст и/или изображения) и запускает ответ.
     * `false` — уже идёт другая генерация или сообщение пустое.
     */
    fun send(chatId: String, text: String, attachments: List<ImageAttachment> = emptyList()): Boolean {
        val content = text.trim()
        if ((content.isEmpty() && attachments.isEmpty()) || !reserve(chatId)) return false
        scope.launch {
            try {
                chats.addMessage(chatId, MessageRole.USER, content, MessageStatus.COMPLETE, modelId = null, images = attachments)
                if (content.isNotEmpty()) chats.setTitleIfEmpty(chatId, titleFrom(content))
                generateReply(chatId)
            } finally {
                mutableActive.value = null
            }
        }
        return true
    }

    /**
     * Просит модель изображений нарисовать картинку по описанию [text]; с [source] — перерисовать это фото.
     * `false` — уже идёт другая генерация или описание пустое.
     */
    fun draw(chatId: String, text: String, source: ImageAttachment? = null): Boolean {
        val prompt = text.trim()
        if (prompt.isEmpty() || !reserve(chatId)) return false
        scope.launch {
            try {
                chats.addMessage(chatId, MessageRole.USER, prompt, MessageStatus.COMPLETE, modelId = null, images = listOfNotNull(source))
                chats.setTitleIfEmpty(chatId, titleFrom(prompt))
                drawReply(chatId, prompt, source)
            } finally {
                mutableActive.value = null
            }
        }
        return true
    }

    /**
     * Просит модель видео (Wan) снять ролик по описанию [text]; с [source] — оживить это фото.
     * `false` — уже идёт другая генерация или описание пустое.
     */
    fun animate(chatId: String, text: String, source: ImageAttachment? = null): Boolean {
        val prompt = text.trim()
        if (prompt.isEmpty() || !reserve(chatId)) return false
        scope.launch {
            try {
                chats.addMessage(chatId, MessageRole.USER, prompt, MessageStatus.COMPLETE, modelId = null, images = listOfNotNull(source))
                chats.setTitleIfEmpty(chatId, titleFrom(prompt))
                videoReply(chatId, prompt, source)
            } finally {
                mutableActive.value = null
            }
        }
        return true
    }

    /** Повторяет последний неудачный или остановленный ответ (текстовый, рисунок или видео). */
    fun retry(chatId: String): Boolean {
        if (!reserve(chatId)) return false
        scope.launch {
            try {
                val messages = chats.messagesSnapshot(chatId)
                val last = messages.lastOrNull()
                val lastKind = last?.takeIf { it.role == MessageRole.ASSISTANT }?.modelId?.let(models::catalogModel)?.kind
                val wasDrawing = lastKind == ModelKind.IMAGE
                val wasVideo = lastKind == ModelKind.VIDEO
                if (last != null && last.role == MessageRole.ASSISTANT && last.status != MessageStatus.COMPLETE) {
                    chats.deleteMessage(last.id)
                }
                val request = messages.lastOrNull { it.role == MessageRole.USER }
                if (wasDrawing && request != null && request.content.isNotBlank()) {
                    drawReply(chatId, request.content, request.images.firstOrNull())
                } else if (wasVideo && request != null && request.content.isNotBlank()) {
                    videoReply(chatId, request.content, request.images.firstOrNull())
                } else {
                    generateReply(chatId)
                }
            } finally {
                mutableActive.value = null
            }
        }
        return true
    }

    fun stop() {
        stopRequested = true
        decision?.complete(false)
        engine.cancel()
        imageGenerator.cancel()
    }

    /** Ответ пользователя на запрос разрешения [actionId] (изменение файлов, скачивание, команда). */
    fun resolvePending(actionId: String, approved: Boolean) {
        if (mutableActive.value?.pending?.id == actionId) decision?.complete(approved)
    }

    /** На этой платформе есть программа генерации изображений. */
    val canGenerateImages: Boolean get() = imageGenerator.isSupported

    /** Скачанная модель видео для этой платформы. */
    fun videoModel(): CatalogModel? = models.models.value
        .firstOrNull { it.model.kind == ModelKind.VIDEO && it.status == ModelStatus.Downloaded }
        ?.model

    /** Скачанная модель изображений для этой платформы (каталог уже отфильтрован по платформе). */
    fun imageModel(): CatalogModel? = models.models.value
        .firstOrNull { it.model.kind == ModelKind.IMAGE && it.status == ModelStatus.Downloaded }
        ?.model

    private fun reserve(chatId: String): Boolean {
        if (!mutableActive.compareAndSet(null, ActiveGeneration(chatId = chatId, messageId = "", text = ""))) return false
        stopRequested = false
        return true
    }

    private suspend fun generateReply(chatId: String) {
        val messages = chats.messagesSnapshot(chatId)
        val modelId = models.activeModelId.value
        val reply = chats.addMessage(chatId, MessageRole.ASSISTANT, "", MessageStatus.GENERATING, modelId)
        mutableActive.value = ActiveGeneration(chatId, reply.id, "")

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
                ?.let { documents.retrieve(chatId, it, documentBudget(model)) }
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
        val history = promptHistory(messages, withImages = projectorPath != null)
            .let { if (found.isEmpty()) it else withDocuments(it, found, persona) }

        val live = StringBuilder()
        val steps = mutableListOf<AgentStep>()
        try {
            // Чат и рисование не держат модели в памяти одновременно: телефону не хватит памяти на обе.
            imageGenerator.release()
            engine.load(model, path, projectorPath)
            if (!stopRequested) {
                val safeRule = persona.safeModeRule.takeIf { settings.settings.value.safeMode && it.isNotBlank() }
                val request = GenerationRequest(
                    systemPrompt = listOfNotNull(persona.systemPrompt, safeRule).joinToString("\n\n"),
                    messages = history,
                    primer = persona.primer,
                    reminder = persona.reminder,
                )
                val toolbox = agentToolbox(chatId, reply.id)
                if (toolbox == null) {
                    engine.generate(request).collect { piece ->
                        live.append(piece)
                        mutableActive.value = ActiveGeneration(chatId, reply.id, live.toString())
                    }
                } else {
                    runAgent(chatId, reply.id, request, persona, toolbox, live, steps)
                }
            }
            val status = if (stopRequested) MessageStatus.STOPPED else MessageStatus.COMPLETE
            if (steps.isNotEmpty()) chats.attachSteps(chatId, reply.id, steps)
            chats.updateMessage(reply.id, live.toString().trim(), status, null)
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

    private suspend fun videoReply(chatId: String, prompt: String, source: ImageAttachment?) {
        val model = videoModel()
        val reply = chats.addMessage(chatId, MessageRole.ASSISTANT, "", MessageStatus.GENERATING, model?.id)
        fun progress(drawing: DrawingProgress) {
            mutableActive.value = ActiveGeneration(chatId, reply.id, "", drawing.copy(video = true))
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
            // Фото оживляет только модель, которая это умеет; размер кадра — как в настройках модели.
            val photo = source?.takeIf { videoSettings.imageToVideo }
            val video = imageGenerator.generateVideo(
                model = model,
                files = files,
                request = VideoRequest(
                    prompt = englishPrompt,
                    negativePrompt = videoSettings.negativePrompt,
                    width = videoSettings.width,
                    height = videoSettings.height,
                    frames = videoSettings.frames,
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
                    initImagePath = photo?.let { images.path(it.fileName) },
                ),
                onProgress = { event ->
                    when (event) {
                        ImageGenerationProgress.Loading -> progress(DrawingProgress(DrawingStage.LOADING))
                        is ImageGenerationProgress.Step -> progress(DrawingProgress(DrawingStage.DRAWING, event.step, event.steps))
                    }
                },
            )
            val attachment = images.saveVideo(video.avi, video.width, video.height)
            chats.attachImages(chatId, reply.id, listOf(attachment))
            chats.updateMessage(reply.id, "", MessageStatus.COMPLETE, null)
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
    private suspend fun agentToolbox(chatId: String, replyId: String): AgentToolbox? {
        val current = settings.settings.value
        val internet = web?.takeIf { current.internetEnabled }
        val folder = chats.workspaceOf(chatId)?.let { location -> workspaces?.open(location) }
        if (internet == null && folder == null) return null
        return AgentToolbox(
            workspace = folder,
            web = internet,
            commands = commands,
            autoApplyEdits = current.autoApplyEdits,
            approve = { action -> askApproval(chatId, replyId, action) },
            newId = ::newId,
        ).takeIf { it.tools.isNotEmpty() }
    }

    /**
     * Ответ с инструментами: модель пишет вызов `<tool …>`, приложение выполняет его и возвращает результат,
     * пока модель не ответит обычным текстом (не больше [MAX_AGENT_STEPS] шагов).
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
    ) {
        val workspaceName = chats.workspaceOf(chatId)?.let { location -> workspaces?.open(location)?.name }
        val context = listOfNotNull(
            workspaceName?.let { persona.agentWorkspaceNote.replace("{name}", it) },
            persona.agentInternetNote.takeIf { AgentTool.WEB_SEARCH in toolbox.tools && it.isNotBlank() },
        ).joinToString("\n")
        val tools = toolbox.tools.joinToString("\n") { tool -> "- " + (persona.toolDescriptions[tool] ?: tool.toolName) }
        val instructions = persona.agentInstructions.replace("{context}", context).replace("{tools}", tools)
        // Напоминание о личности остаётся у вопроса пользователя: так при следующих шагах кэш промпта не теряется.
        var dialog = ChatPromptFormatter.withReminder(base.messages, base.reminder)
        val parts = mutableListOf<String>()
        fun show(current: String) {
            live.setLength(0)
            live.append((parts + current).filter { it.isNotBlank() }.joinToString("\n\n"))
            mutableActive.value = ActiveGeneration(chatId, replyId, live.toString(), steps = steps.toList())
        }
        for (round in 0..MAX_AGENT_STEPS) {
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
                .collect { show(ToolCallProtocol.visibleText(raw.toString())) }
            val visible = ToolCallProtocol.visibleText(raw.toString())
            val call = ToolCallProtocol.parse(raw.toString())
            parts += visible
            show("")
            if (stopRequested || call == null || round == MAX_AGENT_STEPS) return
            val outcome = toolbox.execute(call)
            outcome.step?.let { steps += it }
            show("")
            if (stopRequested) return
            dialog = dialog +
                PromptMessage(MessageRole.ASSISTANT, ToolCallProtocol.callText(raw.toString())) +
                PromptMessage(MessageRole.USER, ToolCallProtocol.result(call.name, outcome.result))
        }
    }

    /** Ждёт, пока пользователь разрешит или отклонит действие (или остановит ответ). */
    private suspend fun askApproval(chatId: String, replyId: String, action: PendingAction): Boolean {
        if (stopRequested) return false
        val waiting = CompletableDeferred<Boolean>()
        decision = waiting
        mutableActive.update { current ->
            (current ?: ActiveGeneration(chatId, replyId, "")).copy(pending = action)
        }
        return try {
            waiting.await()
        } finally {
            decision = null
            mutableActive.update { it?.copy(pending = null) }
        }
    }

    private suspend fun drawReply(chatId: String, prompt: String, source: ImageAttachment?) {
        val model = imageModel()
        val reply = chats.addMessage(chatId, MessageRole.ASSISTANT, "", MessageStatus.GENERATING, model?.id)
        fun progress(drawing: DrawingProgress) {
            mutableActive.value = ActiveGeneration(chatId, reply.id, "", drawing)
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
            engine.load(model, path, null)
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
            engine.load(model, path, projector)
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
        private const val ORPHAN_IMAGE_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val TRANSLATION_MAX_TOKENS = 400

        /** Сколько раз подряд ассистент может вызвать инструменты в одном ответе. */
        const val MAX_AGENT_STEPS = 12
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
