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

/** На Windows нет системной палитры из обоев. */
@Composable
internal actual fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

@Composable
internal actual fun rememberBackdropBlur(radius: Dp): RenderEffect? {
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    return remember(radiusPx) {
        // Та же формула перевода радиуса в сигму, что и у RenderEffect на Android.
        val sigma = radiusPx * BLUR_SIGMA_SCALE + 0.5f
        ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP).asComposeRenderEffect()
    }
}

/** Системные панели окна Desktop не зависят от темы приложения. */
@Composable
internal actual fun SystemBarsEffect(darkTheme: Boolean) = Unit

private const val BLUR_SIGMA_SCALE = 0.57735f
