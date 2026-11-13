package ai.prism.domain.repository

import ai.prism.domain.model.AppSettings
import ai.prism.domain.model.DarkMode
import ai.prism.domain.model.TextEncoderMode
import ai.prism.domain.model.ThemeStyle
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    fun setThemeStyle(style: ThemeStyle)

    fun setDarkMode(mode: DarkMode)

    fun setDynamicColor(enabled: Boolean)

    fun setSafeMode(enabled: Boolean)

    fun setInternetEnabled(enabled: Boolean)

    fun setAutoApplyEdits(enabled: Boolean)

    fun setSpeechModel(modelId: String)

    fun setVoiceModel(modelId: String)

    fun setSpeechRate(rate: Float)

    fun setVoiceSpeaker(speaker: Int)

    fun setVideoModel(modelId: String)

    fun setVideoSeconds(seconds: Int)

    /** Текстовый энкодер видео; `null` — выбирать по объёму оперативной памяти. */
    fun setTextEncoderMode(mode: TextEncoderMode?)

    /** Контекст модели чата; 0 — как в каталоге. */
    fun setContextLength(tokens: Int)

    /** Шаги агента в одном ответе; 0 — без ограничения. */
    fun setAgentSteps(steps: Int)

    /** Глубина просмотра папок; 0 — без ограничения. */
    fun setFolderDepth(depth: Int)
}
