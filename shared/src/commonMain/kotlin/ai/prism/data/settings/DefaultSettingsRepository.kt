package ai.prism.data.settings

import ai.prism.domain.model.AppSettings
import ai.prism.domain.model.DarkMode
import ai.prism.domain.model.ThemeStyle
import ai.prism.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * Настройки поверх [KeyValueStore]. Неизвестные или повреждённые значения
 * заменяются значениями по умолчанию, поэтому приложение всегда стартует.
 */
class DefaultSettingsRepository(
    private val store: KeyValueStore,
) : SettingsRepository {

    private val state = MutableStateFlow(load())

    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun setThemeStyle(style: ThemeStyle) = update { it.copy(themeStyle = style) }

    override fun setDarkMode(mode: DarkMode) = update { it.copy(darkMode = mode) }

    override fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    override fun setSafeMode(enabled: Boolean) = update { it.copy(safeMode = enabled) }

    private fun update(transform: (AppSettings) -> AppSettings) {
        save(state.updateAndGet(transform))
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeStyle = parseEnum(store.getString(KEY_THEME_STYLE), defaults.themeStyle),
            darkMode = parseEnum(store.getString(KEY_DARK_MODE), defaults.darkMode),
            dynamicColor = store.getString(KEY_DYNAMIC_COLOR)?.toBooleanStrictOrNull() ?: defaults.dynamicColor,
            safeMode = store.getString(KEY_SAFE_MODE)?.toBooleanStrictOrNull() ?: defaults.safeMode,
        )
    }

    private fun save(settings: AppSettings) {
        store.putString(KEY_THEME_STYLE, settings.themeStyle.name)
        store.putString(KEY_DARK_MODE, settings.darkMode.name)
        store.putString(KEY_DYNAMIC_COLOR, settings.dynamicColor.toString())
        store.putString(KEY_SAFE_MODE, settings.safeMode.toString())
    }

    private inline fun <reified E : Enum<E>> parseEnum(raw: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == raw } ?: default

    private companion object {
        const val KEY_THEME_STYLE = "theme_style"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_SAFE_MODE = "safe_mode"
    }
}
