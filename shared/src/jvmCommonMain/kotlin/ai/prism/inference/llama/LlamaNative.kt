package ai.prism.inference.llama

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

    /** Загружает бэкенды ggml из каталога (или из стандартных путей при `null`). */
    actual external fun backendInit(backendDir: String?): Boolean

    /** Устройства вида `CPU:имя`, `GPU:имя`, `IGPU:имя`. */
    actual external fun devices(): Array<String>

    /** Последнее сообщение об ошибке llama.cpp. */
    actual external fun lastError(): String

    /** Возвращает дескриптор модели или 0 при ошибке. */
    actual external fun loadModel(path: String, gpuLayers: Int, listener: LoadProgressListener?): Long

    actual external fun freeModel(model: Long)

    actual external fun modelDescription(model: Long): String

    actual external fun modelTrainContext(model: Long): Int

    /** Возвращает дескриптор сессии (контекст + кэш) или 0 при нехватке памяти. */
    actual external fun createSession(model: Long, contextSize: Int, batchSize: Int, threads: Int): Long

    actual external fun freeSession(session: Long)

    actual external fun contextSize(session: Long): Int

    /**
     * Сколько ячеек контекста займёт промпт; [imageSizes] — пары (ширина, высота) изображений по порядку меток.
     * −1 — промпт не удалось разобрать.
     */
    actual external fun tokenCount(session: Long, projector: Long, text: ByteArray, imageSizes: IntArray?): Int

    actual external fun cancel(session: Long)

    /** Загружает модуль зрения (mmproj) для модели; возвращает дескриптор или 0 при ошибке. */
    actual external fun loadProjector(model: Long, path: String, useGpu: Boolean, threads: Int, maxImageTokens: Int): Long

    actual external fun freeProjector(projector: Long)

    /**
     * Генерация ответа. Изображения передаются в порядке меток в промпте: пиксели RGB, размеры парами
     * (ширина, высота) и идентификаторы (по ним переиспользуется кэш). Без изображений — `null`.
     */
    actual external fun generate(
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
    actual external fun createEmbedder(model: Long, maxTokens: Int, threads: Int): Long

    actual external fun freeEmbedder(embedder: Long)

    /** Эмбеддинг текста (UTF-8) единичной длины или `null` при ошибке (см. [lastError]). */
    actual external fun embed(embedder: Long, text: ByteArray): FloatArray?
}
