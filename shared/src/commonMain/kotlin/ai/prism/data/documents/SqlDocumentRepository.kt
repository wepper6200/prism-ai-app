package ai.prism.data.documents

import ai.prism.core.currentTimeMillis
import ai.prism.core.newId
import ai.prism.data.db.Document
import ai.prism.data.db.PrismDatabase
import ai.prism.domain.documents.ChatDocument
import ai.prism.domain.documents.DocumentChunk
import ai.prism.domain.documents.DocumentError
import ai.prism.domain.documents.DocumentFormat
import ai.prism.domain.documents.DocumentStatus
import ai.prism.domain.repository.DocumentRepository
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Файлы диалогов и их фрагменты в SQLite (SQLDelight). */
class SqlDocumentRepository(
    private val database: PrismDatabase,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : DocumentRepository {

    private val queries get() = database.documentQueries

    override fun documents(chatId: String): Flow<List<ChatDocument>> =
        queries.selectDocumentsOfChat(chatId).asFlow().mapToList(ioContext).map { rows -> rows.map { it.toDomain() } }

    override suspend fun documentsSnapshot(chatId: String): List<ChatDocument> = withContext(ioContext) {
        queries.selectDocumentsOfChat(chatId).executeAsList().map { it.toDomain() }
    }

    override suspend fun add(chatId: String, name: String, format: DocumentFormat, sizeBytes: Long): ChatDocument =
        withContext(ioContext) {
            val document = ChatDocument(
                id = newId(),
                chatId = chatId,
                name = name,
                format = format,
                sizeBytes = sizeBytes,
                status = DocumentStatus.INDEXING,
                error = null,
                chunkCount = 0,
                addedAt = currentTimeMillis(),
            )
            queries.insertDocument(
                id = document.id,
                chat_id = chatId,
                name = name,
                format = format.name,
                size_bytes = sizeBytes,
                status = DocumentStatus.INDEXING.name,
                error = null,
                chunk_count = 0,
                embedding_model = null,
                added_at = document.addedAt,
            )
            document
        }

    override suspend fun saveChunks(documentId: String, embeddingModelId: String, chunks: List<DocumentChunk>) =
        withContext(ioContext) {
            queries.transaction {
                val document = queries.selectDocument(documentId).executeAsOneOrNull() ?: return@transaction
                queries.deleteChunksOfDocument(documentId)
                chunks.forEach { chunk ->
                    queries.insertChunk(
                        document_id = documentId,
                        chat_id = document.chat_id,
                        position = chunk.position.toLong(),
                        page = chunk.page?.toLong(),
                        text = chunk.text,
                        embedding = EmbeddingCodec.encode(chunk.embedding),
                    )
                }
                queries.updateDocumentStatus(
                    status = DocumentStatus.READY.name,
                    error = null,
                    chunk_count = chunks.size.toLong(),
                    embedding_model = embeddingModelId,
                    id = documentId,
                )
            }
        }

    override suspend fun markFailed(documentId: String, error: DocumentError) = withContext(ioContext) {
        queries.transaction {
            queries.deleteChunksOfDocument(documentId)
            queries.updateDocumentStatus(
                status = DocumentStatus.FAILED.name,
                error = error.name,
                chunk_count = 0,
                embedding_model = null,
                id = documentId,
            )
        }
    }

    override suspend fun chunks(chatId: String, embeddingModelId: String): List<DocumentChunk> = withContext(ioContext) {
        queries.selectReadyChunksOfChat(chatId, embeddingModelId).executeAsList().map { row ->
            DocumentChunk(
                documentId = row.document_id,
                documentName = row.name,
                position = row.position.toInt(),
                page = row.page?.toInt(),
                text = row.text,
                embedding = EmbeddingCodec.decode(row.embedding),
            )
        }
    }

    override suspend fun delete(documentId: String) = withContext(ioContext) {
        queries.transaction {
            queries.deleteChunksOfDocument(documentId)
            queries.deleteDocument(documentId)
        }
    }

    override suspend fun markInterruptedIndexing() = withContext(ioContext) {
        queries.markIndexingInterrupted()
        Unit
    }

    private fun Document.toDomain() = ChatDocument(
        id = id,
        chatId = chat_id,
        name = name,
        format = DocumentFormat.entries.firstOrNull { it.name == format } ?: DocumentFormat.TEXT,
        sizeBytes = size_bytes,
        status = DocumentStatus.entries.firstOrNull { it.name == status } ?: DocumentStatus.FAILED,
        error = DocumentError.entries.firstOrNull { it.name == error },
        chunkCount = chunk_count.toInt(),
        addedAt = added_at,
    )
}
