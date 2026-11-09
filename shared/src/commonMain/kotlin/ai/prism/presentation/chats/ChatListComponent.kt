package ai.prism.presentation.chats

import ai.prism.core.componentScope
import ai.prism.domain.model.ChatSummary
import ai.prism.domain.repository.ChatRepository
import ai.prism.service.ChatGenerationService
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

data class ChatListState(
    val chats: List<ChatSummary> = emptyList(),
    /** Диалог, в котором сейчас генерируется ответ. */
    val generatingChatId: String? = null,
)

sealed interface ChatListIntent {
    data object NewChat : ChatListIntent
    data class OpenChat(val chatId: String) : ChatListIntent
    data class DeleteChat(val chatId: String) : ChatListIntent
    data object OpenModels : ChatListIntent
    data object OpenSettings : ChatListIntent
}

interface ChatListComponent {
    val state: StateFlow<ChatListState>

    fun onIntent(intent: ChatListIntent)
}

class DefaultChatListComponent(
    componentContext: ComponentContext,
    private val chatRepository: ChatRepository,
    private val generation: ChatGenerationService,
    mainContext: CoroutineContext,
    private val onOpenChat: (chatId: String) -> Unit,
    private val onChatDeleted: (chatId: String) -> Unit,
    private val onOpenModels: () -> Unit,
    private val onOpenSettings: () -> Unit,
) : ChatListComponent, ComponentContext by componentContext {

    private val scope = componentScope(mainContext)

    override val state: StateFlow<ChatListState> =
        combine(chatRepository.chats, generation.active) { chats, active ->
            ChatListState(chats = chats, generatingChatId = active?.chatId)
        }.stateIn(scope, SharingStarted.Eagerly, ChatListState())

    override fun onIntent(intent: ChatListIntent) {
        when (intent) {
            ChatListIntent.NewChat -> scope.launch {
                // Пустой диалог без сообщений переиспользуем, чтобы не плодить «Новые чаты».
                val empty = chatRepository.chats.first().firstOrNull { it.title == null }
                onOpenChat((empty ?: chatRepository.createChat()).id)
            }
            is ChatListIntent.OpenChat -> onOpenChat(intent.chatId)
            is ChatListIntent.DeleteChat -> scope.launch {
                if (generation.active.value?.chatId == intent.chatId) generation.stop()
                chatRepository.deleteChat(intent.chatId)
                onChatDeleted(intent.chatId)
            }
            ChatListIntent.OpenModels -> onOpenModels()
            ChatListIntent.OpenSettings -> onOpenSettings()
        }
    }
}
