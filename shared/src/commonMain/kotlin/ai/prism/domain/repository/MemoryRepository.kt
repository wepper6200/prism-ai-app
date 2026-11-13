package ai.prism.domain.repository

import ai.prism.domain.model.UserMemory
import kotlinx.coroutines.flow.Flow

/** Память о пользователе между диалогами: имя, предпочтения, проекты. Только на устройстве. */
interface MemoryRepository {
    val memories: Flow<List<UserMemory>>

    suspend fun snapshot(): List<UserMemory>

    suspend fun add(text: String)

    suspend fun delete(id: String)

    suspend fun clear()

    object None : MemoryRepository {
        override val memories: Flow<List<UserMemory>> = kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun snapshot(): List<UserMemory> = emptyList()
        override suspend fun add(text: String) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun clear() = Unit
    }
}
