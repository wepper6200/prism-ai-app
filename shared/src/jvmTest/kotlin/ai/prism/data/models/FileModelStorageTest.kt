package ai.prism.data.models

import ai.prism.domain.model.ModelFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileModelStorageTest {

    private val dir = Files.createTempDirectory("prism-storage").toFile()
    private val storage = FileModelStorage(dir)

    @Test
    fun modelDownloadedUnderOldNameIsRenamedNotDownloadedAgain() {
        dir.resolve("gemma-4-E2B-it-Q4_0.gguf").writeBytes(ByteArray(10))
        dir.resolve("encoder-bf16.safetensors.part").writeBytes(ByteArray(3))
        dir.resolve("encoder-bf16.safetensors.q4_k.gguf").writeBytes(ByteArray(4))
        val chat = ModelFile("https://example.com/a.gguf", "prism-lite-q4_0.gguf", 10, "0".repeat(64), oldFileNames = listOf("gemma-4-E2B-it-Q4_0.gguf"))
        val encoder = ModelFile("https://example.com/e.safetensors", "prism-encoder-bf16.safetensors", 20, "1".repeat(64), oldFileNames = listOf("encoder-bf16.safetensors"))

        storage.adoptOldFiles(chat)
        storage.adoptOldFiles(encoder)

        assertTrue(storage.isDownloaded(chat))
        assertFalse(dir.resolve("gemma-4-E2B-it-Q4_0.gguf").exists())
        assertEquals(3, storage.partialBytes(encoder), "незавершённая загрузка продолжается под новым именем")
        assertTrue(dir.resolve("prism-encoder-bf16.safetensors.q4_k.gguf").isFile, "сжатая копия энкодера переименована")
    }

    @Test
    fun fileAlreadyUnderNewNameIsKept() {
        dir.resolve("old.gguf").writeBytes(ByteArray(5))
        dir.resolve("new.gguf").writeBytes(ByteArray(10))
        val file = ModelFile("https://example.com/n.gguf", "new.gguf", 10, "0".repeat(64), oldFileNames = listOf("old.gguf"))

        storage.adoptOldFiles(file)

        assertEquals(10, dir.resolve("new.gguf").length())
        assertTrue(dir.resolve("old.gguf").exists())
    }
}
