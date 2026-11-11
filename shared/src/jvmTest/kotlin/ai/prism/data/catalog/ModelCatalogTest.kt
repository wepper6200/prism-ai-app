package ai.prism.data.catalog

import ai.prism.domain.model.ChatFormat
import ai.prism.domain.model.ModelKind
import ai.prism.domain.model.SpeechEngineType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Проверяет встроенный каталог `files/models.json`. */
class ModelCatalogTest {

    private val models = runBlocking { ModelCatalog().load() }

    @Test
    fun catalogIsValid() {
        assertTrue(models.isNotEmpty())
        assertEquals(models.size, models.map { it.id }.toSet().size, "id моделей должны быть уникальны")
        val files = models.flatMap { it.files }
        // Один файл может принадлежать нескольким моделям (детектор речи у моделей распознавания),
        // но под одним именем всегда лежит один и тот же файл.
        files.groupBy { it.fileName }.forEach { (name, same) ->
            assertEquals(1, same.toSet().size, "под именем $name разные файлы")
        }
        files.forEach { file ->
            // Компоненты моделей изображений хранятся под своими именами (у разных репозиториев они совпадают).
            assertTrue(file.url.startsWith("https://") && file.url.substringAfterLast('.') == file.fileName.substringAfterLast('.'), file.fileName)
            assertTrue(file.sha256.matches(Regex("[0-9a-f]{64}")) && file.sizeBytes > 0, file.fileName)
        }
        models.forEach { model ->
            assertTrue(model.url.startsWith("https://") && model.url.substringAfterLast('.') == model.fileName.substringAfterLast('.'), model.id)
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")), "sha256 ${model.id}")
            assertTrue(model.sizeBytes > 0 && model.minRamMb > 0, model.id)
            if (model.kind == ModelKind.CHAT) assertTrue(model.contextLength >= 2048, model.id)
            assertTrue(!model.description["ru"].isNullOrBlank(), "описание ${model.id}")
            assertTrue(model.license.url.startsWith("https://"), "лицензия ${model.id}")
        }
    }

    /** Все модели Gemma 4 понимают изображения: у каждой есть модуль зрения из того же репозитория. */
    @Test
    fun gemma4ModelsHaveVisionModules() {
        models.filter { it.chatFormat == ChatFormat.GEMMA4 }.forEach { model ->
            val vision = model.vision
            assertTrue(vision != null && vision.fileName.startsWith("mmproj-"), model.id)
            assertEquals(model.url.substringBeforeLast('/'), vision.url.substringBeforeLast('/'), model.id)
        }
    }

    /** На каждой платформе есть ровно одна модель рисования со всеми нужными stable-diffusion.cpp файлами. */
    @Test
    fun imageModelsCoverBothPlatforms() {
        for (platform in listOf("android", "desktop")) {
            val image = models.filter { it.kind == ModelKind.IMAGE && it.availableOn(platform) }
            assertEquals(1, image.size, platform)
            val model = image.single()
            assertTrue(model.image != null && model.mainRole in setOf("model", "diffusion_model"), model.id)
            assertTrue(model.components.all { it.role in setOf("t5xxl", "clip_l", "clip_g", "vae", "taesd") }, model.id)
        }
        val flux = models.single { it.id.startsWith("flux1-schnell") }
        assertEquals(setOf("t5xxl", "clip_l", "vae"), flux.components.map { it.role }.toSet())
    }

    @Test
    fun hasGemma4ModelForPhones() {
        assertTrue(models.any { it.chatFormat == ChatFormat.GEMMA4 && "android" in it.recommendedFor && it.minRamMb <= 8192 })
    }

    @Test
    fun hasEmbeddingModelForFileSearch() {
        val embedding = models.filter { it.kind == ModelKind.EMBEDDING }
        assertEquals(1, embedding.size, "нужна ровно одна модель поиска по файлам")
        val model = embedding.single()
        assertTrue(model.platforms.isEmpty(), "поиск по файлам нужен на обеих платформах")
        val settings = assertNotNull(model.embedding)
        assertTrue("{text}" in settings.queryTemplate && "{text}" in settings.documentTemplate)
        assertTrue(model.url.startsWith("https://huggingface.co/ggml-org/"))
    }

