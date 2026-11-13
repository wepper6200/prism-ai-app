package ai.prism.presentation.chat

import ai.prism.core.componentScope
import ai.prism.domain.agent.AgentStep
import ai.prism.domain.agent.PendingAction
import ai.prism.domain.agent.RunningTool
import ai.prism.domain.agent.WorkspaceProvider
import ai.prism.domain.documents.DocumentError
import ai.prism.domain.documents.DocumentFormat
import ai.prism.domain.documents.DocumentStatus
import ai.prism.domain.documents.MessageSource
import ai.prism.domain.inference.EngineState
import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.ImageAttachment
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.model.ModelKind
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.model.ThinkingLevel
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.repository.ChatDraft
import ai.prism.domain.repository.DocumentRepository
import ai.prism.domain.repository.DraftRepository
import ai.prism.domain.repository.ImageException
import ai.prism.domain.repository.ImageStore
import ai.prism.domain.repository.ModelRepository
import ai.prism.domain.repository.SettingsRepository
import ai.prism.service.ChatGenerationService
import ai.prism.service.DrawingProgress
import ai.prism.service.ImagePromptPolicy
import ai.prism.service.documents.DocumentService
import ai.prism.service.voice.VoicePhase
import ai.prism.service.voice.VoiceService
import ai.prism.domain.voice.VoiceError
import ai.prism.domain.voice.AudioOutput
import ai.prism.domain.voice.SpeechAudio
import ai.prism.domain.voice.VoiceException
import ai.prism.core.PcmAudio
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.CoroutineContext

data class MessageItem(
    val id: String,
    val role: MessageRole,
    val text: String,
    val status: MessageStatus,
    val error: GenerationError?,
    /** Текст ещё дописывается моделью. */
    val isStreaming: Boolean,
    /** Изображения сообщения: приложенные пользователем или нарисованные моделью. */
    val images: List<ImageAttachment> = emptyList(),
    /** Ход рисования, пока модель создаёт изображение для этого ответа. */
    val drawing: DrawingProgress? = null,
    /** Файлы диалога, на которые опирался ответ. */
    val sources: List<MessageSource> = emptyList(),
    /** Шаги ассистента с инструментами: поиск, страницы, файлы, команды. */
    val steps: List<AgentStep> = emptyList(),
    /** Действие ассистента, которое ждёт разрешения (только у ответа, который генерируется). */
    val pending: PendingAction? = null,
    /** Команда, которая выполняется сейчас, с последними строками вывода. */
    val running: RunningTool? = null,
)

/** Файл диалога на экране: [progress] — доля готовности, пока файл готовится к поиску. */
data class DocumentItem(
    val id: String,
    val name: String,
    val format: DocumentFormat,
    val status: DocumentStatus,
    val error: DocumentError?,
    val progress: Float? = null,
)

/** Можно ли прикладывать файлы для поиска по ним. */
enum class DocumentSupport {
    /** Модель поиска по файлам скачана. */
    READY,

    /** Модель поиска по файлам есть в каталоге, но не скачана. */
    NOT_DOWNLOADED,

    /** Поиска по файлам на этой платформе нет. */
    UNAVAILABLE,
}

/** Может ли приложение рисовать изображения. */
enum class DrawSupport {
    /** Модель изображений скачана — можно рисовать. */
    READY,

    /** Модель изображений есть в каталоге этой платформы, но не скачана полностью. */
    NOT_DOWNLOADED,

    /** На этой платформе генерации изображений нет. */
    UNAVAILABLE,
}

/** Что умеет активная модель с изображениями. */
enum class VisionSupport {
    /** Модуль зрения скачан — можно прикладывать фото. */
    READY,

    /** Модель понимает изображения, но модуль зрения ещё не скачан. */
    NOT_DOWNLOADED,

    /** Модель работает только с текстом (или модель не выбрана). */
    UNSUPPORTED,
}

