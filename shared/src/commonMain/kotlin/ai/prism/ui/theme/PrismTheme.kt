package ai.prism.ui.theme

import ai.prism.domain.model.AppSettings
import ai.prism.domain.model.DarkMode
import ai.prism.domain.model.ThemeStyle
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Активный стиль оформления, доступный любому компоненту интерфейса. */
@Immutable
data class PrismStyle(
    val themeStyle: ThemeStyle,
    val isDark: Boolean,
) {
    val isGlass: Boolean get() = themeStyle == ThemeStyle.LIQUID_GLASS
}

val LocalPrismStyle = staticCompositionLocalOf { PrismStyle(ThemeStyle.EXPRESSIVE, isDark = false) }

private val PrismShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private const val THEME_ANIMATION_MS = 450

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PrismTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (settings.darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    val useDynamic = settings.themeStyle == ThemeStyle.EXPRESSIVE && settings.dynamicColor
    val dynamicScheme = if (useDynamic) platformDynamicColorScheme(darkTheme) else null
    val baseScheme = dynamicScheme ?: if (darkTheme) PrismDarkColors else PrismLightColors
    val targetScheme = when (settings.themeStyle) {
        ThemeStyle.EXPRESSIVE -> baseScheme
        ThemeStyle.LIQUID_GLASS -> glassColorScheme(baseScheme, darkTheme)
    }

    SystemBarsEffect(darkTheme)

    CompositionLocalProvider(LocalPrismStyle provides PrismStyle(settings.themeStyle, darkTheme)) {
        MaterialExpressiveTheme(
            colorScheme = animateColorScheme(targetScheme),
            shapes = PrismShapes,
            content = content,
        )
    }
}

/** Плавная смена палитры при переключении темы, режима или стиля. */
@Composable
private fun animateColorScheme(target: ColorScheme): ColorScheme = target.copy(
    primary = target.primary.animated(),
    onPrimary = target.onPrimary.animated(),
    primaryContainer = target.primaryContainer.animated(),
    onPrimaryContainer = target.onPrimaryContainer.animated(),
    secondary = target.secondary.animated(),
    onSecondary = target.onSecondary.animated(),
    secondaryContainer = target.secondaryContainer.animated(),
    onSecondaryContainer = target.onSecondaryContainer.animated(),
    tertiary = target.tertiary.animated(),
    tertiaryContainer = target.tertiaryContainer.animated(),
    background = target.background.animated(),
    onBackground = target.onBackground.animated(),
    surface = target.surface.animated(),
    onSurface = target.onSurface.animated(),
    surfaceVariant = target.surfaceVariant.animated(),
    onSurfaceVariant = target.onSurfaceVariant.animated(),
    outline = target.outline.animated(),
    outlineVariant = target.outlineVariant.animated(),
    surfaceContainerLowest = target.surfaceContainerLowest.animated(),
    surfaceContainerLow = target.surfaceContainerLow.animated(),
    surfaceContainer = target.surfaceContainer.animated(),
    surfaceContainerHigh = target.surfaceContainerHigh.animated(),
    surfaceContainerHighest = target.surfaceContainerHighest.animated(),
)

@Composable
private fun Color.animated(): Color =
    animateColorAsState(targetValue = this, animationSpec = tween(THEME_ANIMATION_MS), label = "themeColor").value
