package ai.prism.domain.repository

import ai.prism.domain.documents.ChatDocument
import ai.prism.domain.documents.DocumentChunk
import ai.prism.domain.documents.DocumentError
import ai.prism.domain.documents.DocumentFormat
import kotlinx.coroutines.flow.Flow

/** Файлы диалогов и их фрагменты для поиска. Удаляются вместе с диалогом ([ChatRepository.deleteChat]). */
interface DocumentRepository {
    fun documents(chatId: String): Flow<List<ChatDocument>>

    suspend fun documentsSnapshot(chatId: String): List<ChatDocument>

    /** Новый файл в состоянии «готовится». */
    suspend fun add(chatId: String, name: String, format: DocumentFormat, sizeBytes: Long): ChatDocument

    /** Сохраняет фрагменты и помечает файл готовым к поиску моделью [embeddingModelId]. */
    suspend fun saveChunks(documentId: String, embeddingModelId: String, chunks: List<DocumentChunk>)

    suspend fun markFailed(documentId: String, error: DocumentError)

    /** Фрагменты готовых файлов диалога, подготовленные моделью [embeddingModelId]. */
    suspend fun chunks(chatId: String, embeddingModelId: String): List<DocumentChunk>

    suspend fun delete(documentId: String)

    /** Файлы, которые готовились в момент закрытия приложения, помечаются прерванными. */
    suspend fun markInterruptedIndexing()
}
