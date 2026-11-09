package ai.prism.presentation.settings

import ai.prism.core.BuildInfo
import ai.prism.core.componentScope
import ai.prism.domain.model.AppSettings
import ai.prism.domain.model.DarkMode
import ai.prism.domain.model.PlatformCapabilities
import ai.prism.domain.model.ThemeStyle
import ai.prism.domain.repository.ModelRepository
import ai.prism.domain.repository.SettingsRepository
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.coroutines.CoroutineContext

/** Семейство моделей и его лицензия — для раздела «О приложении». */
data class ModelLicenseInfo(
    val family: String,
    val licenseName: String,
    val licenseUrl: String,
)

data class SettingsState(
    val settings: AppSettings,
    val supportsDynamicColor: Boolean,
    val versionName: String,
    val modelLicenses: List<ModelLicenseInfo> = emptyList(),
)

sealed interface SettingsIntent {
    data class SelectThemeStyle(val style: ThemeStyle) : SettingsIntent
    data class SelectDarkMode(val mode: DarkMode) : SettingsIntent
    data class SetDynamicColor(val enabled: Boolean) : SettingsIntent
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
) : SettingsComponent, ComponentContext by componentContext {

    private val scope = componentScope(mainContext)

    private fun toState(settings: AppSettings, supportsDynamicColor: Boolean) = SettingsState(
        settings = settings,
        supportsDynamicColor = supportsDynamicColor,
        versionName = BuildInfo.VERSION_NAME,
    )

    override val state: StateFlow<SettingsState> =
        combine(settingsRepository.settings, modelRepository.models) { settings, models ->
            toState(settings, capabilities.supportsDynamicColor).copy(
                modelLicenses = models
                    .map { ModelLicenseInfo(it.model.family, it.model.license.name, it.model.license.url) }
                    .distinctBy { it.family },
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = toState(settingsRepository.settings.value, capabilities.supportsDynamicColor),
        )

    override fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SelectThemeStyle -> settingsRepository.setThemeStyle(intent.style)
            is SettingsIntent.SelectDarkMode -> settingsRepository.setDarkMode(intent.mode)
            is SettingsIntent.SetDynamicColor -> settingsRepository.setDynamicColor(intent.enabled)
            SettingsIntent.Back -> onBack()
        }
    }
}