/** Голос в диалоге: диктовка, озвучка ответов и разговор голосом. */
data class ChatVoiceState(
    /** Голос есть на этой платформе — показывать микрофон. */
    val supported: Boolean = false,
    /** Модель распознавания речи скачана. */
    val canListen: Boolean = false,
    /** Голос для озвучки скачан. */
    val canSpeak: Boolean = false,
    /** Идёт диктовка в поле ввода этого диалога. */
    val dictating: Boolean = false,
    /** Идёт разговор голосом в этом диалоге. */
    val conversation: Boolean = false,
    val phase: VoicePhase = VoicePhase.IDLE,
    /** Громкость микрофона (0–1). */
    val level: Float = 0f,
    /** Ответ, который сейчас озвучивается. */
    val speakingMessageId: String? = null,
    val error: VoiceError? = null,
    /** Что предложить скачать, если модели нет. */
    val recognizerName: String? = null,
    val recognizerBytes: Long = 0,
    val voiceName: String? = null,
    val voiceBytes: Long = 0,
)

/** Почему не удалось приложить изображение. */
enum class AttachmentError {
    /** Формат не поддерживается или файл повреждён. */
    UNREADABLE,

    /** В одном сообщении не больше [ChatState.MAX_ATTACHMENTS] изображений. */
    TOO_MANY,
}

data class ChatState(
    val chatId: String,
    val title: String? = null,
    val messages: List<MessageItem> = emptyList(),
    val draft: String = "",
    /** Изображения, приложенные к ещё не отправленному сообщению. */
    val attachments: List<ImageAttachment> = emptyList(),
    /** Выбранные изображения ещё обрабатываются (уменьшение, сохранение). */
    val isAttaching: Boolean = false,
    val attachmentError: AttachmentError? = null,
    /** Название активной модели или `null`, если модель не выбрана. */
    val modelName: String? = null,
    val modelDownloaded: Boolean = false,
    val vision: VisionSupport = VisionSupport.UNSUPPORTED,
    /** Режим «Нарисовать»: сообщение — описание картинки для модели изображений. */
    val drawMode: Boolean = false,
    val draw: DrawSupport = DrawSupport.UNAVAILABLE,
    /** Модель изображений этой платформы (для подсказки о загрузке). */
    val imageModelName: String? = null,
    val imageModelBytes: Long = 0,
    /** Скачана модель чата, которая переведёт русское описание картинки на английский. */
    val canTranslateDrawing: Boolean = true,
    /** Режим «Видео»: сообщение — описание ролика для модели видео. */
    val videoMode: Boolean = false,
    val video: DrawSupport = DrawSupport.UNAVAILABLE,
    /** Модель видео этой платформы (для подсказки о загрузке). */
    val videoModelName: String? = null,
    val videoModelBytes: Long = 0,
    /** Модель видео умеет оживлять приложенное фото. */
    val videoFromPhoto: Boolean = false,
    /** Длительности видео на выбор, секунды (у скачанной модели). */
    val videoDurations: List<Int> = emptyList(),
    /** Выбранная длительность видео, секунды. */
    val videoSeconds: Int = 0,
    /** Размер модуля зрения активной модели (для подсказки о загрузке), байт. */
    val visionModuleBytes: Long = 0,
    /** Файлы диалога: ассистент ищет в них ответы на вопросы. */
    val documents: List<DocumentItem> = emptyList(),
    val documentSupport: DocumentSupport = DocumentSupport.UNAVAILABLE,
    /** Модель поиска по файлам (для подсказки о загрузке). */
    val embeddingModelName: String? = null,
    val embeddingModelBytes: Long = 0,
    /** Папка проекта диалога: имя для показа или `null`, если папка не выбрана. */
    val workspaceName: String? = null,
    /** Ассистенту разрешён интернет (настройка). */
    val internetEnabled: Boolean = false,
    /** Работа с компьютером: `null` — платформа не умеет (переключатель не показывается). */
    val computerEnabled: Boolean? = null,
    /** Уровень размышлений и умеет ли активная модель рассуждать (переключатель показывается только тогда). */
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    val thinkingSupported: Boolean = false,
    /** Прогресс загрузки модели в память, пока ответ этого чата ждёт её. */
    val modelLoadingProgress: Float? = null,
    val isGenerating: Boolean = false,
    /** Ответ сейчас генерируется в другом чате. */
    val busyInOtherChat: Boolean = false,
    val voice: ChatVoiceState = ChatVoiceState(),
) {
    val canSend: Boolean
        get() = when {
            isGenerating || busyInOtherChat -> false
            drawMode -> draft.isNotBlank() && draw == DrawSupport.READY && !isAttaching && attachments.size <= 1 &&
                !drawNeedsTranslator
            videoMode -> draft.isNotBlank() && video == DrawSupport.READY && !isAttaching &&
                attachments.size <= (if (videoFromPhoto) 1 else 0) && !drawNeedsTranslator
            else -> (draft.isNotBlank() || attachments.isNotEmpty()) && !isAttaching
        }

    val canAttachMore: Boolean
        get() = attachments.size < (if (drawMode || videoMode) 1 else MAX_ATTACHMENTS) && !isAttaching &&
            (!videoMode || videoFromPhoto)

    /** В режиме рисования перерисовывается одно фото, а приложено несколько. */
    val tooManyPhotosToDraw: Boolean
        get() = (drawMode && attachments.size > 1) || (videoMode && attachments.size > (if (videoFromPhoto) 1 else 0))

    /** Описание картинки по-русски, а переводить его на английский нечем: генератор нарисовал бы не то. */
    val drawNeedsTranslator: Boolean
        get() = (drawMode || videoMode) && !canTranslateDrawing && ImagePromptPolicy.hasCyrillic(draft)

    companion object {
        const val MAX_ATTACHMENTS = 4
    }
}

