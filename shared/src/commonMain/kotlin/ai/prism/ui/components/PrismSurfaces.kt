package ai.prism.ui.components

import ai.prism.ui.theme.BackdropState
import ai.prism.ui.theme.GlassSurface
import ai.prism.ui.theme.LocalBackdropState
import ai.prism.ui.theme.LocalPrismStyle
import ai.prism.ui.theme.PrismBackdrop
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize

/**
 * Фон окна. В Liquid Glass — цветные пятна спектра (их же размывают панели),
 * в Material 3 Expressive — цвет фона темы. Смена стиля — плавным перетеканием.
 */
@Composable
fun PrismBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val style = LocalPrismStyle.current
    val backdropState = remember { BackdropState() }
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { backdropState.size = it.toSize() },
    ) {
        Crossfade(targetState = style.isGlass, animationSpec = tween(durationMillis = 450), label = "background") { glass ->
            if (glass) {
                PrismBackdrop(darkTheme = style.isDark)
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }
        }
        CompositionLocalProvider(
            LocalBackdropState provides backdropState,
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
        ) {
            content()
        }
    }
}

/** Панель-контейнер: стеклянная в Liquid Glass, тональная поверхность в Material 3 Expressive. */
@Composable
fun PrismPanel(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    content: @Composable BoxScope.() -> Unit,
) {
    if (LocalPrismStyle.current.isGlass) {
        GlassSurface(shape = shape, modifier = modifier, content = content)
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Box(content = content)
        }
    }
}
