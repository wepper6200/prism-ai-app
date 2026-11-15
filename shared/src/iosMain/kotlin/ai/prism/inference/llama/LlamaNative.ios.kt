@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package ai.prism.inference.llama

import ai.prism.apple.native.*
import kotlinx.cinterop.*

private fun consumeString(value: CPointer<ByteVar>?): String = try { value?.toKString().orEmpty() } finally { prism_free(value) }

private val progressCallback = staticCFunction { context: COpaquePointer?, progress: Float ->
    try { if (context!!.asStableRef<LoadProgressListener>().get().onProgress(progress)) 1 else 0 } catch (_: Throwable) { 0 }
}
private val tokenCallback = staticCFunction { context: COpaquePointer?, bytes: CPointer<ByteVar>?, size: Int ->
    try { if (context!!.asStableRef<TokenListener>().get().onToken(bytes!!.readBytes(size))) 1 else 0 } catch (_: Throwable) { 0 }
}
private val prefillCallback = staticCFunction { context: COpaquePointer?, done: Int, total: Int ->
    try { if (context!!.asStableRef<TokenListener>().get().onPrefill(done, total)) 1 else 0 } catch (_: Throwable) { 0 }
}

internal actual object LlamaNative {
    actual const val STATUS_DONE = 0
    actual const val STATUS_MAX_TOKENS = 1
    actual const val STATUS_CANCELLED = 2
    actual const val STATUS_CONTEXT_FULL = 3
    actual const val STATUS_ERROR_TOKENIZE = -1
    actual const val STATUS_ERROR_DECODE = -2
    actual const val STATUS_ERROR_CALLBACK = -3
    actual const val STATUS_ERROR_OOM = -4
    actual const val STATUS_ERROR_IMAGE = -5

    actual fun backendInit(backendDir: String?): Boolean = prism_backend_init() != 0
    actual fun devices(): Array<String> = consumeString(prism_devices()).lines().filter { it.isNotBlank() }.toTypedArray()
    actual fun lastError(): String = consumeString(prism_last_error())
    actual fun loadModel(path: String, gpuLayers: Int, listener: LoadProgressListener?): Long {
        val ref = listener?.let { StableRef.create(it) }
        return try { prism_load_model(path, gpuLayers, if (ref == null) null else progressCallback, ref?.asCPointer()) }
        finally { ref?.dispose() }
    }
    actual fun freeModel(model: Long) = prism_free_model(model)
    actual fun modelDescription(model: Long): String = consumeString(prism_model_description(model))
    actual fun modelTrainContext(model: Long): Int = prism_model_context(model)
    actual fun createSession(model: Long, contextSize: Int, batchSize: Int, threads: Int): Long = prism_create_session(model, contextSize, batchSize, threads)
    actual fun freeSession(session: Long) = prism_free_session(session)
    actual fun contextSize(session: Long): Int = prism_context_size(session)
    actual fun cancel(session: Long) = prism_cancel(session)
    actual fun tokenCount(session: Long, projector: Long, text: ByteArray, imageSizes: IntArray?): Int =
        text.usePinned { prompt ->
            if (imageSizes == null || imageSizes.isEmpty()) prism_token_count(session, projector, if (text.isEmpty()) null else prompt.addressOf(0), text.size, null, 0)
            else imageSizes.usePinned { sizes -> prism_token_count(session, projector, if (text.isEmpty()) null else prompt.addressOf(0), text.size, sizes.addressOf(0), imageSizes.size) }
        }
    actual fun loadProjector(model: Long, path: String, useGpu: Boolean, threads: Int, maxImageTokens: Int): Long =
        prism_load_projector(model, path, if (useGpu) 1 else 0, threads, maxImageTokens)
    actual fun freeProjector(projector: Long) = prism_free_projector(projector)

    actual fun generate(session: Long, projector: Long, prompt: ByteArray, imagePixels: Array<ByteArray>?, imageSizes: IntArray?,
                        imageIds: Array<String>?, maxTokens: Int, temperature: Float, topP: Float, topK: Int, minP: Float,
                        repeatPenalty: Float, seed: Int, listener: TokenListener): Int {
        val ref = StableRef.create(listener)
        val pixels = imagePixels.orEmpty().map { it.pin() }
        return try {
            memScoped {
                val images = if (pixels.isEmpty()) null else allocArray<prism_image>(pixels.size)
                pixels.forEachIndexed { index, value ->
                    images!![index].pixels = value.addressOf(0)
                    images[index].width = imageSizes!![index * 2]
                    images[index].height = imageSizes[index * 2 + 1]
                    images[index].id = imageIds!![index].cstr.ptr
                }
                prompt.usePinned { bytes ->
                    prism_generate(session, projector, if (prompt.isEmpty()) null else bytes.addressOf(0), prompt.size, images, pixels.size,
                        maxTokens, temperature, topP, topK, minP, repeatPenalty, seed, tokenCallback, prefillCallback, ref.asCPointer())
                }
            }
        } finally { pixels.forEach { it.unpin() }; ref.dispose() }
    }
    actual fun createEmbedder(model: Long, maxTokens: Int, threads: Int): Long = prism_create_embedder(model, maxTokens, threads)
    actual fun freeEmbedder(embedder: Long) = prism_free_embedder(embedder)
    actual fun embed(embedder: Long, text: ByteArray): FloatArray? = memScoped {
        val count = alloc<IntVar>()
        val result = text.usePinned { prism_embed(embedder, if (text.isEmpty()) null else it.addressOf(0), text.size, count.ptr) } ?: return@memScoped null
        try { FloatArray(count.value) { result[it] } } finally { prism_free(result) }
    }
}
