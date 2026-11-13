package ai.prism.domain.inference

import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Локальный движок генерации текста (и понимания изображений, если у модели есть модуль зрения). Сейчас — llama.cpp; позже через этот же интерфейс
 * можно подключить Ollama как второй бэкенд на Windows.
 */
interface InferenceEngine {
    val state: StateFlow<EngineState>

    /**
     * Загружает модель (если загружена другая — выгружает её). Бросает [InferenceException].
     * [projectorPath] — скачанный модуль зрения: он загружается в память только при первом запросе с изображениями.
     */
    suspend fun load(model: CatalogModel, filePath: String, projectorPath: String? = null)

    /**
     * Потоковая генерация ответа: фрагменты текста по мере появления.
     * Завершается нормально по концу ответа или после [cancel]; ошибки — [InferenceException].
     */
    fun generate(request: GenerationRequest): Flow<String>

    /** Останавливает текущую генерацию (можно вызывать из любого потока). */
    fun cancel()

    suspend fun unload()

    fun modelInfo(): ModelInfo?
}

sealed interface EngineState {
    data object Idle : EngineState

    data class Loading(val modelId: String, val progress: Float) : EngineState

    data class Ready(val info: ModelInfo) : EngineState

    data class Failed(val modelId: String, val error: InferenceError) : EngineState
}

data class ModelInfo(
    val modelId: String,
    val name: String,
    val description: String,
    val contextSize: Int,
    /** Где выполняется модель: `CPU` или название GPU. */
    val accelerator: String,
    /** Модель понимает изображения: у неё есть скачанный модуль зрения. */
    val supportsImages: Boolean = false,
)

/** Изображение в промпте: [id] стабилен между запросами (по нему переиспользуется кэш), [path] — файл JPEG. */
data class PromptImage(
    val id: String,
    val path: String,
)

data class PromptMessage(
    val role: MessageRole,
    val content: String,
    /** Изображения сообщения пользователя: в промпте они стоят перед текстом. */
    val images: List<PromptImage> = emptyList(),
)

data class SamplingParams(
    val temperature: Float = 1.0f,
    val topP: Float = 0.95f,
    val topK: Int = 64,
    val minP: Float = 0.0f,
    val repeatPenalty: Float = 1.0f,
    val seed: Int = -1,
)

data class GenerationRequest(
    val systemPrompt: String,
    val messages: List<PromptMessage>,
    /**
     * Образец диалога, который ставится перед историей и никогда не обрезается
     * (например, ответ на «Кто ты?» — маленькие модели лучше следуют примеру, чем правилу).
     */
    val primer: List<PromptMessage> = emptyList(),
    /** Скрытое напоминание, которое дописывается к последнему сообщению пользователя только в промпте. */
    val reminder: String = "",
    val maxTokens: Int = 2048,
    val sampling: SamplingParams = SamplingParams(),
    /**
     * Сколько токенов модель может рассуждать перед ответом (0 — без рассуждений). Рассуждения не показываются
     * и не входят в [maxTokens]; по исчерпании бюджета рассуждение закрывается и модель отвечает.
     */
    val thinkingBudget: Int = 0,
)

enum class InferenceError {
    NATIVE_LIBRARY_MISSING,
    NOT_ENOUGH_MEMORY,
    MODEL_CORRUPTED,
    MODEL_NOT_LOADED,
    CONTEXT_FULL,
    GENERATION_FAILED,

    /** В запросе есть изображения, а у модели нет модуля зрения. */
    IMAGES_NOT_SUPPORTED,

    /** Изображение не удалось прочитать или закодировать. */
    IMAGE_FAILED,

    /** Модуль зрения повреждён или не подходит к модели. */
    VISION_MODULE_CORRUPTED,
}

class InferenceException(
    val error: InferenceError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
