package ai.prism.imagegen

import ai.prism.domain.imagegen.GeneratedImage
import ai.prism.domain.imagegen.ImageGenerationError
import ai.prism.domain.imagegen.ImageGenerationException
import ai.prism.domain.imagegen.ImageGenerationProgress
import ai.prism.domain.imagegen.ImageGenerator
import ai.prism.domain.imagegen.ImageRequest
import ai.prism.domain.model.CatalogModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [ImageGenerator] поверх программы `prism_imagegen` (stable-diffusion.cpp), запущенной отдельным процессом.
 * Обмен — строками JSON через stdin/stdout (см. native/imagegen/prism_imagegen.cpp).
 *
 * @param executable программа генерации или `null`, если на этой платформе её нет.
 * @param workDir каталог для временных файлов изображений.
 */
class ProcessImageGenerator(
    private val executable: () -> File?,
    private val workDir: File,
    private val threads: Int,
) : ImageGenerator {

    override val isSupported: Boolean get() = executable()?.isFile == true

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var events: Channel<JsonObject>? = null
    private var loadedKey: String? = null
    private val stderrTail = StringBuffer()

    @Volatile
    private var cancelRequested = false

    override suspend fun generate(
        model: CatalogModel,
        files: Map<String, String>,
        request: ImageRequest,
        onProgress: (ImageGenerationProgress) -> Unit,
    ): GeneratedImage = mutex.withLock {
        withContext(Dispatchers.IO) {
            cancelRequested = false
            try {
                ensureLoaded(model, files, request, onProgress)
                if (cancelRequested) throw cancelled()
                runGeneration(request, onProgress)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { stopProcess() }
                throw e
            }
        }
    }

    override fun cancel() {
        cancelRequested = true
        val writer = input ?: return
        try {
            synchronized(writer) {
                writer.write("""{"cmd":"cancel"}""")
                writer.newLine()
                writer.flush()
            }
        } catch (e: IOException) {
            // Процесс уже завершился — отменять нечего.
        }
    }

    override suspend fun release() {
        mutex.withLock { withContext(Dispatchers.IO) { stopProcess() } }
    }

    private suspend fun ensureLoaded(
        model: CatalogModel,
        files: Map<String, String>,
        request: ImageRequest,
        onProgress: (ImageGenerationProgress) -> Unit,
    ) {
        val key = model.id + files.toSortedMap().toString() + request.vaeTiling + request.mmap
        if (process?.isAlive == true && loadedKey == key) return
        stopProcess()
        onProgress(ImageGenerationProgress.Loading)
        startProcess()
        send(
            buildJsonObject {
                put("cmd", "load")
                files.forEach { (role, path) -> put(role, path) }
                put("threads", threads)
                put("vae_tiling", request.vaeTiling)
                put("mmap", request.mmap)
            },
        )
        while (true) {
            val event = nextEvent()
            when (event.string("event")) {
                "loaded" -> {
                    loadedKey = key
                    return
                }
                "error" -> {
                    stopProcess()
                    throw failure(event, loading = true)
                }
            }
        }
    }

    private suspend fun runGeneration(request: ImageRequest, onProgress: (ImageGenerationProgress) -> Unit): GeneratedImage {
        workDir.mkdirs()
        val output = File.createTempFile("prism-image", ".png", workDir)
        try {
            send(
                buildJsonObject {
                    put("cmd", "generate")
                    put("prompt", request.prompt)
                    put("negative", request.negativePrompt)
                    put("width", request.width)
                    put("height", request.height)
                    put("steps", request.steps)
                    put("cfg", request.cfg)
                    put("guidance", request.guidance)
                    put("seed", request.seed)
                    put("sampler", request.sampler)
                    put("scheduler", request.scheduler)
                    put("output", output.absolutePath)
                },
            )
            onProgress(ImageGenerationProgress.Step(0, request.steps))
            while (true) {
                val event = nextEvent()
                when (event.string("event")) {
                    "progress" -> {
                        val step = event.int("step") ?: continue
                        val steps = event.int("steps") ?: request.steps
                        onProgress(ImageGenerationProgress.Step(step.coerceAtMost(steps), steps))
                    }
                    "done" -> {
                        val png = output.readBytes()
                        return GeneratedImage(
                            png = png,
                            width = event.int("width") ?: request.width,
                            height = event.int("height") ?: request.height,
                            seed = event["seed"]?.let { (it as? JsonPrimitive)?.longOrNull } ?: request.seed,
                        )
                    }
                    "error" -> throw failure(event, loading = false)
                }
            }
        } finally {
            output.delete()
        }
    }

    private fun startProcess() {
        val exe = executable()?.takeIf { it.isFile }
            ?: throw ImageGenerationException(ImageGenerationError.NOT_SUPPORTED, "Нет программы генерации изображений")
        stderrTail.setLength(0)
        val started = try {
            ProcessBuilder(exe.absolutePath)
                .directory(exe.parentFile)
                .start()
        } catch (e: IOException) {
            throw ImageGenerationException(ImageGenerationError.NOT_SUPPORTED, "Не удалось запустить генерацию: ${e.message}", e)
        }
        val channel = Channel<JsonObject>(Channel.UNLIMITED)
        Thread({
            try {
                started.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    val event = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    if (event != null) channel.trySend(event)
                }
            } catch (e: IOException) {
                // Поток закрыт вместе с процессом.
            } finally {
                channel.close()
            }
        }, "prism-imagegen-out").apply { isDaemon = true }.start()
        Thread({
            try {
                started.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    stderrTail.append(line).append('\n')
                    if (stderrTail.length > STDERR_LIMIT) stderrTail.delete(0, stderrTail.length - STDERR_LIMIT)
                }
            } catch (e: IOException) {
                // Поток закрыт вместе с процессом.
            }
        }, "prism-imagegen-err").apply { isDaemon = true }.start()
        process = started
        input = started.outputStream.bufferedWriter(Charsets.UTF_8)
        events = channel
    }

    private suspend fun nextEvent(): JsonObject {
        val channel = events ?: throw died()
        val event = channel.receiveCatching().getOrNull()
        if (event == null) {
            val error = died()
            stopProcess()
            throw error
        }
        return event
    }

    private fun send(command: JsonObject) {
        val writer = input ?: throw died()
        try {
            synchronized(writer) {
                writer.write(command.toString())
                writer.newLine()
                writer.flush()
            }
        } catch (e: IOException) {
            throw died()
        }
    }

    private suspend fun stopProcess() {
        val current = process ?: return
        process = null
        loadedKey = null
        events = null
        val writer = input
        input = null
        try {
            writer?.let {
                synchronized(it) {
                    it.write("""{"cmd":"quit"}""")
                    it.newLine()
                    it.flush()
                    it.close()
                }
            }
        } catch (e: IOException) {
            // Процесс уже завершился.
        }
        val exited = withTimeoutOrNull(QUIT_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { current.waitFor(QUIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        } == true
        if (!exited) current.destroyForcibly()
    }

    private fun cancelled() = ImageGenerationException(ImageGenerationError.CANCELLED, "Генерация остановлена")

    /** Процесс завершился сам: нехватка памяти (его убила система), нет драйвера или сбой. */
    private fun died(): ImageGenerationException {
        val exit = process?.let { if (it.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) it.exitValue() else null }
        val log = stderrTail.toString()
        return when {
            cancelRequested -> cancelled()
            exit == WINDOWS_DLL_NOT_FOUND || exit == WINDOWS_ENTRY_POINT_NOT_FOUND ->
                ImageGenerationException(ImageGenerationError.GPU_DRIVER_MISSING, "Нет драйвера видеокарты с поддержкой Vulkan")
            exit == SIGKILL_EXIT || log.contains("out of memory", ignoreCase = true) ->
                ImageGenerationException(ImageGenerationError.NOT_ENOUGH_MEMORY, "Не хватило памяти для генерации")
            else -> ImageGenerationException(
                ImageGenerationError.FAILED,
                "Генерация прервалась (код $exit): ${log.takeLast(LOG_IN_MESSAGE)}",
            )
        }
    }

    private fun failure(event: JsonObject, loading: Boolean): ImageGenerationException {
        val message = event.string("message").orEmpty()
        val error = when (event.string("code")) {
            "out_of_memory" -> ImageGenerationError.NOT_ENOUGH_MEMORY
            "cancelled" -> ImageGenerationError.CANCELLED
            "load_failed" -> if (loading) ImageGenerationError.MODEL_CORRUPTED else ImageGenerationError.FAILED
            else -> if (cancelRequested) ImageGenerationError.CANCELLED else ImageGenerationError.FAILED
        }
        return ImageGenerationException(error, message.ifBlank { stderrTail.toString().takeLast(LOG_IN_MESSAGE) })
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private companion object {
        const val STDERR_LIMIT = 8 * 1024
        const val LOG_IN_MESSAGE = 400
        const val QUIT_TIMEOUT_MS = 5_000L
        const val EXIT_WAIT_MS = 2_000L
        const val SIGKILL_EXIT = 137

        /** STATUS_DLL_NOT_FOUND (0xC0000135): нет vulkan-1.dll — не установлен драйвер видеокарты. */
        const val WINDOWS_DLL_NOT_FOUND = -1073741515

        /** STATUS_ENTRY_POINT_NOT_FOUND (0xC0000139): слишком старый загрузчик Vulkan. */
        const val WINDOWS_ENTRY_POINT_NOT_FOUND = -1073741511
    }
}
