package ai.prism.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.max

/**
 * Фон Liquid Glass: мягкий нейтральный свет. Стеклянные панели рисуют этот же
 * фон у себя под размытием, поэтому размер окна хранится здесь.
 */
@Stable
class BackdropState {
    var size: Size by mutableStateOf(Size.Zero)
}

val LocalBackdropState = staticCompositionLocalOf { BackdropState() }

private data class Glow(val x: Float, val y: Float, val radius: Float, val color: Color, val lightAlpha: Float, val darkAlpha: Float)

private val Glows = listOf(
    Glow(x = 0.18f, y = 0.08f, radius = 0.75f, color = Color.White, lightAlpha = 0.35f, darkAlpha = 0.035f),
    Glow(x = 0.82f, y = 0.85f, radius = 0.65f, color = Color(0xFF808080), lightAlpha = 0.035f, darkAlpha = 0.02f),
)

/** Рисует фон в координатах окна размером [windowSize]. */
internal fun DrawScope.drawPrismBackdrop(windowSize: Size, darkTheme: Boolean) {
    if (windowSize.width <= 0f || windowSize.height <= 0f) return
    drawRect(
        color = if (darkTheme) Color(0xFF101010) else Color(0xFFF4F4F4),
        topLeft = Offset.Zero,
        size = windowSize,
    )
    val extent = max(windowSize.width, windowSize.height)
    Glows.forEach { glow ->
        val center = Offset(windowSize.width * glow.x, windowSize.height * glow.y)
        val radius = extent * glow.radius
        val color = glow.color.copy(alpha = if (darkTheme) glow.darkAlpha else glow.lightAlpha)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color, color.copy(alpha = 0f)),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

@Composable
internal fun PrismBackdrop(darkTheme: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawPrismBackdrop(size, darkTheme)
    }
}
