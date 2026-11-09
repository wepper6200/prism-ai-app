package ai.prism.domain.model

/** Возможности текущей платформы, от которых зависит интерфейс настроек. */
data class PlatformCapabilities(
    /** Доступны ли динамические цвета Material You (Android 12+). */
    val supportsDynamicColor: Boolean,
)
