package ai.prism.ui

import ai.prism.domain.inference.PromptMessage
import ai.prism.domain.model.MessageRole
import ai.prism.resources.Res
import ai.prism.resources.image_prompt_instruction
import ai.prism.resources.persona_answer
import ai.prism.resources.persona_question
import ai.prism.resources.persona_reminder
import ai.prism.resources.system_prompt
import ai.prism.service.AssistantPersona
import ai.prism.service.PersonaProvider
import org.jetbrains.compose.resources.getString

/** Личность Prism AI из строковых ресурсов — на языке интерфейса. */
class ResourcePersonaProvider : PersonaProvider {
    override suspend fun persona(): AssistantPersona = AssistantPersona(
        systemPrompt = getString(Res.string.system_prompt),
        primer = listOf(
            PromptMessage(MessageRole.USER, getString(Res.string.persona_question)),
            PromptMessage(MessageRole.ASSISTANT, getString(Res.string.persona_answer)),
        ),
        reminder = getString(Res.string.persona_reminder),
        imagePromptInstruction = getString(Res.string.image_prompt_instruction),
    )
}
