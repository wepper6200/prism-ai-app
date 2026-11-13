package ai.prism.data.drafts

import ai.prism.data.settings.KeyValueStore
import ai.prism.domain.repository.ChatDraft
import ai.prism.domain.repository.DraftRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Черновики всех диалогов — одна строка JSON в [KeyValueStore]. Повреждённое значение — черновиков нет. */
class KeyValueDraftRepository(private val store: KeyValueStore) : DraftRepository {

    private val drafts = MutableStateFlow(load())

    override fun draft(chatId: String): ChatDraft = drafts.value[chatId] ?: ChatDraft()

    override fun save(chatId: String, draft: ChatDraft) =
        change { all -> if (draft.isEmpty) all - chatId else all + (chatId to draft) }

    override fun referencedImageFiles(): Set<String> =
        drafts.value.values.flatMapTo(mutableSetOf()) { draft -> draft.images.map { it.fileName } }

    override fun retainChats(chatIds: Set<String>) = change { all -> all.filterKeys { it in chatIds } }

    private fun change(transform: (Map<String, ChatDraft>) -> Map<String, ChatDraft>) {
        val before = drafts.value
        val after = drafts.updateAndGet(transform)
        if (after != before) store.putString(KEY, json.encodeToString(serializer, after))
    }

    private fun load(): Map<String, ChatDraft> {
        val raw = store.getString(KEY)?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (e: SerializationException) {
            emptyMap()
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }

    private companion object {
        const val KEY = "chat_drafts"
        val json = Json { ignoreUnknownKeys = true }
        val serializer = MapSerializer(String.serializer(), ChatDraft.serializer())
    }
}