/** Выбранный документ: имя файла и содержимое. */
class PickedDocument(val name: String, val bytes: ByteArray)

sealed interface ChatIntent {
    data class DraftChanged(val text: String) : ChatIntent

    /** Пользователь выбрал папку проекта (адрес от выбора папки платформы). */
    data class ChooseWorkspace(val location: String) : ChatIntent
    data object RemoveWorkspace : ChatIntent
    data object ToggleInternet : ChatIntent
    data object ToggleComputer : ChatIntent
    data class SetThinkingLevel(val level: ThinkingLevel) : ChatIntent

    /** Ответ на запрос разрешения действия ассистента. */
    data class ResolvePending(val actionId: String, val approved: Boolean) : ChatIntent

    /** Пользователь выбрал документы для поиска по ним. */
    data class AttachDocuments(val files: List<PickedDocument>) : ChatIntent
    data class RemoveDocument(val id: String) : ChatIntent

    /** Пользователь выбрал изображения (байты файлов). */
    data class AttachImages(val files: List<ByteArray>) : ChatIntent

    /** Выбранный файл не удалось прочитать. */
    data object AttachFailed : ChatIntent
    data class RemoveAttachment(val id: String) : ChatIntent
    data object DismissAttachmentError : ChatIntent

    /** Включает или выключает режим «Нарисовать». */
    data object ToggleDrawMode : ChatIntent

    /** Включает или выключает режим «Видео». */
    data object ToggleVideoMode : ChatIntent

    /** Длительность следующих видео, секунды. */
    data class SelectVideoSeconds(val seconds: Int) : ChatIntent
    data object Send : ChatIntent
    data object Stop : ChatIntent

    /** Микрофон: начать диктовку или закончить её (сказанное распознаётся и добавляется в поле ввода). */
    data object ToggleDictation : ChatIntent

    /** Включает или выключает разговор голосом. */
    data object ToggleConversation : ChatIntent

    /** Озвучить ответ (повторно — остановить). */
    data class Speak(val messageId: String) : ChatIntent

    /** Остановить озвучку и запись. */
    data object StopVoice : ChatIntent
    data object DismissVoiceError : ChatIntent

    /** Пользователь не разрешил доступ к микрофону. */
    data object MicrophoneDenied : ChatIntent
    data object Retry : ChatIntent
    data object OpenModels : ChatIntent
    data object Back : ChatIntent
}

interface ChatComponent {
    val state: StateFlow<ChatState>

    fun onIntent(intent: ChatIntent)

    /** Байты сохранённого изображения (JPEG) для показа или `null`. */
    suspend fun imageBytes(fileName: String): ByteArray?

    /** Проигрывает звук ролика; возвращается, когда он доиграл. */
    suspend fun playAudio(audio: PcmAudio)
}

