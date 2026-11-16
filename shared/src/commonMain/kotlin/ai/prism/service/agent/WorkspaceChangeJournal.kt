package ai.prism.service.agent

import ai.prism.core.currentTimeMillis
import ai.prism.core.newId
import ai.prism.data.db.PrismDatabase
import ai.prism.domain.agent.WorkspaceException
import ai.prism.domain.agent.WorkspaceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Local, bounded undo journal. Records are durable BEFORE the file is changed. */
class WorkspaceChangeJournal(private val database: PrismDatabase? = null, private val ioContext: kotlin.coroutines.CoroutineContext = Dispatchers.IO) {
    private data class Change(val id: String, val chatId: String, val replyId: String, val location: String,
        val path: String, val before: ByteArray?, val after: ByteArray, val at: Long)
    private val lock = Mutex()
    private val memory = mutableListOf<Change>()
    private val mutableReplies = MutableStateFlow<Set<String>>(emptySet())
    val replies = mutableReplies.asStateFlow()
    init { refresh() }
    private fun changes(): List<Change> = database?.workspaceChangeQueries?.selectChanges()?.executeAsList()?.map {
        Change(it.id, it.chat_id, it.message_id, it.location, it.path, it.before_bytes, it.after_bytes, it.created_at)
    } ?: memory.toList()
    private fun refresh() { mutableReplies.value = database?.workspaceChangeQueries?.selectReplyIds()?.executeAsList()?.toSet() ?: memory.mapTo(mutableSetOf()) { it.replyId } }
    private fun same(a: ByteArray?, b: ByteArray?): Boolean = if (a == null || b == null) a == null && b == null else a.contentEquals(b)
    private fun remove(change: Change) {
        database?.workspaceChangeQueries?.deleteChange(change.id)
        memory.removeAll { it.id == change.id }
        refresh()
    }
    suspend fun record(chatId: String, replyId: String, location: String, path: String, before: ByteArray?, after: ByteArray) = withContext(ioContext) {
        lock.withLock {
            val size = (before?.size ?: 0) + after.size
            if (size > MAX_FILE_BYTES) throw WorkspaceException("File is too large for safe undo (limit 4 MiB per change)")
            val all = changes()
            // Keep whole reply groups so an old task never has an unnoticed partial backup.
            var total = all.sumOf { (it.before?.size ?: 0) + it.after.size }
            var count = all.size
            for (group in all.groupBy { it.replyId }.values) {
                if (total + size <= MAX_TOTAL_BYTES && count < 256) break
                if (group.first().replyId == replyId) throw WorkspaceException("Undo storage limit reached for this task")
                count -= group.size
                total -= group.sumOf { (it.before?.size ?: 0) + it.after.size }
                group.forEach(::remove)
            }
            if (total + size > MAX_TOTAL_BYTES) throw WorkspaceException("Undo storage limit reached")
            val change = Change(newId(), chatId, replyId, location, path, before?.copyOf(), after.copyOf(), currentTimeMillis())
            if (database == null) memory += change else database.workspaceChangeQueries.insertChange(
                change.id, chatId, replyId, location, path, before, after, change.at)
            refresh()
        }
    }
    suspend fun undo(chatId: String, replyId: String, provider: WorkspaceProvider) = withContext(ioContext) {
        lock.withLock {
            val records = changes().filter { it.replyId == replyId && it.chatId == chatId }.asReversed()
            if (records.isEmpty()) throw WorkspaceException("No saved changes")
            // Preflight all paths, including multiple changes to one file, before touching any file.
            val expected = mutableMapOf<Pair<String, String>, ByteArray?>()
            for (record in records) {
                val key = record.location to record.path
                val folder = provider.open(record.location) ?: throw WorkspaceException("Project folder unavailable")
                val actual = if (key in expected) expected[key] else if (folder.exists(record.path)) folder.read(record.path) else null
                if (!same(actual, record.before) && !same(actual, record.after)) throw WorkspaceException("File changed since the assistant edit: " + record.path)
                expected[key] = record.before
            }
            for (record in records) {
                val folder = provider.open(record.location) ?: throw WorkspaceException("Project folder unavailable")
                val actual = if (folder.exists(record.path)) folder.read(record.path) else null
                if (same(actual, record.before)) { remove(record); continue }
                if (!same(actual, record.after)) throw WorkspaceException("File changed: " + record.path)
                withContext(NonCancellable) {
                    if (record.before == null) folder.deleteFile(record.path) else folder.write(record.path, record.before)
                    val restored = if (folder.exists(record.path)) folder.read(record.path) else null
                    if (record.before == null && restored != null || record.before != null && (restored == null || !restored.contentEquals(record.before))) {
                        throw WorkspaceException("Restoration verification failed: " + record.path)
                    }
                    remove(record)
                }
            }
        }
    }
    companion object {
        const val MAX_FILE_BYTES = 4 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    }
}
