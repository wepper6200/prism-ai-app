package ai.prism.ui

import ai.prism.domain.inference.PromptMessage
import ai.prism.domain.model.MessageRole
import ai.prism.resources.Res
import ai.prism.resources.document_context
import ai.prism.resources.document_page_label
import ai.prism.resources.image_prompt_instruction
import ai.prism.resources.image_review_instruction
import ai.prism.resources.image_safety_instruction
import ai.prism.resources.persona_answer
import ai.prism.resources.persona_question
import ai.prism.resources.persona_reminder
import ai.prism.resources.safe_mode_chat_rule
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
        safeModeRule = getString(Res.string.safe_mode_chat_rule),
        imageSafetyInstruction = getString(Res.string.image_safety_instruction),
        imageReviewInstruction = getString(Res.string.image_review_instruction),
        documentContext = getString(Res.string.document_context),
        pageLabel = getString(Res.string.document_page_label),
    )
}
