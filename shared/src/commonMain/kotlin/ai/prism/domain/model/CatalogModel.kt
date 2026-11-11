package ai.prism.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Формат диалога, в котором модель была обучена. */
@Serializable
enum class ChatFormat {
    @SerialName("gemma4")
    GEMMA4,

    @SerialName("gemma3")
    GEMMA3,

    /** Без разметки диалога: системный промпт и реплики подряд (тестовые модели). */
    @SerialName("raw")
    RAW,
}

@Serializable
data class ModelLicense(
    val name: String,
    val url: String,
)

/**
 * Один скачиваемый файл модели: адрес, имя на устройстве, размер и SHA-256 (проверяются при загрузке).
 * [archive] — архив `tar.bz2`: после загрузки он распаковывается в папку рядом (голоса Piper).
 */
@Serializable
data class ModelFile(
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val archive: Boolean = false,
)

/** Что делает модель. */
@Serializable
enum class ModelKind {
    /** Языковая модель для чата. */
    @SerialName("chat")
    CHAT,

    /** Генерация изображений по описанию (stable-diffusion.cpp). */
    @SerialName("image")
    IMAGE,

    /** Эмбеддинги текста для поиска по приложенным файлам (llama.cpp). */
    @SerialName("embedding")
    EMBEDDING,

    /** Генерация видео по описанию (Wan в stable-diffusion.cpp). */
    @SerialName("video")
    VIDEO,

    /** Распознавание речи (sherpa-onnx): GigaAM, Whisper. */
    @SerialName("speech_recognition")
    SPEECH_RECOGNITION,

    /** Озвучка ответов (sherpa-onnx): голоса Piper. */
    @SerialName("speech_synthesis")
    SPEECH_SYNTHESIS,
}

/** Какой движок sherpa-onnx работает с моделью речи. */
@Serializable
enum class SpeechEngineType {
    /** Whisper: файлы `encoder`, `decoder`, `tokens`. */
    @SerialName("whisper")
    WHISPER,

    /** CTC-модель NeMo (GigaAM): файлы `model`, `tokens`. */
    @SerialName("nemo_ctc")
    NEMO_CTC,

    /** Голос Piper (VITS): архив с моделью `.onnx`, `tokens.txt` и `espeak-ng-data`. */
    @SerialName("piper")
    PIPER,
}

/** Параметры модели речи. */
@Serializable
data class SpeechModelSettings(
    val engine: SpeechEngineType,
    /** Язык распознавания (`ru`) или пусто — модель определяет язык сама (Whisper). */
    val language: String = "",
    /** Языки, которые понимает или на которых говорит модель (для подписи в каталоге). */
    val languages: List<String> = emptyList(),
)

/** Параметры генерации по умолчанию для модели видео (Wan). */
@Serializable
data class VideoModelSettings(
    /** Размер кадра: кратен 16 (Wan 2.1) или 32 (Wan 2.2 TI2V). */
    val width: Int = 832,
    val height: Int = 480,
    /** Число кадров вида 4k+1. */
    val frames: Int = 33,
    val fps: Int = 16,
    val steps: Int = 20,
    val cfg: Float = 6.0f,
    val flowShift: Float = 3.0f,
    val sampler: String = "euler",
    val scheduler: String = "",
    /** Отрицательное описание, на котором обучена модель. */
    val negativePrompt: String = "",
    val vaeTiling: Boolean = true,
    val mmap: Boolean = false,
    /** Веса в оперативной памяти, в видеопамять — по частям (видеокарты с 8–12 ГБ). */
    val offloadToCpu: Boolean = false,
    val flashAttention: Boolean = false,
    /** Модель умеет оживлять приложенное фото (Wan 2.2 TI2V). */
    val imageToVideo: Boolean = false,
)

/**
 * Как модель эмбеддингов ждёт текст: шаблоны с `{text}` (и `{title}` для фрагментов файлов)
 * и сколько токенов она читает за раз.
 */
@Serializable
data class EmbeddingSettings(
    val queryTemplate: String = "{text}",
    val documentTemplate: String = "{text}",
    val maxTokens: Int = 2048,
) {
    fun query(text: String): String = queryTemplate.replace("{text}", text)

    fun document(title: String, text: String): String =
        documentTemplate.replace("{title}", title).replace("{text}", text)
}

/**
 * Дополнительный файл модели. [role] — назначение: для stable-diffusion.cpp `t5xxl`, `clip_l`, `clip_g`, `vae`,
 * `taesd`; для моделей речи `decoder`, `tokens`, `vad` (детектор речи Silero VAD).
 */
@Serializable
data class ModelComponent(
    val role: String,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val archive: Boolean = false,
) {
    val file: ModelFile get() = ModelFile(url, fileName, sizeBytes, sha256, archive)
}

