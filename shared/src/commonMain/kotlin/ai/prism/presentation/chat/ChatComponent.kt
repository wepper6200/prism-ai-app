package ai.prism.presentation.chat

import ai.prism.core.componentScope
import ai.prism.domain.repository.ChatRepository
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.serializer
import kotlin.coroutines.CoroutineContext

data class ChatState(
    val chatId: String,
    val title: String?,
    val draft: String = "",
)

sealed interface ChatIntent {
    data class DraftChanged(val text: String) : ChatIntent
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

    private val title = chatRepository.chats.map { chats -> chats.firstOrNull { it.id == chatId }?.title }

    override val state: StateFlow<ChatState> = combine(title, draft) { title, draft ->
        ChatState(chatId = chatId, title = title, draft = draft)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ChatState(
            chatId = chatId,
            title = chatRepository.chats.value.firstOrNull { it.id == chatId }?.title,
            draft = draft.value,
        ),
    )

    override fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.DraftChanged -> draft.value = intent.text
            ChatIntent.OpenModels -> onOpenModels()
            ChatIntent.Back -> onBack()
        }
    }

    private companion object {
        const val KEY_DRAFT = "chat_draft"
    }
}
