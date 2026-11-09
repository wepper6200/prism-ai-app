package ai.prism.data.models

import ai.prism.domain.model.DownloadError
import ai.prism.testing.testModel
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpModelDownloaderTest {

    private val data = ByteArray(300_000) { (it % 251).toByte() }
    private val requestedRanges = mutableListOf<String?>()
    private lateinit var server: HttpServer
    private val storage = FileModelStorage(Files.createTempDirectory("prism-models").toFile())
    private val downloader = HttpModelDownloader(storage, allowedSchemes = setOf("http"))

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/model.gguf")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/model.gguf") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            requestedRanges += range
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val body = data.copyOfRange(from, data.size)
            if (range != null) {
                exchange.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
                exchange.sendResponseHeaders(206, body.size.toLong())
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
            }
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun model(sha256: String = sha256Hex(data)) = testModel().copy(
        url = "http://127.0.0.1:${server.address.port}/redirect",
        fileName = "model.gguf",
        sizeBytes = data.size.toLong(),
        sha256 = sha256,
    ).mainFile

    @Test
    fun downloadsThroughRedirectAndVerifiesChecksum() = runBlocking {
        val model = model()
        var verifying = false

        downloader.download(model, onProgress = { _, _ -> }, onVerifying = { verifying = true })

        assertTrue(verifying)
        assertTrue(storage.isDownloaded(model))
        assertContentEquals(data, storage.file(model).readBytes())
        assertFalse(storage.partFile(model).exists())
    }

    @Test
    fun resumesInterruptedDownload() = runBlocking {
        val model = model()
        storage.partFile(model).apply { parentFile.mkdirs() }.writeBytes(data.copyOfRange(0, 100_000))

        downloader.download(model, onProgress = { _, _ -> }, onVerifying = {})

        assertEquals("bytes=100000-", requestedRanges.last())
        assertContentEquals(data, storage.file(model).readBytes())
    }

    @Test
    fun corruptedDownloadIsRejectedAndRemoved() = runBlocking {
        val model = model(sha256 = "f".repeat(64))

        val error = assertFailsWith<DownloadException> {
            downloader.download(model, onProgress = { _, _ -> }, onVerifying = {})
        }

        assertEquals(DownloadError.CHECKSUM_MISMATCH, error.error)
        assertFalse(storage.partFile(model).exists())
        assertFalse(storage.isDownloaded(model))
    }

    @Test
    fun plainHttpIsRejectedByDefault() = runBlocking {
        val strict = HttpModelDownloader(storage)
        val error = assertFailsWith<DownloadException> {
            strict.download(model(), onProgress = { _, _ -> }, onVerifying = {})
        }
        assertEquals(DownloadError.SERVER, error.error)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