    /**
     * Видео: Wan (umt5 и VAE, кадры 4k+1) на обеих платформах — Wan 2.2 TI2V 5B на Desktop, Wan 2.1 1.3B на Android;
     * на Desktop ещё LTX-2.5 со звуком (Gemma 4, VAE видео и звука, кадры 8k+1). Длительности — в пределах куска
     * или продолжением, если модель оживляет кадр.
     */
    @Test
    fun videoModelsCoverBothPlatforms() {
        for (platform in listOf("android", "desktop")) {
            val wan = models.filter { it.kind == ModelKind.VIDEO && it.availableOn(platform) && it.family.startsWith("Wan") }
            assertEquals(1, wan.size, platform)
        }
        models.filter { it.kind == ModelKind.VIDEO }.forEach { model ->
            val settings = assertNotNull(model.video, model.id)
            assertEquals("diffusion_model", model.mainRole)
            val roles = model.components.map { it.role }.toSet()
            assertEquals(if (settings.audio) setOf("llm", "vae", "audio_vae") else setOf("t5xxl", "vae"), roles, model.id)
            assertEquals(1, settings.frames % settings.frameStep, "кадров должно быть ${settings.frameStep}k+1: ${model.id}")
            assertEquals(1, settings.maxFrames % settings.frameStep, "maxFrames ${model.id}")
            assertTrue(settings.width % 16 == 0 && settings.height % 16 == 0, model.id)
            assertTrue(settings.durations.isNotEmpty() && settings.defaultSeconds in settings.availableDurations, model.id)
            if (!settings.imageToVideo) {
                assertTrue(settings.durations.all { it * settings.fps + 1 <= settings.maxFrames }, "без продолжения: ${model.id}")
            }
        }
        assertTrue(models.single { it.id.startsWith("wan2.2-ti2v") }.video!!.imageToVideo)
    }

    /** Голос: на обеих платформах есть распознавание речи с детектором речи и голос Piper. */
    @Test
    fun speechModelsCoverBothPlatforms() {
        for (platform in listOf("android", "desktop")) {
            val recognizers = models.filter { it.kind == ModelKind.SPEECH_RECOGNITION && it.availableOn(platform) }
            val voices = models.filter { it.kind == ModelKind.SPEECH_SYNTHESIS && it.availableOn(platform) }
            assertTrue(recognizers.any { platform in it.recommendedFor }, "распознавание для $platform")
            assertTrue(voices.any { platform in it.recommendedFor }, "голос для $platform")
        }
        models.filter { it.kind == ModelKind.SPEECH_RECOGNITION }.forEach { model ->
            val speech = assertNotNull(model.speech, model.id)
            val roles = setOf(model.mainRole) + model.components.map { it.role }
            val expected = when (speech.engine) {
                SpeechEngineType.WHISPER -> setOf("encoder", "decoder", "tokens", "vad")
                SpeechEngineType.NEMO_CTC -> setOf("model", "tokens", "vad")
                SpeechEngineType.PIPER, SpeechEngineType.SUPERTONIC -> emptySet()
            }
            assertEquals(expected, roles, model.id)
        }
        models.filter { it.kind == ModelKind.SPEECH_SYNTHESIS }.forEach { model ->
            assertTrue(model.speech?.engine in setOf(SpeechEngineType.PIPER, SpeechEngineType.SUPERTONIC), model.id)
            assertTrue(model.archive && model.fileName.endsWith(".tar.bz2") && model.mainRole == "voice", model.id)
        }
        // По умолчанию предлагается Supertonic 3: у Piper хуже ударения и английские слова.
        assertEquals(SpeechEngineType.SUPERTONIC, models.first { it.kind == ModelKind.SPEECH_SYNTHESIS }.speech?.engine)
        // Русская речь распознаётся GigaAM: он первым предлагается для загрузки.
        assertEquals("GigaAM", models.first { it.kind == ModelKind.SPEECH_RECOGNITION }.family)
    }
}
