package ai.prism.store

import ai.prism.store.data.GitHubAsset
import ai.prism.store.data.GitHubCatalogSource
import ai.prism.store.model.AppInstaller
import ai.prism.store.model.CatalogSource
import ai.prism.store.model.PackageDownloader
import ai.prism.store.model.StoreApp
import ai.prism.store.model.StoreCatalog
import ai.prism.store.model.StoreError
import ai.prism.store.model.StoreException
import ai.prism.store.model.StoreFile
import ai.prism.store.model.Versions
import ai.prism.store.model.WindowsPackage
import ai.prism.store.ui.StoreScreen
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class StoreTest {

    private fun file(name: String, size: Long) = StoreFile(name, "https://example.org/$name", size, "0".repeat(64))

    private val prismAi = StoreApp(
        id = "prism-ai",
        name = "Prism AI",
        description = "Офлайн ИИ-ассистент: чат, фото, рисование, файлы, агент, видео и голос.",
        version = "0.9.0",
        windows = WindowsPackage("PrismAI", "PrismAI.exe", file("PrismAI-0.9.0-windows-x64.zip", 175L * 1024 * 1024)),
    )
    private val store = StoreApp(
        id = "prism-store",
        name = "Prism Store",
        description = "Магазин приложений Prism.",
        version = "1.0.0",
        windows = WindowsPackage("PrismStore", "PrismStore.exe", file("PrismStore-1.0.0-windows-x64.zip", 60L * 1024 * 1024)),
    )

    private class FakeSource(var catalog: StoreCatalog? = null) : CatalogSource {
        override suspend fun load(): StoreCatalog = catalog ?: throw StoreException(StoreError.NETWORK, "offline")
    }

    private class FakeDownloader : PackageDownloader {
        override suspend fun download(file: StoreFile, onProgress: (Long, Long) -> Unit): String {
            onProgress(file.sizeBytes, file.sizeBytes)
            return "/downloads/${file.fileName}"
        }
    }

    private class FakeInstaller(val installed: MutableMap<String, String> = mutableMapOf()) : AppInstaller {
        val installs = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var failWith: StoreError? = null
        override fun fileFor(app: StoreApp): StoreFile? = app.windows?.file
        override fun installedVersion(app: StoreApp): String? = installed[app.id]
        override suspend fun install(app: StoreApp, path: String) {
            failWith?.let { throw StoreException(it, "fail") }
            installs += path
            installed[app.id] = app.version
        }
        override fun open(app: StoreApp) {
            opened += app.id
        }
    }

    private fun controller(source: CatalogSource, installer: FakeInstaller) =
        StoreController(source, FakeDownloader(), installer, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun actionsDependOnInstalledVersions() {
        val installer = FakeInstaller(mutableMapOf("prism-store" to "1.0.0"))
        val controller = controller(FakeSource(StoreCatalog(apps = listOf(prismAi, store))), installer)
        controller.refresh()

        val apps = controller.state.value.apps.associateBy { it.app.id }
        assertEquals(AppAction.INSTALL, apps.getValue("prism-ai").action)
        assertEquals(AppAction.OPEN, apps.getValue("prism-store").action)

        installer.installed["prism-ai"] = "0.8.0"
        controller.recheckInstalled()
        assertEquals(AppAction.UPDATE, controller.state.value.apps.first { it.app.id == "prism-ai" }.action)
    }

    @Test
    fun installDownloadsAndInstallsThenOffersToOpen() {
        val installer = FakeInstaller()
        val controller = controller(FakeSource(StoreCatalog(apps = listOf(prismAi))), installer)
        controller.refresh()

        controller.install("prism-ai")

        assertEquals(listOf("/downloads/PrismAI-0.9.0-windows-x64.zip"), installer.installs)
        val item = controller.state.value.apps.single()
        assertEquals(AppAction.OPEN, item.action)
        assertEquals("0.9.0", item.installedVersion)
        assertNull(item.progress)

        controller.open("prism-ai")
        assertEquals(listOf("prism-ai"), installer.opened)
    }

    @Test
    fun errorsAreShownOnTheApp() {
        val installer = FakeInstaller().apply { failWith = StoreError.APP_RUNNING }
        val controller = controller(FakeSource(StoreCatalog(apps = listOf(prismAi))), installer)
        controller.refresh()
        controller.install("prism-ai")
        assertEquals(StoreError.APP_RUNNING, controller.state.value.apps.single().error)

        val offline = controller(FakeSource(null), FakeInstaller())
        offline.refresh()
        assertEquals(StoreError.NETWORK, offline.state.value.error)
    }

    @Test
    fun appsWithoutFileForThisPlatformAreHidden() {
        val androidOnly = prismAi.copy(id = "android-only", windows = null)
        val controller = controller(FakeSource(StoreCatalog(apps = listOf(prismAi, androidOnly))), FakeInstaller())
        controller.refresh()
        assertEquals(listOf("prism-ai"), controller.state.value.apps.map { it.app.id })
    }

    @Test
    fun catalogFromReleaseIsParsed() {
        val json = """
            {"version":1,"apps":[{"id":"prism-ai","name":"Prism AI","version":"0.9.0","description":"x","extra":1,
              "android":{"packageName":"ai.prism.app","file":{"fileName":"PrismAI-0.9.0-android-arm64.apk",
                "url":"https://github.com/r/a.apk","sizeBytes":10,"sha256":"ab"}}}]}
        """.trimIndent()
        val catalog = lenientJson.decodeFromString(StoreCatalog.serializer(), json)
        assertEquals("ai.prism.app", catalog.apps.single().android?.packageName)
        assertNull(catalog.apps.single().windows)
    }

    @Test
    fun releaseWithoutCatalogIsReadFromItsFiles() {
        val sha = "a".repeat(64)
        val catalog = GitHubCatalogSource().fromAssets(
            listOf(
                GitHubAsset("PrismAI-0.8.0-android-arm64.apk", "https://example.org/a.apk", 120, "sha256:$sha"),
                GitHubAsset("PrismAI-0.8.0-windows-x64.zip", "https://example.org/w.zip", 175, "sha256:$sha"),
                GitHubAsset("SHA256SUMS.txt", "https://example.org/s", 1, null),
                GitHubAsset("PrismStore-1.0.0-android.apk", "https://example.org/s.apk", 50, null),
            ),
        )
        val ai = catalog!!.apps.single()
        assertEquals("prism-ai", ai.id)
        assertEquals("0.8.0", ai.version)
        assertEquals("ai.prism.app", ai.android?.packageName)
        assertEquals(sha, ai.android?.file?.sha256)
        assertEquals("PrismAI.exe", ai.windows?.exe)
        assertNull(GitHubCatalogSource().fromAssets(emptyList()))
    }

    @Test
    fun versionsCompareByNumbers() {
        assertEquals(true, Versions.isNewer("0.10.0", "0.9.9"))
        assertEquals(true, Versions.isNewer("1.0.0", ""))
        assertEquals(false, Versions.isNewer("1.0.0", "1.0.0"))
    }

    @Test
    fun storeScreenInstallsAnApp() = runDesktopComposeUiTest(width = 412, height = 892) {
        val installer = FakeInstaller(mutableMapOf("prism-store" to "1.0.0"))
        val controller = controller(FakeSource(StoreCatalog(apps = listOf(prismAi, store))), installer)
        setContent { StoreScreen(controller) }
        waitForIdle()
        onNodeWithText("Prism AI").assertExists()
        screenshot("store_1_catalog")

        onNodeWithTag("install_prism-ai").performClick()
        waitForIdle()
        onNodeWithTag("open_prism-ai").assertExists()
        screenshot("store_2_installed")
        assertEquals(1, installer.installs.size)
    }

    private companion object {
        val lenientJson = Json { ignoreUnknownKeys = true }
    }

    private fun ComposeUiTest.screenshot(name: String) {
        waitForIdle()
        val dir = File(System.getProperty("prism.screenshotDir") ?: "build/screenshots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }
}
