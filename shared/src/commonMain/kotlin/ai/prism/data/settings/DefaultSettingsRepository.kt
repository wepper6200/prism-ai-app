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

    override fun setInternetEnabled(enabled: Boolean) = update { it.copy(internetEnabled = enabled) }

    override fun setAutoApplyEdits(enabled: Boolean) = update { it.copy(autoApplyEdits = enabled) }

    override fun setSpeechModel(modelId: String) = update { it.copy(speechModelId = modelId) }

    override fun setVoiceModel(modelId: String) = update { it.copy(voiceModelId = modelId) }

    override fun setSpeechRate(rate: Float) = update {
        it.copy(speechRate = rate.coerceIn(AppSettings.MIN_SPEECH_RATE, AppSettings.MAX_SPEECH_RATE))
    }

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
            internetEnabled = store.getString(KEY_INTERNET)?.toBooleanStrictOrNull() ?: defaults.internetEnabled,
            autoApplyEdits = store.getString(KEY_AUTO_APPLY)?.toBooleanStrictOrNull() ?: defaults.autoApplyEdits,
            speechModelId = store.getString(KEY_SPEECH_MODEL) ?: defaults.speechModelId,
            voiceModelId = store.getString(KEY_VOICE_MODEL) ?: defaults.voiceModelId,
            speechRate = store.getString(KEY_SPEECH_RATE)?.toFloatOrNull()
                ?.takeIf { it in AppSettings.MIN_SPEECH_RATE..AppSettings.MAX_SPEECH_RATE }
                ?: defaults.speechRate,
        )
    }

    private fun save(settings: AppSettings) {
        store.putString(KEY_THEME_STYLE, settings.themeStyle.name)
        store.putString(KEY_DARK_MODE, settings.darkMode.name)
        store.putString(KEY_DYNAMIC_COLOR, settings.dynamicColor.toString())
        store.putString(KEY_SAFE_MODE, settings.safeMode.toString())
        store.putString(KEY_INTERNET, settings.internetEnabled.toString())
        store.putString(KEY_AUTO_APPLY, settings.autoApplyEdits.toString())
        store.putString(KEY_SPEECH_MODEL, settings.speechModelId)
        store.putString(KEY_VOICE_MODEL, settings.voiceModelId)
        store.putString(KEY_SPEECH_RATE, settings.speechRate.toString())
    }

    private inline fun <reified E : Enum<E>> parseEnum(raw: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == raw } ?: default

    private companion object {
        const val KEY_THEME_STYLE = "theme_style"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_SAFE_MODE = "safe_mode"
        const val KEY_INTERNET = "internet_enabled"
        const val KEY_AUTO_APPLY = "auto_apply_edits"
        const val KEY_SPEECH_MODEL = "speech_model"
        const val KEY_VOICE_MODEL = "voice_model"
        const val KEY_SPEECH_RATE = "speech_rate"
    }
}
