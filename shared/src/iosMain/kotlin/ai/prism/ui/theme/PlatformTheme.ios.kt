package ai.prism.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter

@Composable
internal actual fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

@Composable
internal actual fun rememberBackdropBlur(radius: Dp): RenderEffect? {
    val pixels = with(LocalDensity.current) { radius.toPx() }
    return remember(pixels) { ImageFilter.makeBlur(pixels * 0.57735f + 0.5f, pixels * 0.57735f + 0.5f, FilterTileMode.CLAMP).asComposeRenderEffect() }
}

@Composable
internal actual fun SystemBarsEffect(darkTheme: Boolean) {
    androidx.compose.runtime.SideEffect { ai.prism.ios.appleHost.setSystemAppearance(darkTheme) }
}
