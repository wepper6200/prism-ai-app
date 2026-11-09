package ai.prism.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.unit.Dp

/** Динамическая палитра из обоев (Material You) или `null`, если платформа её не поддерживает. */
@Composable
internal expect fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme?

/**
 * Эффект размытия для стеклянных панелей:
 * Desktop — Skia `ImageFilter`, Android 12+ — `RenderEffect`.
 * `null` — размытие недоступно (Android 11 и ниже), панели остаются просто полупрозрачными.
 */
@Composable
internal expect fun rememberBackdropBlur(radius: Dp): RenderEffect?

/** Цвет значков системных панелей под текущую тему. */
@Composable
internal expect fun SystemBarsEffect(darkTheme: Boolean)
