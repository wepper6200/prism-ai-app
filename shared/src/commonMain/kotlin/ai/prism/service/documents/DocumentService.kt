package ai.prism.service.documents

import ai.prism.domain.documents.ChatDocument
import ai.prism.domain.documents.DocumentChunk
import ai.prism.domain.documents.DocumentError
import ai.prism.domain.documents.DocumentException
import ai.prism.domain.documents.DocumentFormat
import ai.prism.domain.documents.DocumentStatus
import ai.prism.domain.documents.DocumentTextExtractor
import ai.prism.domain.documents.TextEmbedder
import ai.prism.domain.inference.InferenceError
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.EmbeddingSettings
import ai.prism.domain.model.ModelKind
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.repository.DocumentRepository
import ai.prism.domain.repository.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Фрагменты файлов диалога для ответа на вопрос. */
fun interface DocumentRetriever {
    /**
     * Фрагменты файлов диалога [chatId], подходящие к [query], общим объёмом до [maxChars] символов.
     * Пусто — у диалога нет готовых файлов.
     * @throws DocumentException файлы есть, но модель поиска по файлам не скачана.
     * @throws InferenceException модель поиска не загрузилась.
     */
    suspend fun retrieve(chatId: String, query: String, maxChars: Int): List<DocumentChunk>

    companion object {
        val None = DocumentRetriever { _, _, _ -> emptyList() }
    }
}

/**
 * Файлы диалогов: извлекает текст, делит его на фрагменты, считает эмбеддинги и ищет фрагменты для ответа.
 * Файлы готовятся по одному; модель эмбеддингов выгружается, когда очередь пуста и после каждого поиска,
 * чтобы не занимать память, нужную модели чата.
 */
class DocumentService(
    private val repository: DocumentRepository,
    private val models: ModelRepository,
    private val extractor: DocumentTextExtractor,
    private val embedder: TextEmbedder,
    private val scope: CoroutineScope,
    private val workContext: CoroutineContext = Dispatchers.Default,
    private val chunker: TextChunker = TextChunker(),
) : DocumentRetriever {

    private val mutex = Mutex()
    private val jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val pending = MutableStateFlow(0)
    private val mutableProgress = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Доля готовности файлов, которые сейчас готовятся (0..1), по id файла. */
    val progress: StateFlow<Map<String, Float>> = mutableProgress.asStateFlow()

    init {
        scope.launch { repository.markInterruptedIndexing() }
    }

    /** Скачанная модель поиска по файлам (каталог уже отфильтрован по платформе) или `null`. */
    fun embeddingModel(): CatalogModel? = models.models.value
        .firstOrNull { it.model.kind == ModelKind.EMBEDDING && it.status == ModelStatus.Downloaded }
        ?.model

    /** Прикладывает файл [name] к диалогу и готовит его к поиску в фоне. */
    suspend fun add(chatId: String, name: String, bytes: ByteArray): ChatDocument {
        val format = DocumentFormat.ofFileName(name)
        val document = repository.add(chatId, name, format ?: DocumentFormat.TEXT, bytes.size.toLong())
        val error = when {
            format == null -> DocumentError.UNSUPPORTED
            bytes.size > MAX_FILE_BYTES -> DocumentError.TOO_LARGE
            embeddingModel() == null -> DocumentError.NO_EMBEDDING_MODEL
            else -> null
        }
        if (error != null || format == null) {
            repository.markFailed(document.id, error ?: DocumentError.UNSUPPORTED)
            return document.copy(status = DocumentStatus.FAILED, error = error)
        }
        pending.update { it + 1 }
        mutableProgress.update { it + (document.id to 0f) }
        val job = scope.launch {
            try {
                mutex.withLock { index(document, format, bytes) }
            } finally {
                withContext(NonCancellable) {
                    mutableProgress.update { it - document.id }
                    jobs.update { it - document.id }
                    if (pending.updateAndGet { it - 1 } == 0) mutex.withLock { embedder.release() }
                }
            }
        }
        jobs.update { if (job.isActive) it + (document.id to job) else it }
        return document
    }

    /** Убирает файл из диалога (подготовка, если идёт, прерывается). */
    suspend fun remove(documentId: String) {
        jobs.value[documentId]?.cancel()
        jobs.update { it - documentId }
        repository.delete(documentId)
    }

    override suspend fun retrieve(chatId: String, query: String, maxChars: Int): List<DocumentChunk> {
        val documents = repository.documentsSnapshot(chatId).filter { it.status == DocumentStatus.READY }
        if (documents.isEmpty()) return emptyList()
        val model = embeddingModel()
        val path = model?.let { models.downloadedFilePath(it.id) }
        if (model == null || path == null) {
            throw DocumentException(DocumentError.NO_EMBEDDING_MODEL, "Модель поиска по файлам не скачана")
        }
        val chunks = repository.chunks(chatId, model.id)
        if (chunks.isEmpty() || DocumentSearch.fitsWhole(chunks, maxChars)) return chunks
        val settings = model.embedding ?: EmbeddingSettings()
        val vector = mutex.withLock {
            try {
                embedder.embed(model, path, listOf(settings.query(query))).single()
            } finally {
                if (pending.value == 0) withContext(NonCancellable) { embedder.release() }
            }
        }
        return DocumentSearch.select(chunks, vector, maxChars)
    }

    private suspend fun index(document: ChatDocument, format: DocumentFormat, bytes: ByteArray) {
        try {
            val model = embeddingModel()
            val path = model?.let { models.downloadedFilePath(it.id) }
                ?: throw DocumentException(DocumentError.NO_EMBEDDING_MODEL, "Модель поиска по файлам не скачана")
            val pieces = withContext(workContext) { chunker.chunk(extractor.extract(bytes, format)) }
            if (pieces.isEmpty()) throw DocumentException(DocumentError.NO_TEXT, "В файле нет текста")
            if (pieces.size > MAX_CHUNKS) throw DocumentException(DocumentError.TOO_LARGE, "Слишком много текста")
            val settings = model.embedding ?: EmbeddingSettings()
            val title = document.name.substringBeforeLast('.')
            val vectors = embedder.embed(model, path, pieces.map { settings.document(title, it.text) }) { done ->
                mutableProgress.update { it + (document.id to done.toFloat() / pieces.size) }
            }
            val chunks = pieces.zip(vectors).mapIndexed { index, (piece, vector) ->
                DocumentChunk(document.id, document.name, index, piece.page, piece.text, vector)
            }
            repository.saveChunks(document.id, model.id, chunks)
        } catch (e: DocumentException) {
            repository.markFailed(document.id, e.error)
        } catch (e: InferenceException) {
            repository.markFailed(document.id, e.error.toDocumentError())
        }
    }

    private fun InferenceError.toDocumentError(): DocumentError = when (this) {
        InferenceError.NOT_ENOUGH_MEMORY -> DocumentError.NOT_ENOUGH_MEMORY
        InferenceError.MODEL_CORRUPTED -> DocumentError.MODEL_CORRUPTED
        else -> DocumentError.FAILED
    }

    companion object {
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
        const val MAX_CHUNKS = 3000
    }
}
