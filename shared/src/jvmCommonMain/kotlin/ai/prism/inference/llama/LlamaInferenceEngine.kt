package ai.prism.inference.llama

import ai.prism.data.images.ImageCodec
import ai.prism.data.images.RgbImage
import ai.prism.domain.inference.EngineState
import ai.prism.domain.inference.GenerationRequest
import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.inference.InferenceError
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.inference.ModelInfo
import ai.prism.domain.inference.PromptImage
import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.ChatFormat
import ai.prism.domain.model.DeviceInfo
import ai.prism.inference.ChatPromptFormatter
import ai.prism.inference.ReplyFilter
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * [InferenceEngine] на llama.cpp через JNI.
 *
 * Все нативные вызовы идут из одного выделенного потока; [cancel] — из любого.
 * Модуль зрения загружается в память только при первом запросе с изображениями:
 * текстовые диалоги не тратят на него память телефона.
 */
class LlamaInferenceEngine(
    private val libraryLoader: NativeLibraryLoader,
    private val deviceInfo: DeviceInfo,
    private val imageCodec: ImageCodec,
) : InferenceEngine {

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "prism-llama").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mutex = Mutex()

    private val mutableState = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = mutableState.asStateFlow()

    private var backendReady = false
    private var gpuName: String? = null
    private var modelHandle = 0L
    private var loadedModel: CatalogModel? = null
    private var modelOnGpu = false
    private var projectorPath: String? = null
    private var projectorHandle = 0L

    @Volatile
    private var sessionHandle = 0L

    override suspend fun load(model: CatalogModel, filePath: String, projectorPath: String?) {
        mutex.withLock {
            withContext(dispatcher) { loadLocked(model, filePath, projectorPath) }
        }
    }

    override fun generate(request: GenerationRequest): Flow<String> = channelFlow {
        mutex.withLock {
            val model = loadedModel
            val session = sessionHandle
            if (model == null || session == 0L) {
                throw InferenceException(InferenceError.MODEL_NOT_LOADED, "Модель не загружена")
            }
            val producer = this
            withContext(dispatcher) {
                val dialog = request.primer + request.messages
                val projector = if (dialog.any { it.images.isNotEmpty() }) ensureProjectorLocked(model) else 0L
                val pixels = decodeImages(dialog.flatMap { it.images })
                val contextSize = LlamaNative.contextSize(session)
                val replyBudget = request.maxTokens.coerceAtMost(contextSize / 2)
                val prompt = fitPrompt(session, projector, model.chatFormat, request, contextSize - replyBudget, pixels)
                val images = prompt.images.map { image -> image.id to checkNotNull(pixels[image.id]) }
                val sampling = request.sampling
                val filter = ReplyFilter.forFormat(model.chatFormat)
                val status = LlamaNative.generate(
                    session = session,
                    projector = projector,
                    prompt = prompt.text.encodeToByteArray(),
                    imagePixels = images.takeIf { it.isNotEmpty() }?.map { it.second.rgb }?.toTypedArray(),
                    imageSizes = images.takeIf { it.isNotEmpty() }?.let { list -> sizesOf(list.map { it.second }) },
                    imageIds = images.takeIf { it.isNotEmpty() }?.map { it.first }?.toTypedArray(),
                    maxTokens = replyBudget,
                    temperature = sampling.temperature,
                    topP = sampling.topP,
                    topK = sampling.topK,
                    minP = sampling.minP,
                    repeatPenalty = sampling.repeatPenalty,
                    seed = sampling.seed,
                    listener = object : TokenListener {
                        override fun onPrefill(done: Int, total: Int): Boolean = producer.isActive

                        override fun onToken(bytes: ByteArray): Boolean {
                            val visible = filter.push(bytes.decodeToString())
                            if (visible.isNotEmpty()) producer.trySend(visible)
                            return producer.isActive
                        }
                    },
                )
                filter.finish().takeIf { it.isNotEmpty() }?.let { producer.trySend(it) }
                when (status) {
                    LlamaNative.STATUS_DONE, LlamaNative.STATUS_MAX_TOKENS, LlamaNative.STATUS_CANCELLED -> Unit
                    LlamaNative.STATUS_CONTEXT_FULL ->
                        throw InferenceException(InferenceError.CONTEXT_FULL, "Закончилось место в контексте модели")
                    LlamaNative.STATUS_ERROR_OOM ->
                        throw InferenceException(InferenceError.NOT_ENOUGH_MEMORY, "Не хватило памяти во время ответа")
                    LlamaNative.STATUS_ERROR_IMAGE ->
                        throw InferenceException(
                            InferenceError.IMAGE_FAILED,
                            "Модель не смогла обработать изображение: ${LlamaNative.lastError().trim()}",
                        )
                    else ->
                        throw InferenceException(
                            InferenceError.GENERATION_FAILED,
                            "Ошибка генерации ($status): ${LlamaNative.lastError().trim()}",
                        )
                }
            }
        }
    }.buffer(Channel.UNLIMITED)

    override fun cancel() {
        val session = sessionHandle
        if (session != 0L) LlamaNative.cancel(session)
    }

    override suspend fun unload() {
        mutex.withLock {
            withContext(dispatcher) {
                releaseLocked()
                mutableState.value = EngineState.Idle
            }
        }
    }

    override fun modelInfo(): ModelInfo? = (state.value as? EngineState.Ready)?.info

    private fun loadLocked(model: CatalogModel, filePath: String, projector: String?) {
        // Та же модель с тем же контекстом остаётся в памяти; новый размер контекста из настроек — перезагрузка.
        if (loadedModel?.id == model.id && loadedModel?.contextLength == model.contextLength && sessionHandle != 0L) {
            // Та же модель; модуль зрения мог быть докачан (или удалён) с прошлой загрузки.
            if (projector != projectorPath) {
                freeProjectorLocked()
                projectorPath = projector
                (mutableState.value as? EngineState.Ready)?.let { ready ->
                    mutableState.value = ready.copy(info = ready.info.copy(supportsImages = projector != null))
                }
            }
            return
        }
        releaseLocked()
        mutableState.value = EngineState.Loading(model.id, 0f)
        try {
            ensureBackend()
            checkModelFile(model, filePath)
            checkMemory(model)

            val listener = object : LoadProgressListener {
                override fun onProgress(progress: Float): Boolean {
                    mutableState.value = EngineState.Loading(model.id, progress.coerceIn(0f, 1f))
                    return true
                }
            }
            val useGpu = deviceInfo.preferGpu && gpuName != null
            var handle = LlamaNative.loadModel(filePath, if (useGpu) ALL_LAYERS else 0, listener)
            var onGpu = useGpu
            if (handle == 0L && useGpu) {
                // Не хватило видеопамяти или драйвер отказал — пробуем на процессоре.
                handle = LlamaNative.loadModel(filePath, 0, listener)
                onGpu = false
            }
            if (handle == 0L) throw loadFailure()

            val trained = LlamaNative.modelTrainContext(handle).takeIf { it > 0 } ?: model.contextLength
            val wanted = model.contextLength.coerceAtMost(trained)
            var session = 0L
            val smallest = minOf(MIN_CONTEXT, wanted)
            for (size in listOf(wanted, wanted / 2, MIN_CONTEXT).distinct().filter { it in smallest..wanted }) {
                session = LlamaNative.createSession(handle, size, BATCH_SIZE, deviceInfo.inferenceThreads)
                if (session != 0L) break
            }
            if (session == 0L) {
                LlamaNative.freeModel(handle)
                throw InferenceException(InferenceError.NOT_ENOUGH_MEMORY, "Не хватает памяти для контекста модели")
            }

            modelHandle = handle
            sessionHandle = session
            loadedModel = model
            modelOnGpu = onGpu
            projectorPath = projector
            mutableState.value = EngineState.Ready(
                ModelInfo(
                    modelId = model.id,
                    name = model.name,
                    description = LlamaNative.modelDescription(handle),
                    contextSize = LlamaNative.contextSize(session),
                    accelerator = if (onGpu) gpuName ?: CPU else CPU,
                    supportsImages = projector != null,
                ),
            )
        } catch (e: InferenceException) {
            mutableState.value = EngineState.Failed(model.id, e.error)
            throw e
        } catch (e: OutOfMemoryError) {
            mutableState.value = EngineState.Failed(model.id, InferenceError.NOT_ENOUGH_MEMORY)
            throw InferenceException(InferenceError.NOT_ENOUGH_MEMORY, "Не хватает памяти", e)
        }
    }

    private fun ensureBackend() {
        if (backendReady) return
        val backendDir = libraryLoader.load()
        if (!LlamaNative.backendInit(backendDir)) {
            throw InferenceException(InferenceError.NATIVE_LIBRARY_MISSING, "Не найдены вычислительные модули ggml")
        }
        gpuName = LlamaNative.devices()
            .firstOrNull { it.startsWith("GPU:") || it.startsWith("IGPU:") }
            ?.substringAfter(':')
        backendReady = true
    }

    private fun checkModelFile(model: CatalogModel, filePath: String) {
        if (!isGguf(File(filePath), model.sizeBytes)) {
            throw InferenceException(InferenceError.MODEL_CORRUPTED, "Файл модели повреждён или скачан не полностью")
        }
    }

    private fun isGguf(file: File, expectedSize: Long): Boolean {
        if (!file.isFile || file.length() != expectedSize) return false
        val magic = ByteArray(4)
        val read = file.inputStream().use { it.read(magic) }
        return read == 4 && magic.contentEquals(GGUF_MAGIC)
    }

    /** Загружает модуль зрения при первом запросе с изображениями. */
    private fun ensureProjectorLocked(model: CatalogModel): Long {
        if (projectorHandle != 0L) return projectorHandle
        val path = projectorPath
        val vision = model.vision
        if (path == null || vision == null) {
            throw InferenceException(InferenceError.IMAGES_NOT_SUPPORTED, "У модели нет модуля зрения")
        }
        if (!isGguf(File(path), vision.sizeBytes)) {
            throw InferenceException(InferenceError.VISION_MODULE_CORRUPTED, "Файл модуля зрения повреждён")
        }
        val threads = deviceInfo.inferenceThreads
        var handle = LlamaNative.loadProjector(modelHandle, path, modelOnGpu, threads, MAX_IMAGE_TOKENS)
        if (handle == 0L && modelOnGpu) {
            handle = LlamaNative.loadProjector(modelHandle, path, false, threads, MAX_IMAGE_TOKENS)
        }
        if (handle == 0L) {
            val error = LlamaNative.lastError().lowercase()
            throw if ("memory" in error || "alloc" in error) {
                InferenceException(InferenceError.NOT_ENOUGH_MEMORY, "Не хватает памяти для модуля зрения")
            } else {
                InferenceException(InferenceError.VISION_MODULE_CORRUPTED, "Не удалось загрузить модуль зрения: ${error.trim()}")
            }
        }
        projectorHandle = handle
        return handle
    }

    /** Пиксели всех изображений запроса по идентификатору. */
    private fun decodeImages(images: List<PromptImage>): Map<String, RgbImage> {
        val result = HashMap<String, RgbImage>()
        for (image in images) {
            if (image.id in result) continue
            val decoded = try {
                imageCodec.decodeRgb(File(image.path))
            } catch (e: OutOfMemoryError) {
                throw InferenceException(InferenceError.NOT_ENOUGH_MEMORY, "Не хватает памяти для изображения", e)
            } ?: throw InferenceException(InferenceError.IMAGE_FAILED, "Не удалось открыть изображение")
            result[image.id] = decoded
        }
        return result
    }

    private fun sizesOf(images: List<RgbImage>): IntArray =
        IntArray(images.size * 2) { if (it % 2 == 0) images[it / 2].width else images[it / 2].height }

    private class FittedPrompt(val text: String, val images: List<PromptImage>)

    private fun checkMemory(model: CatalogModel) {
        val required = model.minRamMb.toLong() * BYTES_IN_MB
        // Устройства сообщают чуть меньше памяти, чем указано в характеристиках.
        if (deviceInfo.totalRamBytes < required * RAM_TOLERANCE_PERCENT / 100) {
            throw InferenceException(InferenceError.NOT_ENOUGH_MEMORY, "Модели нужно не меньше ${model.minRamMb} МБ ОЗУ")
        }
    }

    private fun loadFailure(): InferenceException {
        val error = LlamaNative.lastError().lowercase()
        return if ("memory" in error || "alloc" in error) {
            InferenceException(InferenceError.NOT_ENOUGH_MEMORY, "Не хватает памяти для загрузки модели")
        } else {
            InferenceException(InferenceError.MODEL_CORRUPTED, "Не удалось прочитать модель: ${error.trim()}")
        }
    }

    /** Собирает промпт, отбрасывая самые старые реплики (вместе с их изображениями), пока он не поместится в [budget]. */
    private fun fitPrompt(
        session: Long,
        projector: Long,
        format: ChatFormat,
        request: GenerationRequest,
        budget: Int,
        pixels: Map<String, RgbImage>,
    ): FittedPrompt {
        var messages = request.messages
        while (true) {
            val dialog = request.primer + ChatPromptFormatter.withReminder(messages, request.reminder)
            val prompt = ChatPromptFormatter.format(format, request.systemPrompt, dialog)
            val images = dialog.flatMap { it.images }
            val sizes = if (images.isEmpty()) null else sizesOf(images.map { checkNotNull(pixels[it.id]) })
            val tokens = LlamaNative.tokenCount(session, projector, prompt.encodeToByteArray(), sizes)
            if (tokens < 0) throw InferenceException(InferenceError.IMAGE_FAILED, "Не удалось подготовить изображения")
            if (tokens <= budget) return FittedPrompt(prompt, images)
            if (messages.size <= 1) {
                throw InferenceException(InferenceError.CONTEXT_FULL, "Сообщение слишком длинное для модели")
            }
            messages = messages.drop(if (messages.size >= 3) 2 else 1)
        }
    }

    private fun freeProjectorLocked() {
        if (projectorHandle != 0L) LlamaNative.freeProjector(projectorHandle)
        projectorHandle = 0L
    }

    private fun releaseLocked() {
        freeProjectorLocked()
        projectorPath = null
        val session = sessionHandle
        sessionHandle = 0L
        if (session != 0L) LlamaNative.freeSession(session)
        if (modelHandle != 0L) LlamaNative.freeModel(modelHandle)
        modelHandle = 0L
        loadedModel = null
    }

    private companion object {
        const val CPU = "CPU"
        const val ALL_LAYERS = 999
        const val BATCH_SIZE = 512
        const val MIN_CONTEXT = 2048

        /**
         * Сколько токенов контекста занимает одно изображение у моделей с переменным разрешением (Gemma 4: 70–1120).
         * 280 — рекомендованный баланс детализации и скорости; должно помещаться в один батч ([BATCH_SIZE]).
         */
        const val MAX_IMAGE_TOKENS = 280
        const val BYTES_IN_MB = 1024L * 1024L
        const val RAM_TOLERANCE_PERCENT = 85
        val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    }
}
