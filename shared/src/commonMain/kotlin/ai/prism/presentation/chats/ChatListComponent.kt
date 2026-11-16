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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.CoroutineContext

data class ChatListState(
    val chats: List<ChatSummary> = emptyList(),
    /** Диалог, в котором сейчас генерируется ответ. */
    val generatingChatId: String? = null,
    val pendingDeleteId: String? = null,
    val generatingChatIds: Set<String> = emptySet(),
)

sealed interface ChatListIntent {
    data object NewChat : ChatListIntent
    data class OpenChat(val chatId: String) : ChatListIntent
    data class DeleteChat(val chatId: String) : ChatListIntent
    data class RenameChat(val chatId: String, val title: String) : ChatListIntent
    data class PinChat(val chatId: String, val pinned: Boolean) : ChatListIntent
    data object UndoDelete : ChatListIntent
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

    private val pendingDelete = MutableStateFlow<String?>(null)
    private var deletion: Job? = null
    private val deletionLock = Mutex()

    init {
        // Android may recreate this component while the app-wide generation service survives.
        scope.launch { deletionLock.withLock { chatRepository.restoreTrashedChats() } }
    }

    override val state: StateFlow<ChatListState> =
        combine(chatRepository.chats, generation.activeGenerations, pendingDelete) { chats, active, pending ->
            ChatListState(chats = chats, generatingChatId = active.values.firstOrNull()?.chatId, pendingDeleteId = pending, generatingChatIds = active.values.mapTo(mutableSetOf()) { it.chatId })
        }.stateIn(scope, SharingStarted.Eagerly, ChatListState())

    override fun onIntent(intent: ChatListIntent) {
        when (intent) {
            ChatListIntent.NewChat -> scope.launch {
                // Пустой диалог без сообщений переиспользуем, чтобы не плодить «Новые чаты».
                val empty = chatRepository.chats.first().firstOrNull { it.title == null }
                onOpenChat((empty ?: chatRepository.createChat()).id)
            }
            is ChatListIntent.OpenChat -> onOpenChat(intent.chatId)
            is ChatListIntent.DeleteChat -> scope.launch { deletionLock.withLock {
                if (generation.activeGenerations.value.values.any { it.chatId == intent.chatId }) generation.stop(intent.chatId)
                deletion?.cancel()
                pendingDelete.value?.let { chatRepository.deleteChat(it) }
                chatRepository.setTrashed(intent.chatId, true)
                pendingDelete.value = intent.chatId
                onChatDeleted(intent.chatId)
                deletion = scope.launch {
                    delay(6000)
                    deletionLock.withLock {
                        if (pendingDelete.value == intent.chatId) {
                            chatRepository.deleteChat(intent.chatId)
                            pendingDelete.value = null
                        }
                    }
                }
            } }
            is ChatListIntent.RenameChat -> scope.launch { chatRepository.setTitle(intent.chatId, intent.title) }
            is ChatListIntent.PinChat -> scope.launch { chatRepository.setPinned(intent.chatId, intent.pinned) }
            ChatListIntent.UndoDelete -> scope.launch { deletionLock.withLock {
                deletion?.cancel()
                pendingDelete.value?.let { chatRepository.setTrashed(it, false) }
                pendingDelete.value = null
            } }
            ChatListIntent.OpenModels -> onOpenModels()
            ChatListIntent.OpenSettings -> onOpenSettings()
        }
    }
}
