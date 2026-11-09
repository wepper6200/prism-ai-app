package ai.prism.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

private val GlassBlurRadius = 28.dp

/**
 * Стеклянная панель Liquid Glass.
 *
 * Под панелью рисуется тот же фон окна, сдвинутый на позицию панели, и размывается
 * платформенным эффектом ([rememberBackdropBlur]). Сверху — полупрозрачная заливка,
 * мягкий блик и светлая кромка. Если размытие недоступно, заливка плотнее.
 */
@Composable
fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val darkTheme = LocalPrismStyle.current.isDark
    val backdrop = LocalBackdropState.current
    val blur = rememberBackdropBlur(GlassBlurRadius)
    var origin by remember { mutableStateOf(Offset.Zero) }

    val tint = when {
        darkTheme -> Color(0xFF16131F).copy(alpha = if (blur != null) 0.42f else 0.86f)
        else -> Color.White.copy(alpha = if (blur != null) 0.50f else 0.88f)
    }
    val sheen = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = if (darkTheme) 0.08f else 0.30f), Color.Transparent),
    )
    val rim = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (darkTheme) 0.28f else 0.85f),
            Color.White.copy(alpha = if (darkTheme) 0.04f else 0.25f),
        ),
    )

    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape),
    ) {
        if (blur != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        renderEffect = blur
                        clip = true
                    }
                    .drawBehind {
                        translate(left = -origin.x, top = -origin.y) {
                            drawPrismBackdrop(backdrop.size, darkTheme)
                        }
                    },
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(tint)
                .background(sheen)
                .border(width = 1.dp, brush = rim, shape = shape),
        )
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}
