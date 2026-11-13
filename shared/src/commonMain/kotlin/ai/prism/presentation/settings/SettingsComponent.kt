package ai.prism.presentation.settings

import ai.prism.core.BuildInfo
import ai.prism.core.componentScope
import ai.prism.domain.model.AppSettings
import ai.prism.domain.model.DarkMode
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelKind
import ai.prism.domain.model.ModelStatus
import ai.prism.domain.model.PlatformCapabilities
import ai.prism.domain.model.TextEncoderMode
import ai.prism.domain.model.ThemeStyle
import ai.prism.domain.repository.ModelRepository
import ai.prism.domain.repository.SettingsRepository
import ai.prism.domain.update.AppUpdater
import ai.prism.domain.model.ModelFolder
import ai.prism.domain.model.ModelFolderState
import ai.prism.service.update.UpdateService
import ai.prism.service.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.coroutines.CoroutineContext

/** Модель Prism, открытая модель, на которой она основана, и её лицензия — для раздела «О приложении». */
data class ModelLicenseInfo(
    val name: String,
    val origin: String,
    val licenseName: String,
    val licenseUrl: String,
)

/** Скачанная модель речи для выбора в настройках. */
data class VoiceModelOption(val id: String, val name: String)

/** Раздел «Голос»: скачанные модели распознавания и голоса и какие из них выбраны. */
data class VoiceSettings(
    /** В каталоге есть модели речи — раздел показывается. */
    val available: Boolean = false,
    val recognizers: List<VoiceModelOption> = emptyList(),
    val selectedRecognizerId: String? = null,
    val voices: List<VoiceModelOption> = emptyList(),
    val selectedVoiceId: String? = null,
    /** Голоса выбранной модели синтеза (если их несколько) и выбранный. */
    val speakers: List<String> = emptyList(),
    val selectedSpeaker: Int = 0,
)

data class SettingsState(
    val settings: AppSettings,
    val supportsDynamicColor: Boolean,
    val versionName: String,
    val modelLicenses: List<ModelLicenseInfo> = emptyList(),
    val voice: VoiceSettings = VoiceSettings(),
    /** Скачанные модели видео (выбор показывается, если их несколько). */
    val videoModels: List<VoiceModelOption> = emptyList(),
    val selectedVideoModelId: String? = null,
    /** Скачана модель видео с текстовым энкодером, который можно сжать (LTX) — показывается выбор энкодера. */
    val textEncoderAvailable: Boolean = false,
    /** Энкодер, который будет использоваться (выбранный или по объёму памяти). */
    val textEncoderMode: TextEncoderMode = TextEncoderMode.FULL,
    /** Обновление приложения из настроек (Android, Windows). */
    val updatesSupported: Boolean = false,
    val update: UpdateState = UpdateState.Idle,
    /** Где хранятся модели (раздел показывается, если выбор поддерживается). */
    val modelFolder: ModelFolderState = ModelFolderState(),
)

sealed interface SettingsIntent {
    data class SelectThemeStyle(val style: ThemeStyle) : SettingsIntent
    data class SelectDarkMode(val mode: DarkMode) : SettingsIntent
    data class SetDynamicColor(val enabled: Boolean) : SettingsIntent
    data class SetSafeMode(val enabled: Boolean) : SettingsIntent
    data class SetInternetEnabled(val enabled: Boolean) : SettingsIntent
    data class SetAutoApplyEdits(val enabled: Boolean) : SettingsIntent
    data class SelectSpeechModel(val modelId: String) : SettingsIntent
    data class SelectVoiceModel(val modelId: String) : SettingsIntent
    data class SetSpeechRate(val rate: Float) : SettingsIntent
    data class SelectVideoModel(val modelId: String) : SettingsIntent
    data class SelectVoiceSpeaker(val speaker: Int) : SettingsIntent
    data class SelectTextEncoder(val mode: TextEncoderMode) : SettingsIntent
    data class SetContextLength(val tokens: Int) : SettingsIntent
    data class SetAgentSteps(val steps: Int) : SettingsIntent
    data class SetFolderDepth(val depth: Int) : SettingsIntent
    data object CheckForUpdates : SettingsIntent
    data object InstallUpdate : SettingsIntent
    data object CancelUpdate : SettingsIntent
    data class SetSharedModelFolder(val shared: Boolean) : SettingsIntent
    data class ChooseModelFolder(val path: String) : SettingsIntent
    data object ResetModelFolder : SettingsIntent
    data object Back : SettingsIntent
}

interface SettingsComponent {
    val state: StateFlow<SettingsState>

    fun onIntent(intent: SettingsIntent)
}

