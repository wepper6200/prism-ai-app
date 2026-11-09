package ai.prism.domain.inference

import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Локальный движок генерации текста. Сейчас — llama.cpp; позже через этот же интерфейс
 * можно подключить Ollama как второй бэкенд на Windows.
 */
interface InferenceEngine {
    val state: StateFlow<EngineState>

    /** Загружает модель (если загружена другая — выгружает её). Бросает [InferenceException]. */
    suspend fun load(model: CatalogModel, filePath: String)

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
)

data class PromptMessage(
    val role: MessageRole,
    val content: String,
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
    val maxTokens: Int = 2048,
    val sampling: SamplingParams = SamplingParams(),
)

enum class InferenceError {
    NATIVE_LIBRARY_MISSING,
    NOT_ENOUGH_MEMORY,
    MODEL_CORRUPTED,
    MODEL_NOT_LOADED,
    CONTEXT_FULL,
    GENERATION_FAILED,
}

class InferenceException(
    val error: InferenceError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
