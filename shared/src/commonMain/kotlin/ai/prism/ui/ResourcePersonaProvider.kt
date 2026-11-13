package ai.prism.ui

import ai.prism.domain.agent.AgentTool
import ai.prism.resources.agent_instructions
import ai.prism.resources.agent_workspace_note
import ai.prism.resources.agent_internet_note
import ai.prism.resources.agent_step_limit
import ai.prism.resources.agent_step_limit_notice
import ai.prism.resources.memory_extraction
import ai.prism.resources.memory_note
import ai.prism.resources.summary_instruction
import ai.prism.resources.summary_note
import ai.prism.resources.agent_tool_web_search
import ai.prism.resources.agent_tool_fetch_page
import ai.prism.resources.agent_tool_download_file
import ai.prism.resources.agent_tool_list_files
import ai.prism.resources.agent_tool_read_file
import ai.prism.resources.agent_tool_search_files
import ai.prism.resources.agent_tool_write_file
import ai.prism.resources.agent_tool_edit_file
import ai.prism.resources.agent_tool_run_command
import org.jetbrains.compose.resources.StringResource
import ai.prism.domain.inference.PromptMessage
import ai.prism.domain.model.MessageRole
import ai.prism.resources.Res
import ai.prism.resources.current_date_note
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
        currentDateNote = getString(Res.string.current_date_note),
        imagePromptInstruction = getString(Res.string.image_prompt_instruction),
        safeModeRule = getString(Res.string.safe_mode_chat_rule),
        imageSafetyInstruction = getString(Res.string.image_safety_instruction),
        imageReviewInstruction = getString(Res.string.image_review_instruction),
        documentContext = getString(Res.string.document_context),
        pageLabel = getString(Res.string.document_page_label),
        agentInstructions = getString(Res.string.agent_instructions),
        agentWorkspaceNote = getString(Res.string.agent_workspace_note),
        agentInternetNote = getString(Res.string.agent_internet_note),
        toolDescriptions = AgentTool.entries.associateWith { tool -> getString(tool.description()) },
        agentStepLimit = getString(Res.string.agent_step_limit),
        agentStepLimitNotice = getString(Res.string.agent_step_limit_notice),
        memoryNote = getString(Res.string.memory_note),
        memoryExtraction = getString(Res.string.memory_extraction),
        summaryInstruction = getString(Res.string.summary_instruction),
        summaryNote = getString(Res.string.summary_note),
    )
}

/** Описание инструмента для модели (на языке интерфейса). */
internal fun AgentTool.description(): StringResource = when (this) {
    AgentTool.WEB_SEARCH -> Res.string.agent_tool_web_search
    AgentTool.FETCH_PAGE -> Res.string.agent_tool_fetch_page
    AgentTool.DOWNLOAD_FILE -> Res.string.agent_tool_download_file
    AgentTool.LIST_FILES -> Res.string.agent_tool_list_files
    AgentTool.READ_FILE -> Res.string.agent_tool_read_file
    AgentTool.SEARCH_FILES -> Res.string.agent_tool_search_files
    AgentTool.WRITE_FILE -> Res.string.agent_tool_write_file
    AgentTool.EDIT_FILE -> Res.string.agent_tool_edit_file
    AgentTool.RUN_COMMAND -> Res.string.agent_tool_run_command
}
