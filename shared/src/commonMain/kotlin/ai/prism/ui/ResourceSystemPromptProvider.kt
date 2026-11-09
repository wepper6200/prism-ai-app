package ai.prism.ui

import ai.prism.resources.Res
import ai.prism.resources.system_prompt
import ai.prism.service.SystemPromptProvider
import org.jetbrains.compose.resources.getString

/** Системный промпт из строковых ресурсов — на языке интерфейса. */
class ResourceSystemPromptProvider : SystemPromptProvider {
    override suspend fun systemPrompt(): String = getString(Res.string.system_prompt)
}
