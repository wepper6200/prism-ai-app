package ai.prism.domain.repository

import ai.prism.domain.model.ChatSummary
import kotlinx.coroutines.flow.StateFlow

interface ChatRepository {
    /** Диалоги, новые — первыми. */
    val chats: StateFlow<List<ChatSummary>>

    fun createChat(): ChatSummary
}
