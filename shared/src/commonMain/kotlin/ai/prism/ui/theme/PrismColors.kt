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

/** Neutral graphite and silver glass; semantic errors retain a subdued red cue. */
internal fun glassColorScheme(base: ColorScheme, darkTheme: Boolean): ColorScheme {
    val glass = if (darkTheme) Color(0xFF1C1C1C) else Color.White
    val ink = if (darkTheme) Color(0xFFF1F1F1) else Color(0xFF222222)
    val accent = if (darkTheme) Color(0xFFD0D0D0) else Color(0xFF505050)
    val container = if (darkTheme) Color(0xFF333333) else Color(0xFFE5E5E5)
    val onAccent = if (darkTheme) Color(0xFF171717) else Color.White
    return base.copy(
        primary = ink, onPrimary = onAccent,
        primaryContainer = container, onPrimaryContainer = ink,
        inversePrimary = if (darkTheme) Color(0xFF414141) else Color(0xFFD6D6D6),
        secondary = accent, onSecondary = onAccent,
        secondaryContainer = container.copy(alpha = 0.82f), onSecondaryContainer = ink,
        tertiary = accent, onTertiary = onAccent,
        tertiaryContainer = container.copy(alpha = 0.82f), onTertiaryContainer = ink,
        onBackground = ink, onSurface = ink,
        onSurfaceVariant = if (darkTheme) Color(0xFFB8B8B8) else Color(0xFF626262),
        background = Color.Transparent, surface = Color.Transparent,
        surfaceTint = accent,
        inverseSurface = if (darkTheme) Color(0xFFE5E5E5) else Color(0xFF2B2B2B),
        inverseOnSurface = if (darkTheme) Color(0xFF222222) else Color(0xFFF1F1F1),
        outline = if (darkTheme) Color(0xFF707070) else Color(0xFF808080),
        outlineVariant = if (darkTheme) Color(0xFF3B3B3B) else Color(0xFFD4D4D4),
        surfaceBright = if (darkTheme) Color(0xFF383838) else Color(0xFFFAFAFA),
        surfaceDim = if (darkTheme) Color(0xFF101010) else Color(0xFFE0E0E0),
        surfaceContainerLowest = glass.copy(alpha = 0.22f),
        surfaceContainerLow = glass.copy(alpha = 0.32f),
        surfaceContainer = glass.copy(alpha = 0.42f),
        surfaceContainerHigh = glass.copy(alpha = 0.56f),
        surfaceContainerHighest = glass.copy(alpha = 0.68f),
        surfaceVariant = glass.copy(alpha = 0.48f),
        error = if (darkTheme) Color(0xFFE7B4B4) else Color(0xFF944747),
        onError = if (darkTheme) Color(0xFF361E1E) else Color.White,
        errorContainer = if (darkTheme) Color(0xFF2C2323) else Color(0xFFF5ECEC),
        onErrorContainer = if (darkTheme) Color(0xFFF2DADA) else Color(0xFF753A3A),
    )
}
