package ai.prism.music

import ai.prism.core.MjpegAvi
import ai.prism.domain.imagegen.ImageGenerationError
import ai.prism.domain.imagegen.ImageGenerationException
import ai.prism.domain.imagegen.ImageGenerationProgress
import ai.prism.domain.music.MusicGenerator
import ai.prism.domain.music.MusicRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.Volatile

/** Одноразовый процесс: модели и временные файлы освобождаются также при остановке и ошибке. */
class ProcessMusicGenerator(
    private val executable: () -> File?,
    private val workDir: File,
) : MusicGenerator {
    private val mutex = Mutex()
    private val cancellation = AtomicLong()
    @Volatile private var process: Process? = null

    override val isSupported: Boolean get() = executable()?.isFile == true

    override suspend fun generate(
        files: Map<String, String>,
        request: MusicRequest,
        onProgress: (ImageGenerationProgress) -> Unit,
    ): ByteArray = mutex.withLock {
        val epoch = cancellation.get()
        withContext(Dispatchers.IO) {
            val binary = executable()?.takeIf { it.isFile }
                ?: throw ImageGenerationException(ImageGenerationError.NOT_SUPPORTED, "Music worker unavailable")
            require(request.seconds in 5..60 && request.prompt.isNotBlank())
            require(listOf("model", "lm", "text_encoder", "vae").all { files[it]?.let(::File)?.isFile == true })
            if (!workDir.isDirectory && !workDir.mkdirs()) {
                throw ImageGenerationException(ImageGenerationError.FAILED, "Cannot create music directory")
            }
            val output = File.createTempFile("prism-music-", ".wav", workDir)
            val events = Channel<String>(Channel.UNLIMITED)
            var child: Process? = null
            var reader: Thread? = null
            var errors: Thread? = null
            try {
                onProgress(ImageGenerationProgress.Loading)
                child = ProcessBuilder(binary.absolutePath).directory(binary.parentFile).start()
                process = child
                if (cancellation.get() != epoch) throw cancelled()
                val running = child
                reader = Thread({
                    try {
                        running.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                            lines.forEach { events.trySend(it) }
                        }
                    } catch (_: IOException) {
                        // Завершение процесса закрывает поток.
                    } finally {
                        events.close()
                    }
                }, "prism-music-out").apply { isDaemon = true; start() }
                // stderr читается постоянно, чтобы большой лог движка не заблокировал процесс.
                errors = Thread({
                    try { running.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { } } }
                    catch (_: IOException) { /* Процесс завершён. */ }
                }, "prism-music-err").apply { isDaemon = true; start() }
                val command = buildJsonObject {
                    files.forEach { (role, path) -> put(role, path) }
                    put("output", output.absolutePath)
                    put("caption", request.prompt)
                    put("duration", request.seconds)
                    put("vocal_language", "ru")
                }
                running.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(command.toString() + "\n") }
                for (line in events) {
                    val event = try { Json.parseToJsonElement(line) as? JsonObject } catch (_: IllegalArgumentException) { null }
                    when (event?.get("event")?.jsonPrimitive?.content) {
                        "progress" -> {
                            val step = when (event["stage"]?.jsonPrimitive?.content) {
                                "composing" -> 1
                                "synthesizing" -> 2
                                else -> 3
                            }
                            onProgress(ImageGenerationProgress.Step(step, 3))
                        }
                        "done" -> {
                            if (cancellation.get() != epoch) throw cancelled()
                            val bytes = output.readBytes()
                            if (MjpegAvi.parseWav(bytes)?.pcm16?.isNotEmpty() != true) {
                                throw ImageGenerationException(ImageGenerationError.FAILED, "Invalid music output")
                            }
                            return@withContext bytes
                        }
                        "error" -> throw ImageGenerationException(
                            if (event["code"]?.jsonPrimitive?.content == "memory") ImageGenerationError.NOT_ENOUGH_MEMORY
                            else ImageGenerationError.FAILED,
                            "Music generation failed",
                        )
                    }
                }
                if (cancellation.get() != epoch) throw cancelled()
                val error = if (running.waitFor() == -1073741515) ImageGenerationError.GPU_DRIVER_MISSING else ImageGenerationError.FAILED
                throw ImageGenerationException(error, "Music worker terminated")
            } catch (e: IOException) {
                if (cancellation.get() != epoch) throw cancelled()
                throw ImageGenerationException(ImageGenerationError.FAILED, "Music I/O failed", e)
            } finally {
                child?.destroyForcibly()
                process = null
                events.cancel()
                reader?.join(1_000)
                errors?.join(1_000)
                output.delete()
            }
        }
    }

    override fun cancel() {
        cancellation.incrementAndGet()
        process?.destroyForcibly()
    }

    override suspend fun release() {
        cancel()
        mutex.withLock { Unit }
    }

    private fun cancelled() = ImageGenerationException(ImageGenerationError.CANCELLED, "Music generation stopped")
}
