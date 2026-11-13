package ai.prism.data.memory

import ai.prism.core.currentTimeMillis
import ai.prism.core.newId
import ai.prism.data.db.PrismDatabase
import ai.prism.domain.model.UserMemory
import ai.prism.domain.repository.MemoryRepository
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Память о пользователе в SQLite. Не больше [MAX_MEMORIES] фактов: самые старые вытесняются. */
class SqlMemoryRepository(
    private val database: PrismDatabase,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : MemoryRepository {

    private val queries get() = database.userMemoryQueries

    override val memories: Flow<List<UserMemory>> = queries.selectMemories().asFlow().mapToList(ioContext)
        .map { rows -> rows.map { UserMemory(it.id, it.text, it.created_at) } }

    override suspend fun snapshot(): List<UserMemory> = withContext(ioContext) {
        queries.selectMemories().executeAsList().map { UserMemory(it.id, it.text, it.created_at) }
    }

    override suspend fun add(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        withContext(ioContext) { insert(clean) }
    }

    private fun insert(clean: String) {
        queries.transaction {
            val existing = queries.selectMemories().executeAsList()
            if (existing.any { it.text.equals(clean, ignoreCase = true) }) return@transaction
            existing.take((existing.size + 1 - MAX_MEMORIES).coerceAtLeast(0)).forEach { queries.deleteMemory(it.id) }
            queries.insertMemory(id = newId(), text = clean, created_at = currentTimeMillis())
        }
    }

    override suspend fun delete(id: String) {
        withContext(ioContext) { queries.deleteMemory(id) }
    }

    override suspend fun clear() {
        withContext(ioContext) { queries.clearMemories() }
    }

    companion object {
        const val MAX_MEMORIES = 50
    }
}
