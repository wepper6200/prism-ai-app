package ai.prism.desktop

import ai.prism.di.desktopPlatformModule
import ai.prism.di.initKoin
import ai.prism.presentation.root.RootComponentFactory
import ai.prism.ui.PrismApp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import java.awt.Dimension

fun main() {
    val koin = initKoin(desktopPlatformModule()).koin
    val lifecycle = LifecycleRegistry()
    val root = runOnUiThread {
        koin.get<RootComponentFactory>()
            .create(DefaultComponentContext(lifecycle = lifecycle))
            .also { lifecycle.resume() }
    }

    application {
        val windowState = rememberWindowState(
            size = DpSize(1200.dp, 800.dp),
            position = WindowPosition.Aligned(Alignment.Center),
        )
        Window(
            onCloseRequest = {
                lifecycle.destroy()
                exitApplication()
            },
            state = windowState,
            title = prismWindowTitle(),
            icon = prismWindowIcon(),
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
            }
            PrismApp(root)
        }
    }
}

private const val MIN_WIDTH = 400
private const val MIN_HEIGHT = 560
