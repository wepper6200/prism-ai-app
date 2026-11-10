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
    /**
     * Безопасный режим: Prism AI не рисует и не пишет откровенный, жестокий и подобный контент.
     * Проверка идёт на устройстве и не даёт полной гарантии. По умолчанию выключен.
     */
    val safeMode: Boolean = false,
    /**
     * Интернет для ассистента: поиск (DuckDuckGo), чтение страниц и скачивание файлов.
     * Запросы уходят в сеть, поэтому по умолчанию выключен.
     */
    val internetEnabled: Boolean = false,
    /** Ассистент меняет файлы в папке проекта без подтверждения каждой правки. Команды подтверждаются всегда. */
    val autoApplyEdits: Boolean = false,
)
