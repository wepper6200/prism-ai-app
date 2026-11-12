package ai.prism.store.desktop

import ai.prism.store.StoreController
import ai.prism.store.data.GitHubCatalogSource
import ai.prism.store.data.HttpPackageDownloader
import ai.prism.store.platform.WindowsAppInstaller
import ai.prism.store.ui.StoreScreen
import ai.prism.store.ui.storeTitle
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.swing.Swing
import java.io.File

fun main() {
    val programs = WindowsAppInstaller.defaultProgramsDir()
    val work = File(programs.parentFile, "PrismStore-data").apply { mkdirs() }
    val controller = StoreController(
        source = GitHubCatalogSource(),
        downloader = HttpPackageDownloader(File(work, "packages")),
        installer = WindowsAppInstaller(
            programsDir = programs,
            workDir = work,
            selfVersion = System.getProperty("prism.store.version").orEmpty(),
        ),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing),
    )
    application {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(size = DpSize(760.dp, 820.dp), position = WindowPosition.Aligned(Alignment.Center)),
            title = storeTitle(),
        ) {
            StoreScreen(controller)
        }
    }
}
