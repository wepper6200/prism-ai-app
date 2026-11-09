package ai.prism.presentation.models

import com.arkivanov.decompose.ComponentContext

sealed interface ModelsIntent {
    data object Back : ModelsIntent
}

interface ModelsComponent {
    fun onIntent(intent: ModelsIntent)
}

class DefaultModelsComponent(
    componentContext: ComponentContext,
    private val onBack: () -> Unit,
) : ModelsComponent, ComponentContext by componentContext {

    override fun onIntent(intent: ModelsIntent) {
        when (intent) {
            ModelsIntent.Back -> onBack()
        }
    }
}
