package ai.prism.presentation.chat

import ai.prism.domain.model.GenerationProfile
import ai.prism.domain.music.MusicStudioDraft
import ai.prism.service.WorkbenchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ChatWorkbench(val work: WorkbenchState = WorkbenchState(),
    val profile: GenerationProfile = GenerationProfile.STANDARD, val music: MusicStudioDraft = MusicStudioDraft())
internal val EmptyChatWorkbench: StateFlow<ChatWorkbench> = MutableStateFlow(ChatWorkbench())
