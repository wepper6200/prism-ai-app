package ai.prism.presentation.models

import ai.prism.core.componentScope
import ai.prism.domain.inference.EngineState
import ai.prism.domain.inference.InferenceEngine
import ai.prism.domain.model.DeviceInfo
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.repository.ModelRepository
import ai.prism.domain.repository.SettingsRepository
import ai.prism.service.ChatGenerationService
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

data class ModelsState(
    val entries: List<ModelEntry> = emptyList(),
    val activeModelId: String? = null,
    val engineState: EngineState = EngineState.Idle,
    val totalRamBytes: Long = 0,
    /** Ассистенту разрешён интернет (та же настройка, что в «Настройках»). */
    val internetEnabled: Boolean = false,
)

sealed interface ModelsIntent {
    data class Download(val modelId: String) : ModelsIntent
    data class Pause(val modelId: String) : ModelsIntent
    data class Delete(val modelId: String) : ModelsIntent
    data class Activate(val modelId: String) : ModelsIntent
    data class SetInternetEnabled(val enabled: Boolean) : ModelsIntent
    data object Back : ModelsIntent
}

interface ModelsComponent {
    val state: StateFlow<ModelsState>

    fun onIntent(intent: ModelsIntent)
}

class DefaultModelsComponent(
    componentContext: ComponentContext,
    private val modelRepository: ModelRepository,
    private val engine: InferenceEngine,
    private val generation: ChatGenerationService,
    private val settingsRepository: SettingsRepository,
    deviceInfo: DeviceInfo,
    mainContext: CoroutineContext,
    private val onBack: () -> Unit,
) : ModelsComponent, ComponentContext by componentContext {

    private val scope = componentScope(mainContext)

    override val state: StateFlow<ModelsState> = combine(
        modelRepository.models,
        modelRepository.activeModelId,
        engine.state,
        settingsRepository.settings,
    ) { entries, activeId, engineState, settings ->
        ModelsState(entries, activeId, engineState, deviceInfo.totalRamBytes, settings.internetEnabled)
    }.stateIn(scope, SharingStarted.Eagerly, ModelsState(totalRamBytes = deviceInfo.totalRamBytes))

    override fun onIntent(intent: ModelsIntent) {
        when (intent) {
            is ModelsIntent.Download -> modelRepository.download(intent.modelId)
            is ModelsIntent.Pause -> modelRepository.pause(intent.modelId)
            is ModelsIntent.Activate -> modelRepository.setActive(intent.modelId)
            is ModelsIntent.SetInternetEnabled -> settingsRepository.setInternetEnabled(intent.enabled)
            is ModelsIntent.Delete -> scope.launch {
                // Файл нельзя удалять, пока модель открыта движком.
                if (engineUses(intent.modelId)) {
                    generation.stop()
                    engine.unload()
                }
                modelRepository.delete(intent.modelId)
            }
            ModelsIntent.Back -> onBack()
        }
    }

    private fun engineUses(modelId: String): Boolean = when (val engineState = engine.state.value) {
        is EngineState.Ready -> engineState.info.modelId == modelId
        is EngineState.Loading -> engineState.modelId == modelId
        else -> false
    }
}
