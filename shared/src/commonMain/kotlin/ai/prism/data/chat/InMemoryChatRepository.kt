package ai.prism.data.chat

import ai.prism.domain.model.ChatSummary
import ai.prism.domain.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Список диалогов в памяти процесса. Используется на этапе каркаса;
 * хранение в SQLDelight заменит его вместе с историей сообщений.
 * Вызывается только из главного потока.
 */
class InMemoryChatRepository : ChatRepository {

    private val state = MutableStateFlow<List<ChatSummary>>(emptyList())
    private var nextId = 1L

    override val chats: StateFlow<List<ChatSummary>> = state.asStateFlow()

    override fun createChat(): ChatSummary {
        val chat = ChatSummary(id = "chat-${nextId++}", title = null)
        state.update { listOf(chat) + it }
        return chat
    }
}
