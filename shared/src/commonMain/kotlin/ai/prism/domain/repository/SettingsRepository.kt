package ai.prism.domain.repository

import ai.prism.domain.model.AppSettings
import ai.prism.domain.model.DarkMode
import ai.prism.domain.model.ThemeStyle
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setThemeStyle(style: ThemeStyle)

    fun setDarkMode(mode: DarkMode)

    fun setDynamicColor(enabled: Boolean)

    fun setSafeMode(enabled: Boolean)
}
