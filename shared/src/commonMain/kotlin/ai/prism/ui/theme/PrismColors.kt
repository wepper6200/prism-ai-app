package ai.prism.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Фирменная палитра Prism: фиолетовый (primary), индиго (secondary), розовый (tertiary) —
 * «спектр» призмы. Контраст текста на основных цветах ≥ 4.5:1.
 */

internal val PrismLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF7C3AED),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDE4FF),
    onPrimaryContainer = Color(0xFF2A0A6B),
    inversePrimary = Color(0xFFD0BCFF),
    secondary = Color(0xFF5354C4),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E3FF),
    onSecondaryContainer = Color(0xFF14156B),
    tertiary = Color(0xFFA62D68),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E6),
    onTertiaryContainer = Color(0xFF3E0021),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1C1A23),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1A23),
    surfaceVariant = Color(0xFFE7E0F0),
    onSurfaceVariant = Color(0xFF4A4458),
    surfaceTint = Color(0xFF7C3AED),
    inverseSurface = Color(0xFF322F38),
    inverseOnSurface = Color(0xFFF5EFFA),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF7B748A),
    outlineVariant = Color(0xFFCBC3D9),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFCF8FF),
    surfaceContainer = Color(0xFFF1ECF7),
    surfaceContainerHigh = Color(0xFFEBE6F1),
    surfaceContainerHighest = Color(0xFFE5E0EB),
    surfaceContainerLow = Color(0xFFF7F2FC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDD8E3),
)

internal val PrismDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF3B0E8C),
    primaryContainer = Color(0xFF5420C2),
    onPrimaryContainer = Color(0xFFEDE4FF),
    inversePrimary = Color(0xFF7C3AED),
    secondary = Color(0xFFC0C1FF),
    onSecondary = Color(0xFF26278F),
    secondaryContainer = Color(0xFF3B3CA8),
    onSecondaryContainer = Color(0xFFE2E3FF),
    tertiary = Color(0xFFFFB0CF),
    onTertiary = Color(0xFF5F1040),
    tertiaryContainer = Color(0xFF842357),
    onTertiaryContainer = Color(0xFFFFD9E6),
    background = Color(0xFF131119),
    onBackground = Color(0xFFE7E1EE),
    surface = Color(0xFF131119),
    onSurface = Color(0xFFE7E1EE),
    surfaceVariant = Color(0xFF4A4458),
    onSurfaceVariant = Color(0xFFCBC3D9),
    surfaceTint = Color(0xFFD0BCFF),
    inverseSurface = Color(0xFFE7E1EE),
    inverseOnSurface = Color(0xFF322F38),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF958EA3),
    outlineVariant = Color(0xFF4A4458),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A3741),
    surfaceContainer = Color(0xFF201D27),
    surfaceContainerHigh = Color(0xFF2A2731),
    surfaceContainerHighest = Color(0xFF35323C),
    surfaceContainerLow = Color(0xFF1B1822),
    surfaceContainerLowest = Color(0xFF0E0C14),
    surfaceDim = Color(0xFF131119),
)

/**
 * Палитра Liquid Glass: акценты бренда, а фон и контейнеры полупрозрачные,
 * чтобы сквозь них проступал размытый фон.
 */
internal fun glassColorScheme(base: ColorScheme, darkTheme: Boolean): ColorScheme {
    val glass = if (darkTheme) Color(0xFF1A1724) else Color(0xFFFFFFFF)
    return base.copy(
        background = Color.Transparent,
        surface = Color.Transparent,
        surfaceContainerLowest = glass.copy(alpha = 0.20f),
        surfaceContainerLow = glass.copy(alpha = 0.28f),
        surfaceContainer = glass.copy(alpha = 0.36f),
        surfaceContainerHigh = glass.copy(alpha = 0.46f),
        surfaceContainerHighest = glass.copy(alpha = 0.56f),
        surfaceVariant = glass.copy(alpha = 0.40f),
        secondaryContainer = base.secondaryContainer.copy(alpha = 0.72f),
    )
}