/** Параметры генерации по умолчанию для модели изображений. */
@Serializable
data class ImageModelSettings(
    val width: Int = 1024,
    val height: Int = 1024,
    val steps: Int = 4,
    /** Сила следования описанию (CFG); 1 — без отрицательного описания, у дистиллированных моделей. */
    val cfg: Float = 1.0f,
    /** Встроенная подсказка для дистиллированных моделей FLUX. */
    val guidance: Float = 3.5f,
    /** Алгоритм и расписание шагов (`euler`, `sgm_uniform`…); пусто — по умолчанию модели. */
    val sampler: String = "",
    val scheduler: String = "",
    /** Декодировать изображение по частям — меньше пиковой памяти (телефон). */
    val vaeTiling: Boolean = false,
    /** Отображать веса в память вместо копирования (меньше пиковой памяти). */
    val mmap: Boolean = false,
    /** Насколько сильно перерисовывать приложенное фото (0–1). */
    val strength: Float = 0.7f,
)

/** Модель из каталога `files/models.json`. */
@Serializable
data class CatalogModel(
    val id: String,
    val name: String,
    val family: String,
    val parameters: String,
    val quantization: String,
    /** Описание по коду языка: `ru`, позже `en`. */
    val description: Map<String, String>,
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Контекст языковой модели в токенах (у моделей изображений не используется). */
    val contextLength: Int = 0,
    val minRamMb: Int,
    val chatFormat: ChatFormat = ChatFormat.RAW,
    val license: ModelLicense,
    /** `android` и/или `desktop` — где модель рекомендуется. */
    val recommendedFor: List<String> = emptyList(),
    /** Модуль зрения (проектор mmproj): с ним модель понимает изображения. `null` — только текст. */
    val vision: ModelFile? = null,
    val kind: ModelKind = ModelKind.CHAT,
    /** Платформы, где модель доступна: `android`, `desktop`. Пусто — везде. */
    val platforms: List<String> = emptyList(),
    /**
     * Назначение основного файла: у моделей изображений `model` (целый чекпойнт) или `diffusion_model`,
     * у Whisper — `encoder`, у голосов Piper — `voice`.
     */
    val mainRole: String = "model",
    /** Остальные файлы модели изображений (текстовые энкодеры, VAE). */
    val components: List<ModelComponent> = emptyList(),
    val image: ImageModelSettings? = null,
    /** Для моделей эмбеддингов: шаблоны запроса и фрагмента. */
    val embedding: EmbeddingSettings? = null,
    /** Для моделей видео: размер, кадры, шаги. */
    val video: VideoModelSettings? = null,
    /** Для моделей речи: движок и язык. */
    val speech: SpeechModelSettings? = null,
    /** Основной файл — архив `tar.bz2` (голоса Piper). */
    val archive: Boolean = false,
) {
    /** Основной файл с весами модели. */
    val mainFile: ModelFile get() = ModelFile(url, fileName, sizeBytes, sha256, archive)

    /** Все файлы модели в порядке загрузки. */
    val files: List<ModelFile> get() = listOfNotNull(mainFile, vision) + components.map { it.file }

    fun availableOn(platform: String): Boolean = platforms.isEmpty() || platform in platforms

    val totalSizeBytes: Long get() = files.sumOf { it.sizeBytes }

    val supportsImages: Boolean get() = vision != null
}

@Serializable
data class ModelCatalogFile(
    val version: Int,
    val models: List<CatalogModel>,
)

enum class DownloadError {
    NETWORK,
    NOT_ENOUGH_SPACE,
    CHECKSUM_MISMATCH,
    STORAGE,
    SERVER,
}

sealed interface ModelStatus {
    data object NotDownloaded : ModelStatus

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelStatus

    /** Файл скачан, идёт проверка контрольной суммы. */
    data object Verifying : ModelStatus

    /** Загрузка прервана (пользователем или ошибкой); её можно продолжить с места остановки. */
    data class Paused(val downloadedBytes: Long, val totalBytes: Long, val error: DownloadError?) : ModelStatus

    data object Downloaded : ModelStatus
}

/**
 * Модель каталога на устройстве.
 *
 * [status] описывает все файлы модели вместе (основной + модуль зрения), а [chatReady] — что основной файл
 * уже скачан: модель отвечает текстом, даже если модуль зрения ещё не докачан.
 */
data class ModelEntry(
    val model: CatalogModel,
    val status: ModelStatus,
    /** Хватает ли оперативной памяти устройства по требованиям модели. */
    val fitsInMemory: Boolean,
    val chatReady: Boolean = status == ModelStatus.Downloaded,
) {
    /** Модель понимает изображения: модуль зрения есть в каталоге и скачан. */
    val visionReady: Boolean get() = model.supportsImages && status == ModelStatus.Downloaded

    /** Модель уже работает с текстом, но модуль зрения ещё не скачан. */
    val visionMissing: Boolean get() = model.supportsImages && chatReady && status != ModelStatus.Downloaded
}