class DefaultChatComponent(
    componentContext: ComponentContext,
    private val chatId: String,
    private val chatRepository: ChatRepository,
    modelRepository: ModelRepository,
    engine: InferenceEngine,
    private val generation: ChatGenerationService,
    private val documentService: DocumentService,
    documentRepository: DocumentRepository,
    private val workspaceProvider: WorkspaceProvider,
    private val settingsRepository: SettingsRepository,
    private val imageStore: ImageStore,
    private val voice: VoiceService,
    private val audioOutput: AudioOutput,
    private val drafts: DraftRepository = DraftRepository.None,
    /** Платформа умеет работать с компьютером (Desktop): показывать переключатель «Компьютер». */
    private val computerSupported: Boolean = false,
    mainContext: CoroutineContext,
    private val onOpenModels: () -> Unit,
    private val onBack: () -> Unit,
) : ChatComponent, ComponentContext by componentContext {

    private val scope = componentScope(mainContext)

    // Черновик и его изображения переживают пересоздание Activity (поворот экрана, смена темы),
    // а в [drafts] — уход из диалога и перезапуск приложения.
    private val saved = drafts.draft(chatId)
    private val draft = MutableStateFlow(stateKeeper.consume(KEY_DRAFT, String.serializer()) ?: saved.text)

    private data class DraftImages(
        val items: List<ImageAttachment> = emptyList(),
        val pending: Int = 0,
        val error: AttachmentError? = null,
    )

    private val drawMode = MutableStateFlow(stateKeeper.consume(KEY_DRAW_MODE, Boolean.serializer()) ?: false)
    private val videoMode = MutableStateFlow(stateKeeper.consume(KEY_VIDEO_MODE, Boolean.serializer()) ?: false)

    private val draftImages = MutableStateFlow(
        DraftImages(items = stateKeeper.consume(KEY_ATTACHMENTS, ATTACHMENTS_SERIALIZER) ?: saved.images),
    )

    init {
        // Ушли из диалога — микрофон и озвучка этого диалога выключаются.
        lifecycle.doOnDestroy {
            val current = voice.state.value
            if (current.conversationChatId == chatId || current.dictationChatId == chatId) voice.stop()
        }
        scope.launch {
            combine(draft, draftImages) { text, images -> ChatDraft(text, images.items) }
                .distinctUntilChanged()
                .collectLatest { current ->
                    // Пока пользователь печатает, черновик не пишется на диск после каждой буквы.
                    delay(DRAFT_SAVE_DELAY_MS)
                    drafts.save(chatId, current)
                }
        }
        lifecycle.doOnDestroy { drafts.save(chatId, ChatDraft(draft.value, draftImages.value.items)) }
        stateKeeper.register(KEY_DRAFT, String.serializer()) { draft.value }
        stateKeeper.register(KEY_ATTACHMENTS, ATTACHMENTS_SERIALIZER) { draftImages.value.items }
        stateKeeper.register(KEY_DRAW_MODE, Boolean.serializer()) { drawMode.value }
        stateKeeper.register(KEY_VIDEO_MODE, Boolean.serializer()) { videoMode.value }
    }

    private data class ConversationPart(
        val title: String?,
        val messages: List<MessageItem>,
        val isGenerating: Boolean,
        val busyElsewhere: Boolean,
    )

    private data class ModelPart(
        val name: String?,
        val downloaded: Boolean,
        val vision: VisionSupport,
        val visionBytes: Long,
        val loadingProgress: Float?,
        val draw: DrawSupport,
        val imageModelName: String?,
        val imageModelBytes: Long,
        val canTranslate: Boolean,
        val documentSupport: DocumentSupport,
        val embeddingModelName: String?,
        val embeddingModelBytes: Long,
        val video: DrawSupport,
        val videoModelName: String?,
        val videoModelBytes: Long,
        val videoFromPhoto: Boolean,
        val videoDurations: List<Int>,
        val videoSeconds: Int,
        val thinkingSupported: Boolean,
    )

    private val conversation = combine(
        chatRepository.chats.map { chats -> chats.firstOrNull { it.id == chatId }?.title },
        chatRepository.messages(chatId),
        generation.active,
    ) { title, messages, active ->
        val activeHere = active?.takeIf { it.chatId == chatId }
        ConversationPart(
            title = title,
            messages = messages.map { message ->
                val streaming = activeHere?.takeIf { it.messageId == message.id }
                MessageItem(
                    id = message.id,
                    role = message.role,
                    text = streaming?.text ?: message.content,
                    status = message.status,
                    error = message.error,
                    isStreaming = streaming != null,
                    images = message.images,
                    drawing = streaming?.drawing,
                    sources = message.sources,
                    steps = streaming?.steps ?: message.steps,
                    pending = streaming?.pending,
                    running = streaming?.running,
                )
            },
            isGenerating = active?.chatId == chatId,
            busyElsewhere = active != null && active.chatId != chatId,
        )
    }

    private val modelPart = combine(
        modelRepository.activeModelId,
        modelRepository.models,
        engine.state,
        settingsRepository.settings,
    ) { activeId, entries, engineState, _ ->
        val entry = entries.firstOrNull { it.model.id == activeId }
        val imageEntry = entries.firstOrNull { it.model.kind == ModelKind.IMAGE }
        val embeddingEntry = entries.firstOrNull { it.model.kind == ModelKind.EMBEDDING }
        // Скачанная (выбранная в настройках) модель видео, иначе первая из каталога — для подсказки о загрузке.
        val selectedVideo = generation.videoModel()
        val videoEntry = entries.firstOrNull { it.model.id == selectedVideo?.id } ?: entries.firstOrNull { it.model.kind == ModelKind.VIDEO }
        ModelPart(
            thinkingSupported = entry?.model?.chatFormat?.let(ThinkingLevel::supports) == true,
            name = entry?.model?.name,
            downloaded = entry?.chatReady == true,
            vision = when {
                entry == null || !entry.model.supportsImages -> VisionSupport.UNSUPPORTED
                entry.visionReady -> VisionSupport.READY
                else -> VisionSupport.NOT_DOWNLOADED
            },
            visionBytes = entry?.model?.vision?.sizeBytes ?: 0L,
            loadingProgress = (engineState as? EngineState.Loading)?.takeIf { it.modelId == activeId }?.progress,
            draw = when {
                imageEntry == null || !generation.canGenerateImages -> DrawSupport.UNAVAILABLE
                imageEntry.status == ModelStatus.Downloaded -> DrawSupport.READY
                else -> DrawSupport.NOT_DOWNLOADED
            },
            imageModelName = imageEntry?.model?.name,
            imageModelBytes = imageEntry?.model?.totalSizeBytes ?: 0L,
            canTranslate = entries.any { it.model.kind == ModelKind.CHAT && it.chatReady },
            documentSupport = when {
                embeddingEntry == null -> DocumentSupport.UNAVAILABLE
                embeddingEntry.status == ModelStatus.Downloaded -> DocumentSupport.READY
                else -> DocumentSupport.NOT_DOWNLOADED
            },
            embeddingModelName = embeddingEntry?.model?.name,
            embeddingModelBytes = embeddingEntry?.model?.totalSizeBytes ?: 0L,
            video = when {
                videoEntry == null || !generation.canGenerateImages -> DrawSupport.UNAVAILABLE
                videoEntry.status == ModelStatus.Downloaded -> DrawSupport.READY
                else -> DrawSupport.NOT_DOWNLOADED
            },
            videoModelName = videoEntry?.model?.name,
            videoModelBytes = videoEntry?.model?.totalSizeBytes ?: 0L,
            videoFromPhoto = videoEntry?.model?.video?.imageToVideo == true,
            videoDurations = videoEntry?.model?.video?.availableDurations.orEmpty(),
            videoSeconds = videoEntry?.model?.let(generation::videoSeconds) ?: 0,
        )
    }

    private val documentsPart = combine(
        documentRepository.documents(chatId),
        documentService.progress,
    ) { documents, progress ->
        documents.map { document ->
            DocumentItem(
                id = document.id,
                name = document.name,
                format = document.format,
                status = document.status,
                error = document.error,
                progress = progress[document.id],
            )
        }
    }

    private data class AgentPart(
        val workspaceName: String?,
        val internetEnabled: Boolean,
        val thinkingLevel: ThinkingLevel,
        val computerEnabled: Boolean,
    )

    private val agentPart = combine(
        chatRepository.workspace(chatId).map { location ->
            location?.let { workspaceProvider.open(it)?.name ?: it.substringAfterLast('/').substringAfterLast("%2F") }
        },
        settingsRepository.settings.map { Triple(it.internetEnabled, it.thinkingLevel, it.computerEnabled) },
    ) { name, (internet, thinking, computer) -> AgentPart(name, internet, thinking, computer) }

    private val voicePart = combine(voice.state, voice.availability) { current, available ->
        val conversation = current.conversationChatId == chatId
        val dictating = current.dictationChatId == chatId
        ChatVoiceState(
            supported = available.supported,
            canListen = available.canListen,
            canSpeak = available.canSpeak,
            dictating = dictating,
            conversation = conversation,
            phase = if (conversation || dictating || current.speakingMessageId != null) current.phase else VoicePhase.IDLE,
            level = if (conversation || dictating) current.level else 0f,
            speakingMessageId = current.speakingMessageId,
            error = current.error,
            recognizerName = available.suggestedRecognizer?.name,
            recognizerBytes = available.suggestedRecognizer?.totalSizeBytes ?: 0L,
            voiceName = available.suggestedVoice?.name,
            voiceBytes = available.suggestedVoice?.totalSizeBytes ?: 0L,
        )
    }

    private data class ExtraPart(
        val drawMode: Boolean,
        val videoMode: Boolean,
        val documents: List<DocumentItem>,
        val agent: AgentPart,
        val voice: ChatVoiceState,
    )

    override val state: StateFlow<ChatState> = combine(
        conversation,
        modelPart,
        draft,
        draftImages,
        combine(drawMode, videoMode, documentsPart, agentPart, voicePart) { drawing, video, documents, agent, voiceState ->
            ExtraPart(drawing, video, documents, agent, voiceState)
        },
    ) { chat, model, text, images, extra ->
        val drawing = extra.drawMode
        val documents = extra.documents
        ChatState(
            chatId = chatId,
            title = chat.title,
            messages = chat.messages,
            draft = text,
            attachments = images.items,
            isAttaching = images.pending > 0,
            attachmentError = images.error,
            modelName = model.name,
            modelDownloaded = model.downloaded,
            vision = model.vision,
            visionModuleBytes = model.visionBytes,
            drawMode = drawing && model.draw != DrawSupport.UNAVAILABLE,
            videoMode = extra.videoMode && model.video != DrawSupport.UNAVAILABLE,
            video = model.video,
            videoModelName = model.videoModelName,
            videoModelBytes = model.videoModelBytes,
            videoFromPhoto = model.videoFromPhoto,
            videoDurations = model.videoDurations,
            videoSeconds = model.videoSeconds,
            draw = model.draw,
            imageModelName = model.imageModelName,
            imageModelBytes = model.imageModelBytes,
            canTranslateDrawing = model.canTranslate,
            documents = documents,
            documentSupport = model.documentSupport,
            embeddingModelName = model.embeddingModelName,
            embeddingModelBytes = model.embeddingModelBytes,
            workspaceName = extra.agent.workspaceName,
            internetEnabled = extra.agent.internetEnabled,
            computerEnabled = extra.agent.computerEnabled.takeIf { computerSupported },
            thinkingLevel = extra.agent.thinkingLevel,
            thinkingSupported = model.thinkingSupported,
            modelLoadingProgress = model.loadingProgress?.takeIf { chat.isGenerating },
            isGenerating = chat.isGenerating,
            busyInOtherChat = chat.busyElsewhere,
            voice = extra.voice,
        )
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        ChatState(chatId = chatId, draft = draft.value, attachments = draftImages.value.items),
    )

    override fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.DraftChanged -> draft.value = intent.text
            is ChatIntent.AttachDocuments -> intent.files.forEach { file ->
                scope.launch { documentService.add(chatId, file.name, file.bytes) }
            }
            is ChatIntent.RemoveDocument -> scope.launch { documentService.remove(intent.id) }
            is ChatIntent.ChooseWorkspace -> scope.launch { chatRepository.setWorkspace(chatId, intent.location) }
            ChatIntent.RemoveWorkspace -> scope.launch { chatRepository.setWorkspace(chatId, null) }
            ChatIntent.ToggleInternet -> settingsRepository.setInternetEnabled(!settingsRepository.settings.value.internetEnabled)
            ChatIntent.ToggleComputer -> settingsRepository.setComputerEnabled(!settingsRepository.settings.value.computerEnabled)
            is ChatIntent.SetThinkingLevel -> settingsRepository.setThinkingLevel(intent.level)
            is ChatIntent.ResolvePending -> generation.resolvePending(intent.actionId, intent.approved)
            is ChatIntent.AttachImages -> attach(intent.files)
            ChatIntent.AttachFailed -> draftImages.update { it.copy(error = AttachmentError.UNREADABLE) }
            is ChatIntent.RemoveAttachment -> remove(intent.id)
            ChatIntent.DismissAttachmentError -> draftImages.update { it.copy(error = null) }
            ChatIntent.ToggleDrawMode -> {
                drawMode.update { !it }
                if (drawMode.value) videoMode.value = false
            }
            ChatIntent.ToggleVideoMode -> {
                videoMode.update { !it }
                if (videoMode.value) drawMode.value = false
            }
            is ChatIntent.SelectVideoSeconds -> settingsRepository.setVideoSeconds(intent.seconds)
            ChatIntent.Send -> send()
            ChatIntent.Stop -> generation.stop()
            ChatIntent.ToggleDictation -> toggleDictation()
            ChatIntent.ToggleConversation ->
                if (voice.state.value.conversationChatId == chatId) voice.stop() else voice.startConversation(chatId)
            is ChatIntent.Speak -> state.value.messages.firstOrNull { it.id == intent.messageId }?.let { message ->
                voice.speak(message.id, message.text)
            }
            ChatIntent.StopVoice -> voice.stop()
            ChatIntent.DismissVoiceError -> voice.dismissError()
            ChatIntent.MicrophoneDenied -> voice.reportError(VoiceError.PERMISSION_DENIED)
            ChatIntent.Retry -> generation.retry(chatId)
            ChatIntent.OpenModels -> onOpenModels()
            ChatIntent.Back -> onBack()
        }
    }

    override suspend fun imageBytes(fileName: String): ByteArray? = imageStore.read(fileName)

    override suspend fun playAudio(audio: PcmAudio) {
        try {
            audioOutput.play(SpeechAudio(audio.monoSamples(), audio.sampleRate))
        } catch (e: VoiceException) {
            // Нет устройства вывода звука — ролик играет без звука.
        }
    }

    private fun send() {
        val current = state.value
        if (!current.canSend) return
        if (current.drawMode) {
            if (generation.draw(chatId, draft.value, current.attachments.firstOrNull())) {
                draft.value = ""
                draftImages.update { it.copy(items = emptyList(), error = null) }
            }
            return
        }
        if (current.videoMode) {
            if (generation.animate(chatId, draft.value, current.attachments.firstOrNull())) {
                draft.value = ""
                draftImages.update { it.copy(items = emptyList(), error = null) }
            }
            return
        }
        val images = draftImages.value.items
        if (generation.send(chatId, draft.value, images)) {
            draft.value = ""
            // Файлы теперь принадлежат сообщению — удалять их нельзя.
            draftImages.update { it.copy(items = emptyList(), error = null) }
        }
    }

    /** Первое нажатие — слушать, второе — закончить запись; распознанный текст дописывается в поле ввода. */
    private fun toggleDictation() {
        val current = voice.state.value
        if (current.dictationChatId == chatId) {
            if (current.phase == VoicePhase.LISTENING) voice.stopListening() else voice.stop()
            return
        }
        if (current.conversationChatId != null) return
        voice.stop()
        voice.dictate(chatId) { text ->
            draft.update { old -> if (old.isBlank()) text else old.trimEnd() + " " + text }
        }
    }

    private fun attach(files: List<ByteArray>) {
        if (files.isEmpty()) return
        val current = draftImages.value
        val room = ChatState.MAX_ATTACHMENTS - current.items.size - current.pending
        val accepted = files.take(room.coerceAtLeast(0))
        draftImages.update {
            it.copy(
                pending = it.pending + accepted.size,
                error = if (accepted.size < files.size) AttachmentError.TOO_MANY else null,
            )
        }
        accepted.forEach { bytes ->
            scope.launch {
                val saved = try {
                    imageStore.save(bytes)
                } catch (e: ImageException) {
                    null
                }
                draftImages.update {
                    it.copy(
                        items = if (saved != null) it.items + saved else it.items,
                        pending = it.pending - 1,
                        error = if (saved == null) AttachmentError.UNREADABLE else it.error,
                    )
                }
            }
        }
    }

    private fun remove(id: String) {
        val removed = draftImages.value.items.firstOrNull { it.id == id } ?: return
        draftImages.update { it.copy(items = it.items - removed) }
        scope.launch { imageStore.delete(listOf(removed.fileName)) }
    }

    private companion object {
        const val KEY_DRAFT = "chat_draft"
        const val DRAFT_SAVE_DELAY_MS = 500L
        const val KEY_ATTACHMENTS = "chat_attachments"
        const val KEY_DRAW_MODE = "chat_draw_mode"
        const val KEY_VIDEO_MODE = "chat_video_mode"
        val ATTACHMENTS_SERIALIZER = ListSerializer(ImageAttachment.serializer())
    }
}
