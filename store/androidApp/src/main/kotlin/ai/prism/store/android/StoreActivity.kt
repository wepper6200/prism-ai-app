package ai.prism.store.android

import ai.prism.store.StoreController
import ai.prism.store.data.GitHubCatalogSource
import ai.prism.store.data.HttpPackageDownloader
import ai.prism.store.platform.AndroidAppInstaller
import ai.prism.store.ui.StoreScreen
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class StoreActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val controller = StoreProcess.controller(applicationContext)
        setContent { StoreScreen(controller) }
    }

    override fun onResume() {
        super.onResume()
        // После установки или удаления приложения в системе кнопки должны показать новое состояние.
        StoreProcess.controller(applicationContext).recheckInstalled()
    }
}

/** Один магазин на процесс: загрузка не прерывается при повороте экрана. */
private object StoreProcess {
    private var instance: StoreController? = null

    fun controller(context: Context): StoreController = instance ?: StoreController(
        source = GitHubCatalogSource(),
        downloader = HttpPackageDownloader(File(context.cacheDir, "packages")),
        installer = AndroidAppInstaller(context),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    ).also { instance = it }
}
