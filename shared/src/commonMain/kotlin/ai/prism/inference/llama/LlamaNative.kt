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
internal expect object LlamaNative {
    val STATUS_DONE: Int
    val STATUS_MAX_TOKENS: Int
    val STATUS_CANCELLED: Int
    val STATUS_CONTEXT_FULL: Int
    val STATUS_ERROR_TOKENIZE: Int
    val STATUS_ERROR_DECODE: Int
    val STATUS_ERROR_CALLBACK: Int
    val STATUS_ERROR_OOM: Int
    val STATUS_ERROR_IMAGE: Int

    /** Загружает бэкенды ggml из каталога (или из стандартных путей при `null`). */
    fun backendInit(backendDir: String?): Boolean

    /** Устройства вида `CPU:имя`, `GPU:имя`, `IGPU:имя`. */
    fun devices(): Array<String>

    /** Последнее сообщение об ошибке llama.cpp. */
    fun lastError(): String

    /** Возвращает дескриптор модели или 0 при ошибке. */
    fun loadModel(path: String, gpuLayers: Int, listener: LoadProgressListener?): Long

    fun freeModel(model: Long)

    fun modelDescription(model: Long): String

    fun modelTrainContext(model: Long): Int

    /** Возвращает дескриптор сессии (контекст + кэш) или 0 при нехватке памяти. */
    fun createSession(model: Long, contextSize: Int, batchSize: Int, threads: Int): Long

    fun freeSession(session: Long)

    fun contextSize(session: Long): Int

    /**
     * Сколько ячеек контекста займёт промпт; [imageSizes] — пары (ширина, высота) изображений по порядку меток.
     * −1 — промпт не удалось разобрать.
     */
    fun tokenCount(session: Long, projector: Long, text: ByteArray, imageSizes: IntArray?): Int

    fun cancel(session: Long)

    /** Загружает модуль зрения (mmproj) для модели; возвращает дескриптор или 0 при ошибке. */
    fun loadProjector(model: Long, path: String, useGpu: Boolean, threads: Int, maxImageTokens: Int): Long

    fun freeProjector(projector: Long)

    /**
     * Генерация ответа. Изображения передаются в порядке меток в промпте: пиксели RGB, размеры парами
     * (ширина, высота) и идентификаторы (по ним переиспользуется кэш). Без изображений — `null`.
     */
    fun generate(
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
    fun createEmbedder(model: Long, maxTokens: Int, threads: Int): Long

    fun freeEmbedder(embedder: Long)

    /** Эмбеддинг текста (UTF-8) единичной длины или `null` при ошибке (см. [lastError]). */
    fun embed(embedder: Long, text: ByteArray): FloatArray?
}
