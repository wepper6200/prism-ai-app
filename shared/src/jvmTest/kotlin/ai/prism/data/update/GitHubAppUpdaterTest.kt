package ai.prism.data.update

import ai.prism.domain.update.AppVersion
import ai.prism.domain.update.AppUpdater
import ai.prism.domain.update.UpdateError
import ai.prism.domain.update.UpdateException
import ai.prism.service.update.UpdateService
import ai.prism.service.update.UpdateState
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
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

class GitHubAppUpdaterTest {

    private lateinit var server: HttpServer
    private lateinit var dir: File
    private val apk = ByteArray(300_000) { (it % 251).toByte() }
    private var withDigest = true

    private val base get() = "http://127.0.0.1:${server.address.port}"

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @BeforeTest
    fun start() {
        dir = Files.createTempDirectory("prism-update").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/releases/latest") { exchange ->
            val digest = if (withDigest) ""","digest":"sha256:${sha256(apk)}"""" else ""
            val body = """
                {"tag_name":"v0.9.0","body":"Что нового","html_url":"https://example.org/r","assets":[
                  {"name":"PrismAI-0.9.0-windows-x64.zip","browser_download_url":"$base/zip","size":10},
                  {"name":"PrismAI-0.9.0-android-arm64.apk","browser_download_url":"$base/apk","size":${apk.size}$digest},
                  {"name":"SHA256SUMS.txt","browser_download_url":"$base/sums","size":100}]}
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/sums") { exchange ->
            val body = "${sha256(apk)}  PrismAI-0.9.0-android-arm64.apk\nabc  PrismAI-0.9.0-windows-x64.zip\n".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/apk") { exchange ->
            exchange.sendResponseHeaders(200, apk.size.toLong())
            exchange.responseBody.use { it.write(apk) }
        }
        server.start()
    }

    @AfterTest
    fun stop() {
        runCatching { server.stop(0) }
        dir.deleteRecursively()
    }

    private inner class TestUpdater : GitHubAppUpdater(dir, "-android-arm64.apk", "$base/releases/latest", setOf("http")) {
        var installed: String? = null
        override val supported = true
        override suspend fun install(path: String, version: String) {
            installed = path
        }
    }

    @Test
    fun latestReleasePicksThePlatformFileWithItsChecksum() = runBlocking {
        val release = TestUpdater().latestRelease()
        assertEquals("0.9.0", release.version)
        assertEquals("Что нового", release.notes)
        assertEquals("PrismAI-0.9.0-android-arm64.apk", release.asset?.name)
        assertEquals(sha256(apk), release.asset?.sha256)
    }

    @Test
    fun checksumComesFromSumsFileWhenGitHubGivesNoDigest() = runBlocking {
        withDigest = false
        assertEquals(sha256(apk), TestUpdater().latestRelease().asset?.sha256)
    }

    @Test
    fun downloadVerifiesTheFile() = runBlocking {
        val updater = TestUpdater()
        val asset = checkNotNull(updater.latestRelease().asset)
        val path = updater.download(asset) { _, _ -> }
        assertContentEquals(apk, File(path).readBytes())

        val broken = asset.copy(sha256 = "0".repeat(64))
        File(path).delete()
        val error = assertFailsWith<UpdateException> { updater.download(broken) { _, _ -> } }
        assertEquals(UpdateError.CHECKSUM, error.error)
    }

    @Test
    fun serviceFindsNewerVersionDownloadsAndInstalls() = runBlocking {
        val updater = TestUpdater()
        val service = UpdateService(updater, CoroutineScope(Dispatchers.IO), currentVersion = "0.8.0")
        service.check()
        val available = withTimeout(10_000) { service.state.first { it is UpdateState.Available } } as UpdateState.Available
        assertEquals("0.9.0", available.release.version)

        service.install()
        withTimeout(10_000) { service.state.first { it is UpdateState.Installing } }
        withTimeout(10_000) { while (updater.installed == null) kotlinx.coroutines.delay(10) }
        assertContentEquals(apk, File(checkNotNull(updater.installed)).readBytes())
    }

    @Test
    fun sameVersionIsUpToDate() = runBlocking {
        val service = UpdateService(TestUpdater(), CoroutineScope(Dispatchers.IO), currentVersion = "0.9.0")
        service.check()
        assertEquals(UpdateState.UpToDate("0.9.0"), withTimeout(10_000) { service.state.first { it is UpdateState.UpToDate } })
    }

    @Test
    fun networkErrorsAreReported() = runBlocking {
        server.stop(0)
        val service = UpdateService(TestUpdater(), CoroutineScope(Dispatchers.IO), currentVersion = "0.8.0")
        service.check()
        val failed = withTimeout(10_000) { service.state.first { it is UpdateState.Failed } } as UpdateState.Failed
        assertEquals(UpdateError.NETWORK, failed.error)
    }

    @Test
    fun versionsCompareByNumbers() {
        assertTrue(AppVersion.isNewer("0.10.0", "0.9.9"))
        assertTrue(AppVersion.isNewer("v1.0", "0.99.0"))
        assertFalse(AppVersion.isNewer("0.8.0", "0.8.0"))
        // Версии с четвёртым числом (исправления): 0.9.2.1 новее 0.9.2, но старше 0.9.3.
        assertTrue(AppVersion.isNewer("0.9.2.1", "0.9.2"))
        assertFalse(AppVersion.isNewer("0.9.2.1", "0.9.3"))
        assertFalse(AppVersion.isNewer("0.8.0-beta", "0.8.0"))
        assertFalse(AppVersion.isNewer("0.7.9", "0.8.0"))
    }

    @Test
    fun platformWithoutUpdatesDoesNothing() {
        val service = UpdateService(AppUpdater.None, CoroutineScope(Dispatchers.IO))
        service.check()
        assertEquals(UpdateState.Idle, service.state.value)
    }
}
