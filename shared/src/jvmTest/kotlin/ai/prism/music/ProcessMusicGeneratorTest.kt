package ai.prism.music

import ai.prism.domain.imagegen.ImageGenerationError
import ai.prism.domain.imagegen.ImageGenerationException
import ai.prism.domain.imagegen.ImageGenerationProgress
import ai.prism.domain.music.MusicRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeFalse
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProcessMusicGeneratorTest {
    @Test
    fun stoppedProcessCanBeReusedAndTemporaryOutputsAreRemoved() = runBlocking {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val dir = Files.createTempDirectory("prism-music-process").toFile()
        val script = File(dir, "worker")
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
        script.writeText("#!/bin/sh\nexec ${quote(java)} -cp ${quote(classpath)} ai.prism.music.FakeMusicWorker\n")
        assertTrue(script.setExecutable(true))
        val model = File(dir, "weights.gguf").apply { writeText("test") }
        val files = listOf("model", "lm", "text_encoder", "vae").associateWith { model.absolutePath }
        val out = File(dir, "output")
        val generator = ProcessMusicGenerator({ script }, out)
        try {
            val started = CompletableDeferred<Unit>()
            val failure = async(Dispatchers.Default) {
                assertFailsWith<ImageGenerationException> {
                    generator.generate(files, MusicRequest("wait")) { if (it is ImageGenerationProgress.Step) started.complete(Unit) }
                }
            }
            withTimeout(15_000) { started.await() }
            generator.cancel()
            assertEquals(ImageGenerationError.CANCELLED, withTimeout(15_000) { failure.await() }.error)
            val audio = generator.generate(files, MusicRequest("piano")) {}
            assertTrue(audio.size > 44)
            assertTrue(out.listFiles().orEmpty().isEmpty())
            val corrupt = assertFailsWith<ImageGenerationException> { generator.generate(files, MusicRequest("corrupt")) {} }
            assertEquals(ImageGenerationError.FAILED, corrupt.error)
            assertTrue(out.listFiles().orEmpty().isEmpty())
        } finally {
            generator.release()
            dir.deleteRecursively()
        }
    }
}
