package ai.prism.data.settings

import ai.prism.domain.model.AppSettings
import ai.prism.domain.model.DarkMode
import ai.prism.domain.model.TextEncoderMode
import ai.prism.domain.model.ThemeStyle
import ai.prism.domain.model.ThinkingLevel
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

    override fun setVoiceSpeaker(speaker: Int) = update { it.copy(voiceSpeaker = speaker.coerceAtLeast(0)) }

    override fun setVideoModel(modelId: String) = update { it.copy(videoModelId = modelId) }

    override fun setVideoSeconds(seconds: Int) = update { it.copy(videoSeconds = seconds.coerceIn(0, MAX_VIDEO_SECONDS)) }

    override fun setTextEncoderMode(mode: TextEncoderMode?) = update { it.copy(textEncoderMode = mode) }

    override fun setContextLength(tokens: Int) = update { it.copy(contextLength = tokens.coerceIn(0, MAX_CONTEXT)) }

    override fun setAgentSteps(steps: Int) = update { it.copy(agentSteps = steps.coerceAtLeast(0)) }

    override fun setFolderDepth(depth: Int) = update { it.copy(folderDepth = depth.coerceAtLeast(0)) }

    override fun setThinkingLevel(level: ThinkingLevel) = update { it.copy(thinkingLevel = level) }

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
            voiceSpeaker = store.getString(KEY_VOICE_SPEAKER)?.toIntOrNull()?.coerceAtLeast(0) ?: defaults.voiceSpeaker,
            videoModelId = store.getString(KEY_VIDEO_MODEL) ?: defaults.videoModelId,
            videoSeconds = store.getString(KEY_VIDEO_SECONDS)?.toIntOrNull()?.coerceIn(0, MAX_VIDEO_SECONDS) ?: defaults.videoSeconds,
            textEncoderMode = enumValues<TextEncoderMode>().firstOrNull { it.name == store.getString(KEY_TEXT_ENCODER) },
            contextLength = store.getString(KEY_CONTEXT)?.toIntOrNull()?.coerceIn(0, MAX_CONTEXT) ?: defaults.contextLength,
            agentSteps = store.getString(KEY_AGENT_STEPS)?.toIntOrNull()?.coerceAtLeast(0) ?: defaults.agentSteps,
            folderDepth = store.getString(KEY_FOLDER_DEPTH)?.toIntOrNull()?.coerceAtLeast(0) ?: defaults.folderDepth,
            thinkingLevel = parseEnum(store.getString(KEY_THINKING), defaults.thinkingLevel),
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
        store.putString(KEY_VOICE_SPEAKER, settings.voiceSpeaker.toString())
        store.putString(KEY_VIDEO_MODEL, settings.videoModelId)
        store.putString(KEY_VIDEO_SECONDS, settings.videoSeconds.toString())
        store.putString(KEY_TEXT_ENCODER, settings.textEncoderMode?.name.orEmpty())
        store.putString(KEY_CONTEXT, settings.contextLength.toString())
        store.putString(KEY_AGENT_STEPS, settings.agentSteps.toString())
        store.putString(KEY_FOLDER_DEPTH, settings.folderDepth.toString())
        store.putString(KEY_THINKING, settings.thinkingLevel.name)
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
        const val KEY_VOICE_SPEAKER = "voice_speaker"
        const val KEY_VIDEO_MODEL = "video_model"
        const val KEY_VIDEO_SECONDS = "video_seconds"
        const val KEY_TEXT_ENCODER = "text_encoder_mode"
        const val KEY_CONTEXT = "context_length"
        const val KEY_AGENT_STEPS = "agent_steps"
        const val KEY_FOLDER_DEPTH = "folder_depth"
        const val KEY_THINKING = "thinking_level"
        const val MAX_CONTEXT = 131_072
        const val MAX_VIDEO_SECONDS = 60
    }
}