class DefaultSettingsComponent(
    componentContext: ComponentContext,
    private val settingsRepository: SettingsRepository,
    modelRepository: ModelRepository,
    capabilities: PlatformCapabilities,
    mainContext: CoroutineContext,
    private val onBack: () -> Unit,
    private val updates: UpdateService = UpdateService(AppUpdater.None, CoroutineScope(SupervisorJob())),
    private val modelFolder: ModelFolder = ModelFolder.None,
    /** Объём оперативной памяти: текстовый энкодер по умолчанию (0 — неизвестен). */
    private val totalRamBytes: Long = 0L,
) : SettingsComponent, ComponentContext by componentContext {

    private val scope = componentScope(mainContext)

    private fun toState(settings: AppSettings, supportsDynamicColor: Boolean) = SettingsState(
        settings = settings,
        supportsDynamicColor = supportsDynamicColor,
        versionName = BuildInfo.VERSION_NAME,
        updatesSupported = updates.supported,
    )

    override val state: StateFlow<SettingsState> =
        combine(settingsRepository.settings, modelRepository.models, updates.state, modelFolder.state) { settings, models, update, folder ->
            toState(settings, capabilities.supportsDynamicColor).copy(
                update = update,
                modelFolder = folder,
                modelLicenses = models
                    .map { entry ->
                        val model = entry.model
                        ModelLicenseInfo(model.name, "${model.family} ${model.parameters}", model.license.name, model.license.url)
                    }
                    .distinctBy { it.name },
                voice = voiceSettings(models, settings),
            ).let { state ->
                val downloadedVideos = models.filter { it.model.kind == ModelKind.VIDEO && it.status == ModelStatus.Downloaded }
                val videos = downloadedVideos.map { VoiceModelOption(it.model.id, it.model.name) }
                state.copy(
                    videoModels = videos,
                    textEncoderAvailable = downloadedVideos.any { entry ->
                        entry.model.components.any { it.role == TextEncoderMode.ROLE && TextEncoderMode.canCompress(it.fileName) }
                    },
                    textEncoderMode = settings.textEncoderMode ?: TextEncoderMode.auto(totalRamBytes),
                    selectedVideoModelId = (videos.firstOrNull { it.id == settings.videoModelId } ?: videos.firstOrNull())?.id,
                )
            }
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = toState(settingsRepository.settings.value, capabilities.supportsDynamicColor),
        )

    private fun voiceSettings(models: List<ModelEntry>, settings: AppSettings): VoiceSettings {
        fun downloaded(kind: ModelKind) = models
            .filter { it.model.kind == kind && it.status == ModelStatus.Downloaded }
            .map { VoiceModelOption(it.model.id, it.model.name) }
        val recognizers = downloaded(ModelKind.SPEECH_RECOGNITION)
        val voices = downloaded(ModelKind.SPEECH_SYNTHESIS)
        val voiceId = (voices.firstOrNull { it.id == settings.voiceModelId } ?: voices.firstOrNull())?.id
        val speakers = models.firstOrNull { it.model.id == voiceId }?.model?.speech?.speakers.orEmpty()
        return VoiceSettings(
            speakers = speakers,
            selectedSpeaker = settings.voiceSpeaker.takeIf { it in speakers.indices } ?: 0,
            available = models.any { it.model.kind == ModelKind.SPEECH_RECOGNITION || it.model.kind == ModelKind.SPEECH_SYNTHESIS },
            recognizers = recognizers,
            selectedRecognizerId = (recognizers.firstOrNull { it.id == settings.speechModelId } ?: recognizers.firstOrNull())?.id,
            voices = voices,
            selectedVoiceId = (voices.firstOrNull { it.id == settings.voiceModelId } ?: voices.firstOrNull())?.id,
        )
    }

    override fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SelectThemeStyle -> settingsRepository.setThemeStyle(intent.style)
            is SettingsIntent.SelectDarkMode -> settingsRepository.setDarkMode(intent.mode)
            is SettingsIntent.SetDynamicColor -> settingsRepository.setDynamicColor(intent.enabled)
            is SettingsIntent.SetSafeMode -> settingsRepository.setSafeMode(intent.enabled)
            is SettingsIntent.SetInternetEnabled -> settingsRepository.setInternetEnabled(intent.enabled)
            is SettingsIntent.SetAutoApplyEdits -> settingsRepository.setAutoApplyEdits(intent.enabled)
            is SettingsIntent.SelectSpeechModel -> settingsRepository.setSpeechModel(intent.modelId)
            is SettingsIntent.SelectVoiceModel -> settingsRepository.setVoiceModel(intent.modelId)
            is SettingsIntent.SetSpeechRate -> settingsRepository.setSpeechRate(intent.rate)
            is SettingsIntent.SelectVideoModel -> settingsRepository.setVideoModel(intent.modelId)
            is SettingsIntent.SelectVoiceSpeaker -> settingsRepository.setVoiceSpeaker(intent.speaker)
            is SettingsIntent.SelectTextEncoder -> settingsRepository.setTextEncoderMode(intent.mode)
            is SettingsIntent.SetContextLength -> settingsRepository.setContextLength(intent.tokens)
            is SettingsIntent.SetAgentSteps -> settingsRepository.setAgentSteps(intent.steps)
            is SettingsIntent.SetFolderDepth -> settingsRepository.setFolderDepth(intent.depth)
            SettingsIntent.CheckForUpdates -> updates.check()
            SettingsIntent.InstallUpdate -> updates.install()
            SettingsIntent.CancelUpdate -> updates.cancel()
            is SettingsIntent.SetSharedModelFolder -> modelFolder.setShared(intent.shared)
            is SettingsIntent.ChooseModelFolder -> modelFolder.choose(intent.path)
            SettingsIntent.ResetModelFolder -> modelFolder.resetToDefault()
            SettingsIntent.Back -> onBack()
        }
    }
}
