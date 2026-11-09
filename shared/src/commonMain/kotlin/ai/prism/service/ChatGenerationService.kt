package ai.prism.service

import ai.prism.domain.imagegen.ImageGenerationError
import ai.prism.domain.imagegen.ImageGenerationException
import ai.prism.domain.imagegen.ImageGenerationProgress
import ai.prism.domain.imagegen.ImageGenerator
import ai.prism.domain.imagegen.ImageRequest
import ai.prism.domain.inference.GenerationRequest
import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.inference.InferenceError
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.inference.PromptImage
import ai.prism.domain.inference.PromptMessage
import ai.prism.domain.inference.SamplingParams
import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.ChatMessage
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.ImageAttachment
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.model.ModelKind
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.repository.ChatRepository
import ai.prism.domain.repository.ImageException
import ai.prism.domain.repository.ImageStore
import ai.prism.domain.repository.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/** Личность ассистента: системный промпт и образец ответа на вопрос «кто ты?». */
data class AssistantPersona(
    val systemPrompt: String,
    val primer: List<PromptMessage>,
    /** Короткое напоминание о личности в конце последнего сообщения пользователя. */
    val reminder: String = "",
    /** Инструкция для перевода описания картинки на английский (генераторы лучше понимают английский). */
    val imagePromptInstruction: String = "",
)

/** Личность Prism AI на языке интерфейса. */
fun interface PersonaProvider {
    suspend fun persona(): AssistantPersona
}

/** Этап создания изображения. */
enum class DrawingStage { TRANSLATING, LOADING, DRAWING }

/** Ход создания изображения: этап и шаг из скольких. */
data class DrawingProgress(
    val stage: DrawingStage,
    val step: Int = 0,
    val steps: Int = 0,
)

/**
 * Ответ, который генерируется прямо сейчас. [messageId] пуст, пока сообщение ещё не создано.
 * [drawing] — не `null`, пока модель рисует изображение.
 */
