package ai.prism.domain.model

/** Возможности текущей платформы, от которых зависит интерфейс настроек. */
data class PlatformCapabilities(
    /** Доступны ли динамические цвета Material You (Android 12+). */
    val supportsDynamicColor: Boolean,
    /** `android` или `desktop` — для выбора моделей каталога. */
    val platform: String = "desktop",
    /** Ассистент может работать с компьютером: файлы вне проекта, программы, сведения о системе (Desktop). */
    val supportsComputer: Boolean = false,
)
