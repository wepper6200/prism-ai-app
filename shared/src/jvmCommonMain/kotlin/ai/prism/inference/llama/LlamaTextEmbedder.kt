package ai.prism.inference.llama

import ai.prism.domain.documents.TextEmbedder
import ai.prism.domain.inference.InferenceError
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.DeviceInfo
import ai.prism.domain.model.EmbeddingSettings
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * [TextEmbedder] на llama.cpp: модель эмбеддингов (EmbeddingGemma) считается на процессоре
 * в своём потоке и не мешает модели чата.
 */
class LlamaTextEmbedder(
    private val libraryLoader: NativeLibraryLoader,
    private val deviceInfo: DeviceInfo,
) : TextEmbedder {

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "prism-embed").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mutex = Mutex()

    private var backendReady = false
    private var loadedPath: String? = null
    private var modelHandle = 0L
    private var embedderHandle = 0L

    override suspend fun embed(
        model: CatalogModel,
        filePath: String,
        texts: List<String>,
        onProgress: (Int) -> Unit,
    ): List<FloatArray> = mutex.withLock {
        withContext(dispatcher) {
            ensureLoaded(model, filePath)
            texts.mapIndexed { index, text ->
                ensureActive()
                val vector = LlamaNative.embed(embedderHandle, text.encodeToByteArray()) ?: throw failure()
                onProgress(index + 1)
                vector
            }
        }
    }

    override suspend fun release() {
        mutex.withLock { withContext(dispatcher) { releaseLocked() } }
    }

    private fun ensureLoaded(model: CatalogModel, filePath: String) {
        if (loadedPath == filePath && embedderHandle != 0L) return
        releaseLocked()
        if (!backendReady) {
            val backendDir = libraryLoader.load()
            if (!LlamaNative.backendInit(backendDir)) {
                throw InferenceException(InferenceError.NATIVE_LIBRARY_MISSING, "Не найдены вычислительные модули ggml")
            }
            backendReady = true
        }
        val file = File(filePath)
        if (!file.isFile || file.length() != model.sizeBytes) {
            throw InferenceException(InferenceError.MODEL_CORRUPTED, "Файл модели поиска повреждён или скачан не полностью")
        }
        val handle = LlamaNative.loadModel(filePath, 0, null)
        if (handle == 0L) throw failure(loading = true)
        val settings = model.embedding ?: EmbeddingSettings()
        val embedder = LlamaNative.createEmbedder(handle, settings.maxTokens, deviceInfo.inferenceThreads)
        if (embedder == 0L) {
            val error = failure(loading = true)
            LlamaNative.freeModel(handle)
            throw error
        }
        modelHandle = handle
        embedderHandle = embedder
        loadedPath = filePath
    }

    private fun failure(loading: Boolean = false): InferenceException {
        val error = LlamaNative.lastError().lowercase()
        return when {
            "memory" in error || "alloc" in error ->
                InferenceException(InferenceError.NOT_ENOUGH_MEMORY, "Не хватает памяти для модели поиска")
            loading -> InferenceException(InferenceError.MODEL_CORRUPTED, "Не удалось прочитать модель поиска: ${error.trim()}")
            else -> InferenceException(InferenceError.GENERATION_FAILED, "Не удалось вычислить эмбеддинг: ${error.trim()}")
        }
    }

    private fun releaseLocked() {
        if (embedderHandle != 0L) LlamaNative.freeEmbedder(embedderHandle)
        if (modelHandle != 0L) LlamaNative.freeModel(modelHandle)
        embedderHandle = 0L
        modelHandle = 0L
        loadedPath = null
    }
}
