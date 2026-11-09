package ai.prism.presentation.chats

import ai.prism.core.componentScope
import ai.prism.domain.model.ChatSummary
import ai.prism.domain.repository.ChatRepository
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.coroutines.CoroutineContext

data class ChatListState(
    val chats: List<ChatSummary> = emptyList(),
)

sealed interface ChatListIntent {
    data object NewChat : ChatListIntent
    data class OpenChat(val chatId: String) : ChatListIntent
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
    mainContext: CoroutineContext,
    private val onOpenChat: (chatId: String) -> Unit,
    private val onOpenModels: () -> Unit,
    private val onOpenSettings: () -> Unit,
) : ChatListComponent, ComponentContext by componentContext {

    private val scope = componentScope(mainContext)

    override val state: StateFlow<ChatListState> = chatRepository.chats
        .map { ChatListState(chats = it) }
        .stateIn(scope, SharingStarted.Eagerly, ChatListState(chats = chatRepository.chats.value))

    override fun onIntent(intent: ChatListIntent) {
        when (intent) {
            ChatListIntent.NewChat -> onOpenChat(chatRepository.createChat().id)
            is ChatListIntent.OpenChat -> onOpenChat(intent.chatId)
            ChatListIntent.OpenModels -> onOpenModels()
            ChatListIntent.OpenSettings -> onOpenSettings()
        }
    }
}
