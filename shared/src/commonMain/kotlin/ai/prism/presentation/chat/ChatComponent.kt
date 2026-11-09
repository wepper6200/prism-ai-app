package ai.prism.presentation.chat

import ai.prism.core.componentScope
import ai.prism.domain.inference.EngineState
import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.repository.ModelRepository
import ai.prism.service.ChatGenerationService
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
)

data class ChatState(
    val chatId: String,
    val title: String? = null,
    val messages: List<MessageItem> = emptyList(),
    val draft: String = "",
    /** Название активной модели или `null`, если модель не выбрана. */
    val modelName: String? = null,
    val modelDownloaded: Boolean = false,
    /** Прогресс загрузки модели в память, пока ответ этого чата ждёт её. */
    val modelLoadingProgress: Float? = null,
    val isGenerating: Boolean = false,
    /** Ответ сейчас генерируется в другом чате. */
    val busyInOtherChat: Boolean = false,
) {
    val canSend: Boolean get() = draft.isNotBlank() && !isGenerating && !busyInOtherChat
}

sealed interface ChatIntent {
    data class DraftChanged(val text: String) : ChatIntent
    data object Send : ChatIntent
    data object Stop : ChatIntent
    data object Retry : ChatIntent
    data object OpenModels : ChatIntent
    data object Back : ChatIntent
}

interface ChatComponent {
    val state: StateFlow<ChatState>

    fun onIntent(intent: ChatIntent)
}

class DefaultChatComponent(
    componentContext: ComponentContext,
    private val chatId: String,
    chatRepository: ChatRepository,
    modelRepository: ModelRepository,
    engine: InferenceEngine,
    private val generation: ChatGenerationService,
    mainContext: CoroutineContext,
    private val onOpenModels: () -> Unit,
    private val onBack: () -> Unit,
) : ChatComponent, ComponentContext by componentContext {

    private val scope = componentScope(mainContext)

    // Черновик переживает пересоздание Activity (поворот экрана, смена темы).
    private val draft = MutableStateFlow(stateKeeper.consume(KEY_DRAFT, String.serializer()) ?: "")

    init {
        stateKeeper.register(KEY_DRAFT, String.serializer()) { draft.value }
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
        val loadingProgress: Float?,
    )

    private val conversation = combine(
        chatRepository.chats.map { chats -> chats.firstOrNull { it.id == chatId }?.title },
        chatRepository.messages(chatId),
        generation.active,
    ) { title, messages, active ->
        val streamingId = active?.takeIf { it.chatId == chatId }?.messageId
        ConversationPart(
            title = title,
            messages = messages.map { message ->
                val streaming = message.id == streamingId
                MessageItem(
                    id = message.id,
                    role = message.role,
                    text = if (streaming) active?.text.orEmpty() else message.content,
                    status = message.status,
                    error = message.error,
                    isStreaming = streaming,
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
        ModelPart(
            name = entry?.model?.name,
            downloaded = entry?.status == ModelStatus.Downloaded,
            loadingProgress = (engineState as? EngineState.Loading)?.takeIf { it.modelId == activeId }?.progress,
        )
    }

    override val state: StateFlow<ChatState> = combine(conversation, modelPart, draft) { chat, model, text ->
        ChatState(
            chatId = chatId,
            title = chat.title,
            messages = chat.messages,
            draft = text,
            modelName = model.name,
            modelDownloaded = model.downloaded,
            modelLoadingProgress = model.loadingProgress?.takeIf { chat.isGenerating },
            isGenerating = chat.isGenerating,
            busyInOtherChat = chat.busyElsewhere,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ChatState(chatId = chatId, draft = draft.value))

    override fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.DraftChanged -> draft.value = intent.text
            ChatIntent.Send -> if (state.value.canSend && generation.send(chatId, draft.value)) {
                draft.value = ""
            }
            ChatIntent.Stop -> generation.stop()
            ChatIntent.Retry -> generation.retry(chatId)
            ChatIntent.OpenModels -> onOpenModels()
            ChatIntent.Back -> onBack()
        }
    }

    private companion object {
        const val KEY_DRAFT = "chat_draft"
    }
}
