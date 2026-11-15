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
 * Фон Liquid Glass: мягкие цветные пятна «спектра». Стеклянные панели рисуют этот же
 * фон у себя под размытием, поэтому размер окна хранится здесь.
 */
@Stable
class BackdropState {
    var size: Size by mutableStateOf(Size.Zero)
}

val LocalBackdropState = staticCompositionLocalOf { BackdropState() }

private data class Glow(val x: Float, val y: Float, val radius: Float, val color: Color, val lightAlpha: Float, val darkAlpha: Float)

private val Glows = listOf(
    Glow(x = 0.12f, y = 0.10f, radius = 0.60f, color = Color(0xFF7C3AED), lightAlpha = 0.12f, darkAlpha = 0.14f),
    Glow(x = 0.88f, y = 0.22f, radius = 0.48f, color = Color(0xFF22D3EE), lightAlpha = 0.10f, darkAlpha = 0.10f),
    Glow(x = 0.72f, y = 0.88f, radius = 0.55f, color = Color(0xFFEC4899), lightAlpha = 0.05f, darkAlpha = 0.06f),
    Glow(x = 0.08f, y = 0.82f, radius = 0.38f, color = Color(0xFFF59E0B), lightAlpha = 0.03f, darkAlpha = 0.04f),
)

/** Рисует фон в координатах окна размером [windowSize]. */
internal fun DrawScope.drawPrismBackdrop(windowSize: Size, darkTheme: Boolean) {
    if (windowSize.width <= 0f || windowSize.height <= 0f) return
    drawRect(
        color = if (darkTheme) Color(0xFF0B0E13) else Color(0xFFF5F7FA),
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
