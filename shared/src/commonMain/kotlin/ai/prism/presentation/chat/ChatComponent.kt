package ai.prism.presentation.chat

import ai.prism.core.componentScope
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
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.repository.DocumentRepository
import ai.prism.domain.repository.ImageException
import ai.prism.domain.repository.ImageStore
import ai.prism.domain.repository.ModelRepository
import ai.prism.service.ChatGenerationService
import ai.prism.service.DrawingProgress
import ai.prism.service.ImagePromptPolicy
import ai.prism.service.documents.DocumentService
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    /** Размер модуля зрения активной модели (для подсказки о загрузке), байт. */
    val visionModuleBytes: Long = 0,
    /** Файлы диалога: ассистент ищет в них ответы на вопросы. */
    val documents: List<DocumentItem> = emptyList(),
    val documentSupport: DocumentSupport = DocumentSupport.UNAVAILABLE,
    /** Модель поиска по файлам (для подсказки о загрузке). */
    val embeddingModelName: String? = null,
    val embeddingModelBytes: Long = 0,
    /** Прогресс загрузки модели в память, пока ответ этого чата ждёт её. */
    val modelLoadingProgress: Float? = null,
    val isGenerating: Boolean = false,
    /** Ответ сейчас генерируется в другом чате. */
    val busyInOtherChat: Boolean = false,
) {
    val canSend: Boolean
        get() = when {
            isGenerating || busyInOtherChat -> false
            drawMode -> draft.isNotBlank() && draw == DrawSupport.READY && !isAttaching && attachments.size <= 1 &&
                !drawNeedsTranslator
            else -> (draft.isNotBlank() || attachments.isNotEmpty()) && !isAttaching
        }

    val canAttachMore: Boolean
        get() = attachments.size < (if (drawMode) 1 else MAX_ATTACHMENTS) && !isAttaching

    /** В режиме рисования перерисовывается одно фото, а приложено несколько. */
    val tooManyPhotosToDraw: Boolean get() = drawMode && attachments.size > 1

    /** Описание картинки по-русски, а переводить его на английский нечем: генератор нарисовал бы не то. */
    val drawNeedsTranslator: Boolean
        get() = drawMode && !canTranslateDrawing && ImagePromptPolicy.hasCyrillic(draft)

    companion object {
        const val MAX_ATTACHMENTS = 4
    }
}

/** Выбранный документ: имя файла и содержимое. */
class PickedDocument(val name: String, val bytes: ByteArray)

sealed interface ChatIntent {
    data class DraftChanged(val text: String) : ChatIntent

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
    data object Send : ChatIntent
    data object Stop : ChatIntent
    data object Retry : ChatIntent
    data object OpenModels : ChatIntent
    data object Back : ChatIntent
}

interface ChatComponent {
    val state: StateFlow<ChatState>

    fun onIntent(intent: ChatIntent)

    /** Байты сохранённого изображения (JPEG) для показа или `null`. */
    suspend fun imageBytes(fileName: String): ByteArray?
}

class DefaultChatComponent(
    componentContext: ComponentContext,
    private val chatId: String,
    chatRepository: ChatRepository,
    modelRepository: ModelRepository,
    engine: InferenceEngine,
    private val generation: ChatGenerationService,
    private val documentService: DocumentService,
    documentRepository: DocumentRepository,
    private val imageStore: ImageStore,
    mainContext: CoroutineContext,
    private val onOpenModels: () -> Unit,
    private val onBack: () -> Unit,
) : ChatComponent, ComponentContext by componentContext {

    private val scope = componentScope(mainContext)

    // Черновик и его изображения переживают пересоздание Activity (поворот экрана, смена темы).
    private val draft = MutableStateFlow(stateKeeper.consume(KEY_DRAFT, String.serializer()) ?: "")

    private data class DraftImages(
        val items: List<ImageAttachment> = emptyList(),
        val pending: Int = 0,
        val error: AttachmentError? = null,
    )

    private val drawMode = MutableStateFlow(stateKeeper.consume(KEY_DRAW_MODE, Boolean.serializer()) ?: false)

    private val draftImages = MutableStateFlow(
        DraftImages(items = stateKeeper.consume(KEY_ATTACHMENTS, ATTACHMENTS_SERIALIZER).orEmpty()),
    )

    init {
        stateKeeper.register(KEY_DRAFT, String.serializer()) { draft.value }
        stateKeeper.register(KEY_ATTACHMENTS, ATTACHMENTS_SERIALIZER) { draftImages.value.items }
        stateKeeper.register(KEY_DRAW_MODE, Boolean.serializer()) { drawMode.value }
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
    ) { activeId, entries, engineState ->
        val entry = entries.firstOrNull { it.model.id == activeId }
        val imageEntry = entries.firstOrNull { it.model.kind == ModelKind.IMAGE }
        val embeddingEntry = entries.firstOrNull { it.model.kind == ModelKind.EMBEDDING }
        ModelPart(
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

    override val state: StateFlow<ChatState> = combine(
        conversation,
        modelPart,
        draft,
        draftImages,
        combine(drawMode, documentsPart) { drawing, documents -> drawing to documents },
    ) { chat, model, text, images, (drawing, documents) ->
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
            draw = model.draw,
            imageModelName = model.imageModelName,
            imageModelBytes = model.imageModelBytes,
            canTranslateDrawing = model.canTranslate,
            documents = documents,
            documentSupport = model.documentSupport,
            embeddingModelName = model.embeddingModelName,
            embeddingModelBytes = model.embeddingModelBytes,
            modelLoadingProgress = model.loadingProgress?.takeIf { chat.isGenerating },
            isGenerating = chat.isGenerating,
            busyInOtherChat = chat.busyElsewhere,
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
            is ChatIntent.AttachImages -> attach(intent.files)
            ChatIntent.AttachFailed -> draftImages.update { it.copy(error = AttachmentError.UNREADABLE) }
            is ChatIntent.RemoveAttachment -> remove(intent.id)
            ChatIntent.DismissAttachmentError -> draftImages.update { it.copy(error = null) }
            ChatIntent.ToggleDrawMode -> drawMode.update { !it }
            ChatIntent.Send -> send()
            ChatIntent.Stop -> generation.stop()
            ChatIntent.Retry -> generation.retry(chatId)
            ChatIntent.OpenModels -> onOpenModels()
            ChatIntent.Back -> onBack()
        }
    }

    override suspend fun imageBytes(fileName: String): ByteArray? = imageStore.read(fileName)

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
        val images = draftImages.value.items
        if (generation.send(chatId, draft.value, images)) {
            draft.value = ""
            // Файлы теперь принадлежат сообщению — удалять их нельзя.
            draftImages.update { it.copy(items = emptyList(), error = null) }
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
        const val KEY_ATTACHMENTS = "chat_attachments"
        const val KEY_DRAW_MODE = "chat_draw_mode"
        val ATTACHMENTS_SERIALIZER = ListSerializer(ImageAttachment.serializer())
    }
}