data class ActiveGeneration(
    val chatId: String,
    val messageId: String,
    val text: String,
    val drawing: DrawingProgress? = null,
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
    private val imageGenerator: ImageGenerator,
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

    /**
     * Просит модель изображений нарисовать картинку по описанию [text].
     * `false` — уже идёт другая генерация или описание пустое.
     */
    fun draw(chatId: String, text: String): Boolean {
        val prompt = text.trim()
        if (prompt.isEmpty() || !reserve(chatId)) return false
        scope.launch {
            try {
                chats.addMessage(chatId, MessageRole.USER, prompt, MessageStatus.COMPLETE, modelId = null)
                chats.setTitleIfEmpty(chatId, titleFrom(prompt))
                drawReply(chatId, prompt)
            } finally {
                mutableActive.value = null
            }
        }
        return true
    }

    /** Повторяет последний неудачный или остановленный ответ (текстовый или рисунок). */
    fun retry(chatId: String): Boolean {
        if (!reserve(chatId)) return false
        scope.launch {
            try {
                val messages = chats.messagesSnapshot(chatId)
                val last = messages.lastOrNull()
                val wasDrawing = last != null && last.role == MessageRole.ASSISTANT &&
                    last.modelId?.let(models::catalogModel)?.kind == ModelKind.IMAGE
                if (last != null && last.role == MessageRole.ASSISTANT && last.status != MessageStatus.COMPLETE) {
                    chats.deleteMessage(last.id)
                }
                val prompt = messages.lastOrNull { it.role == MessageRole.USER }?.content
                if (wasDrawing && !prompt.isNullOrBlank()) drawReply(chatId, prompt) else generateReply(chatId)
            } finally {
                mutableActive.value = null
            }
        }
        return true
    }

    fun stop() {
        stopRequested = true
        engine.cancel()
        imageGenerator.cancel()
    }

    /** На этой платформе есть программа генерации изображений. */
    val canGenerateImages: Boolean get() = imageGenerator.isSupported

    /** Скачанная модель изображений для этой платформы (каталог уже отфильтрован по платформе). */
    fun imageModel(): CatalogModel? = models.models.value
        .firstOrNull { it.model.kind == ModelKind.IMAGE && it.status == ModelStatus.Downloaded }
        ?.model

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
            // Чат и рисование не держат модели в памяти одновременно: телефону не хватит памяти на обе.
            imageGenerator.release()
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

    private suspend fun drawReply(chatId: String, prompt: String) {
        val model = imageModel()
        val reply = chats.addMessage(chatId, MessageRole.ASSISTANT, "", MessageStatus.GENERATING, model?.id)
        fun progress(drawing: DrawingProgress) {
            mutableActive.value = ActiveGeneration(chatId, reply.id, "", drawing)
        }
        progress(DrawingProgress(DrawingStage.TRANSLATING))
        val files = model?.let { models.imageModelFiles(it.id) }
        val settings = model?.image
        if (model == null || files == null || settings == null || !imageGenerator.isSupported) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.NO_IMAGE_MODEL)
            return
        }
        try {
            val englishPrompt = translateForImage(prompt)
            engine.unload()
            if (stopRequested) {
                chats.updateMessage(reply.id, "", MessageStatus.STOPPED, null)
                return
            }
            val image = imageGenerator.generate(
                model = model,
                files = files,
                request = ImageRequest(
                    prompt = englishPrompt,
                    width = settings.width,
                    height = settings.height,
                    steps = settings.steps,
                    cfg = settings.cfg,
                    guidance = settings.guidance,
                    sampler = settings.sampler,
                    scheduler = settings.scheduler,
                    vaeTiling = settings.vaeTiling,
                    mmap = settings.mmap,
                ),
                onProgress = { event ->
                    when (event) {
                        ImageGenerationProgress.Loading -> progress(DrawingProgress(DrawingStage.LOADING))
                        is ImageGenerationProgress.Step -> progress(DrawingProgress(DrawingStage.DRAWING, event.step, event.steps))
                    }
                },
            )
            val attachment = images.save(image.png)
            chats.attachImages(chatId, reply.id, listOf(attachment))
            chats.updateMessage(reply.id, "", MessageStatus.COMPLETE, null)
        } catch (e: ImageGenerationException) {
            if (e.error == ImageGenerationError.CANCELLED || stopRequested) {
                chats.updateMessage(reply.id, "", MessageStatus.STOPPED, null)
            } else {
                chats.updateMessage(reply.id, "", MessageStatus.FAILED, e.error.toGenerationError())
            }
        } catch (e: ImageException) {
            chats.updateMessage(reply.id, "", MessageStatus.FAILED, GenerationError.IMAGE_GENERATION_FAILED)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { chats.updateMessage(reply.id, "", MessageStatus.STOPPED, null) }
            throw e
        }
    }

    /**
     * Переводит описание на английский активной моделью чата: генераторы изображений обучены на английских подписях.
     * Описание без кириллицы, без скачанной модели чата или при ошибке перевода передаётся как есть.
     */
    private suspend fun translateForImage(prompt: String): String {
        if (prompt.none { it in '\u0400'..'\u04FF' }) return prompt
        val model = models.activeModelId.value?.let(models::catalogModel)?.takeIf { it.kind == ModelKind.CHAT } ?: return prompt
        val path = models.downloadedFilePath(model.id) ?: return prompt
        val instruction = personaProvider.persona().imagePromptInstruction.ifBlank { return prompt }
        return try {
            engine.load(model, path, null)
            if (stopRequested) return prompt
            val translated = engine.generate(
                GenerationRequest(
                    systemPrompt = instruction,
                    messages = listOf(PromptMessage(MessageRole.USER, prompt)),
                    maxTokens = TRANSLATION_MAX_TOKENS,
                    sampling = SamplingParams(temperature = 0f),
                ),
            ).toList().joinToString("").trim().trim('"', '«', '»').trim()
            translated.ifBlank { prompt }
        } catch (e: InferenceException) {
            prompt
        }
    }

    private fun ImageGenerationError.toGenerationError(): GenerationError = when (this) {
        ImageGenerationError.NOT_SUPPORTED -> GenerationError.NO_IMAGE_MODEL
        ImageGenerationError.GPU_DRIVER_MISSING -> GenerationError.GPU_DRIVER_MISSING
        ImageGenerationError.NOT_ENOUGH_MEMORY -> GenerationError.NOT_ENOUGH_MEMORY
        ImageGenerationError.MODEL_CORRUPTED -> GenerationError.MODEL_CORRUPTED
        ImageGenerationError.CANCELLED, ImageGenerationError.FAILED -> GenerationError.IMAGE_GENERATION_FAILED
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
        private const val TRANSLATION_MAX_TOKENS = 400

        /** Заголовок диалога — начало первой строки сообщения. */
        fun titleFrom(text: String): String {
            val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                .replace(Regex("\\s+"), " ").trim()
            return if (line.length <= TITLE_LENGTH) line else line.take(TITLE_LENGTH).trimEnd() + "…"
        }
    }
}
