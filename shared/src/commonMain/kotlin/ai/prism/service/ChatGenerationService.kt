package ai.prism.service

import ai.prism.domain.inference.GenerationRequest
import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.inference.InferenceError
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.inference.PromptImage
import ai.prism.domain.inference.PromptMessage
import ai.prism.domain.model.ChatMessage
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.ImageAttachment
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.repository.ImageStore
import ai.prism.domain.repository.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/** Личность ассистента: системный промпт и образец ответа на вопрос «кто ты?». */
data class AssistantPersona(
    val systemPrompt: String,
    val primer: List<PromptMessage>,
    /** Короткое напоминание о личности в конце последнего сообщения пользователя. */
    val reminder: String = "",
)

/** Личность Prism AI на языке интерфейса. */
fun interface PersonaProvider {
    suspend fun persona(): AssistantPersona
}

/** Ответ, который генерируется прямо сейчас. [messageId] пуст, пока сообщение ещё не создано. */
data class ActiveGeneration(
    val chatId: String,
    val messageId: String,
    val text: String,
)

/**
 * Генерация ответов в области приложения: переживает уход с экрана чата и пересоздание Activity.
 * Одновременно идёт не больше одной генерации.
 */
class ChatGenerationService(
    private val engine: InferenceEngine,
    private val chats: ChatRepository,
    private val models: ModelRepository,
    private val personaProvider: PersonaProvider,
    private val images: ImageStore,
    private val scope: CoroutineScope,
) {
    private val mutableActive = MutableStateFlow<ActiveGeneration?>(null)
    val active: StateFlow<ActiveGeneration?> = mutableActive.asStateFlow()

    @Volatile
    private var stopRequested = false

    init {
        scope.launch {
            chats.markInterruptedGenerations()
            // Изображения брошенных черновиков, на которые не ссылается ни одно сообщение.
            images.deleteOrphans(chats.referencedImageFiles(), ORPHAN_IMAGE_AGE_MS)
        }
    }

    /**
     * Отправляет сообщение (текст и/или изображения) и запускает ответ.
     * `false` — уже идёт другая генерация или сообщение пустое.
     */
    fun send(chatId: String, text: String, attachments: List<ImageAttachment> = emptyList()): Boolean {
        val content = text.trim()
        if ((content.isEmpty() && attachments.isEmpty()) || !reserve(chatId)) return false
        scope.launch {
            try {
                chats.addMessage(chatId, MessageRole.USER, content, MessageStatus.COMPLETE, modelId = null, images = attachments)
                if (content.isNotEmpty()) chats.setTitleIfEmpty(chatId, titleFrom(content))
                generateReply(chatId)
            } finally {
                mutableActive.value = null
            }
        }
        return true
    }

    /** Повторяет последний неудачный или остановленный ответ. */
    fun retry(chatId: String): Boolean {
        if (!reserve(chatId)) return false
        scope.launch {
            try {
                val last = chats.messagesSnapshot(chatId).lastOrNull()
                if (last != null && last.role == MessageRole.ASSISTANT && last.status != MessageStatus.COMPLETE) {
                    chats.deleteMessage(last.id)
                }
                generateReply(chatId)
            } finally {
                mutableActive.value = null
            }
        }
        return true
    }

    fun stop() {
        stopRequested = true
        engine.cancel()
    }

    private fun reserve(chatId: String): Boolean {
        if (!mutableActive.compareAndSet(null, ActiveGeneration(chatId = chatId, messageId = "", text = ""))) return false
        stopRequested = false
        return true
    }

    private suspend fun generateReply(chatId: String) {
        val messages = chats.messagesSnapshot(chatId)
        val modelId = models.activeModelId.value
        val reply = chats.addMessage(chatId, MessageRole.ASSISTANT, "", MessageStatus.GENERATING, modelId)
        mutableActive.value = ActiveGeneration(chatId, reply.id, "")

        val model = modelId?.let(models::catalogModel)
        if (model == null) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.NO_MODEL)
            return
        }
        val path = models.downloadedFilePath(model.id)
        if (path == null) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.MODEL_NOT_DOWNLOADED)
            return
        }
        // Без модуля зрения изображения из истории не передаются; на новое изображение ответить нельзя.
        val projectorPath = models.downloadedProjectorPath(model.id)
        val lastUser = messages.lastOrNull { it.role == MessageRole.USER }
        if (projectorPath == null && lastUser != null && lastUser.images.isNotEmpty()) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.IMAGES_NOT_SUPPORTED)
            return
        }
        val history = promptHistory(messages, withImages = projectorPath != null)

        val text = StringBuilder()
        try {
            engine.load(model, path, projectorPath)
            if (!stopRequested) {
                val persona = personaProvider.persona()
                val request = GenerationRequest(
                    systemPrompt = persona.systemPrompt,
                    messages = history,
                    primer = persona.primer,
                    reminder = persona.reminder,
                )
                engine.generate(request).collect { piece ->
                    text.append(piece)
                    mutableActive.value = ActiveGeneration(chatId, reply.id, text.toString())
                }
            }
            val status = if (stopRequested) MessageStatus.STOPPED else MessageStatus.COMPLETE
            chats.updateMessage(reply.id, text.toString().trim(), status, null)
        } catch (e: InferenceException) {
            chats.updateMessage(reply.id, text.toString().trim(), MessageStatus.FAILED, e.error.toGenerationError())
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                chats.updateMessage(reply.id, text.toString().trim(), MessageStatus.STOPPED, null)
            }
            throw e
        }
    }

    /** Завершённые реплики диалога для промпта; изображения — только если модель их понимает. */
    private fun promptHistory(messages: List<ChatMessage>, withImages: Boolean): List<PromptMessage> = messages
        .filter { it.status == MessageStatus.COMPLETE || it.status == MessageStatus.STOPPED }
        .map { message ->
            val attached = if (withImages && message.role == MessageRole.USER) message.images else emptyList()
            PromptMessage(
                role = message.role,
                content = message.content,
                images = attached.map { PromptImage(id = it.id, path = images.path(it.fileName)) },
            )
        }
        .filter { it.content.isNotBlank() || it.images.isNotEmpty() }

    private fun InferenceError.toGenerationError(): GenerationError = when (this) {
        InferenceError.NATIVE_LIBRARY_MISSING -> GenerationError.NATIVE_LIBRARY_MISSING
        InferenceError.NOT_ENOUGH_MEMORY -> GenerationError.NOT_ENOUGH_MEMORY
        InferenceError.MODEL_CORRUPTED -> GenerationError.MODEL_CORRUPTED
        InferenceError.CONTEXT_FULL -> GenerationError.CONTEXT_FULL
        InferenceError.IMAGES_NOT_SUPPORTED -> GenerationError.IMAGES_NOT_SUPPORTED
        InferenceError.IMAGE_FAILED -> GenerationError.IMAGE_FAILED
        InferenceError.VISION_MODULE_CORRUPTED -> GenerationError.VISION_MODULE_CORRUPTED
        InferenceError.MODEL_NOT_LOADED, InferenceError.GENERATION_FAILED -> GenerationError.UNKNOWN
    }

    internal companion object {
        private const val TITLE_LENGTH = 48
        private const val ORPHAN_IMAGE_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** Заголовок диалога — начало первой строки сообщения. */
        fun titleFrom(text: String): String {
            val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                .replace(Regex("\\s+"), " ").trim()
            return if (line.length <= TITLE_LENGTH) line else line.take(TITLE_LENGTH).trimEnd() + "…"
        }
    }
}
