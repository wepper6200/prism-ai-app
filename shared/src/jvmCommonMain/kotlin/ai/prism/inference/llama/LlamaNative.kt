package ai.prism.inference.llama

/** Прогресс загрузки модели (0..1). Вернуть `false`, чтобы прервать загрузку. */
interface LoadProgressListener {
    fun onProgress(progress: Float): Boolean
}

/** События генерации. Вернуть `false`, чтобы остановить генерацию. */
interface TokenListener {
    /** Обработано [done] из [total] новых токенов промпта. */
    fun onPrefill(done: Int, total: Int): Boolean

    /** Очередной фрагмент ответа — всегда целые символы UTF-8. */
    fun onToken(bytes: ByteArray): Boolean
}

/** JNI-функции библиотеки `prism_llama` (native/prism_llama.cpp). */
internal object LlamaNative {
    const val STATUS_DONE = 0
    const val STATUS_MAX_TOKENS = 1
    const val STATUS_CANCELLED = 2
    const val STATUS_CONTEXT_FULL = 3
    const val STATUS_ERROR_TOKENIZE = -1
    const val STATUS_ERROR_DECODE = -2
    const val STATUS_ERROR_CALLBACK = -3
    const val STATUS_ERROR_OOM = -4
    const val STATUS_ERROR_IMAGE = -5

    /** Загружает бэкенды ggml из каталога (или из стандартных путей при `null`). */
    external fun backendInit(backendDir: String?): Boolean

    /** Устройства вида `CPU:имя`, `GPU:имя`, `IGPU:имя`. */
    external fun devices(): Array<String>

    /** Последнее сообщение об ошибке llama.cpp. */
    external fun lastError(): String

    /** Возвращает дескриптор модели или 0 при ошибке. */
    external fun loadModel(path: String, gpuLayers: Int, listener: LoadProgressListener?): Long

    external fun freeModel(model: Long)

    external fun modelDescription(model: Long): String

    external fun modelTrainContext(model: Long): Int

    /** Возвращает дескриптор сессии (контекст + кэш) или 0 при нехватке памяти. */
    external fun createSession(model: Long, contextSize: Int, batchSize: Int, threads: Int): Long

    external fun freeSession(session: Long)

    external fun contextSize(session: Long): Int

    /**
     * Сколько ячеек контекста займёт промпт; [imageSizes] — пары (ширина, высота) изображений по порядку меток.
     * −1 — промпт не удалось разобрать.
     */
    external fun tokenCount(session: Long, projector: Long, text: ByteArray, imageSizes: IntArray?): Int

    external fun cancel(session: Long)

    /** Загружает модуль зрения (mmproj) для модели; возвращает дескриптор или 0 при ошибке. */
    external fun loadProjector(model: Long, path: String, useGpu: Boolean, threads: Int, maxImageTokens: Int): Long

    external fun freeProjector(projector: Long)

    /**
     * Генерация ответа. Изображения передаются в порядке меток в промпте: пиксели RGB, размеры парами
     * (ширина, высота) и идентификаторы (по ним переиспользуется кэш). Без изображений — `null`.
     */
    external fun generate(
        session: Long,
        projector: Long,
        prompt: ByteArray,
        imagePixels: Array<ByteArray>?,
        imageSizes: IntArray?,
        imageIds: Array<String>?,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        seed: Int,
        listener: TokenListener,
    ): Int

    /**
     * Контекст для эмбеддингов модели [model]: текст до [maxTokens] токенов за раз.
     * 0 — нехватка памяти или модель не выдаёт эмбеддинг текста.
     */
    external fun createEmbedder(model: Long, maxTokens: Int, threads: Int): Long

    external fun freeEmbedder(embedder: Long)

    /** Эмбеддинг текста (UTF-8) единичной длины или `null` при ошибке (см. [lastError]). */
    external fun embed(embedder: Long, text: ByteArray): FloatArray?
}
