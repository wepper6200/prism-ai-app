package ai.prism.domain.model

/** Визуальный стиль интерфейса. */
enum class ThemeStyle {
    /** Material 3 Expressive: насыщенные цвета, выразительные формы и движение. */
    EXPRESSIVE,

    /** Liquid Glass: полупрозрачные стеклянные панели с размытием фона. */
    LIQUID_GLASS,
}

/** Светлый или тёмный режим. */
enum class DarkMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Пользовательские настройки, которые сохраняются между запусками. */
data class AppSettings(
    val themeStyle: ThemeStyle = ThemeStyle.EXPRESSIVE,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val dynamicColor: Boolean = true,
)
