package ai.prism.ui

import ai.prism.resources.*
import ai.prism.domain.model.GenerationProfile
import ai.prism.domain.agent.AgentTool
import ai.prism.resources.agent_instructions
import ai.prism.resources.agent_instructions_qwen_coder
import ai.prism.resources.agent_workspace_note
import ai.prism.resources.agent_workspace_action_retry
import ai.prism.resources.agent_workspace_action_incomplete
import ai.prism.resources.agent_workspace_action_rejected
import ai.prism.resources.agent_internet_note
import ai.prism.resources.agent_computer_note
import ai.prism.resources.agent_screen_note
import ai.prism.resources.agent_mcp_note
import ai.prism.resources.agent_step_mcp
import ai.prism.resources.agent_tool_click
import ai.prism.resources.agent_tool_press_keys
import ai.prism.resources.agent_tool_screenshot
import ai.prism.resources.agent_tool_scroll
import ai.prism.resources.agent_tool_type_text
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
import ai.prism.resources.agent_tool_system_info
import ai.prism.resources.agent_tool_open_program
import org.jetbrains.compose.resources.StringResource
import ai.prism.domain.inference.PromptMessage
import ai.prism.domain.model.MessageRole
import ai.prism.resources.music_prompt_instruction
import ai.prism.resources.music_safety_instruction
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
import ai.prism.resources.window_tool_description
import ai.prism.resources.window_desktop_note
import ai.prism.resources.window_task_instructions
import ai.prism.resources.window_next_action
import ai.prism.resources.window_incomplete
import ai.prism.resources.window_no_input
import ai.prism.resources.window_input_report
import ai.prism.resources.window_check_result
import ai.prism.resources.window_automatic_changed
import ai.prism.resources.window_automatic_draft
import ai.prism.resources.window_declined
import ai.prism.service.AssistantPersona
import ai.prism.service.PersonaProvider
import org.jetbrains.compose.resources.getString

/** Личность Prism AI из строковых ресурсов — на языке интерфейса. */
class ResourcePersonaProvider : PersonaProvider {
    override suspend fun persona(): AssistantPersona = AssistantPersona(
        systemPrompt = getString(Res.string.system_prompt),
        profileInstructions = mapOf(
            GenerationProfile.CODING to getString(Res.string.work_profile_coding_instruction),
            GenerationProfile.CONVERSATION to getString(Res.string.work_profile_conversation_instruction),
            GenerationProfile.CREATIVE to getString(Res.string.work_profile_creative_instruction),
        ),
        benchmarkPrompt = getString(Res.string.work_bench_prompt),
        branchTitle = getString(Res.string.work_branch_name),
        verificationRequest = getString(Res.string.work_verify_request),
        verificationRetry = getString(Res.string.work_verify_retry),
        verificationIncomplete = getString(Res.string.work_verify_incomplete),
        primer = listOf(
            PromptMessage(MessageRole.USER, getString(Res.string.persona_question)),
            PromptMessage(MessageRole.ASSISTANT, getString(Res.string.persona_answer)),
        ),
        reminder = getString(Res.string.persona_reminder),
        currentDateNote = getString(Res.string.current_date_note),
        imagePromptInstruction = getString(Res.string.image_prompt_instruction),
        musicPromptInstruction = getString(Res.string.music_prompt_instruction),
        musicSafetyInstruction = getString(Res.string.music_safety_instruction),
        safeModeRule = getString(Res.string.safe_mode_chat_rule),
        imageSafetyInstruction = getString(Res.string.image_safety_instruction),
        imageReviewInstruction = getString(Res.string.image_review_instruction),
        documentContext = getString(Res.string.document_context),
        pageLabel = getString(Res.string.document_page_label),
        agentInstructions = getString(Res.string.agent_instructions),
        agentInstructionsQwenCoder = getString(Res.string.agent_instructions_qwen_coder),
        agentWorkspaceNote = getString(Res.string.agent_workspace_note),
        agentWorkspaceActionRetry = getString(Res.string.agent_workspace_action_retry),
        agentWorkspaceActionIncomplete = getString(Res.string.agent_workspace_action_incomplete),
        agentWorkspaceActionRejected = getString(Res.string.agent_workspace_action_rejected),
        agentInternetNote = getString(Res.string.agent_internet_note),
        agentComputerNote = getString(Res.string.agent_computer_note),
        agentScreenNote = getString(Res.string.agent_screen_note),
        agentMcpNote = getString(Res.string.agent_mcp_note),
        desktopScopeNote = getString(Res.string.window_desktop_note),
        windowTaskInstructions = getString(Res.string.window_task_instructions),
        windowNextAction = getString(Res.string.window_next_action),
        windowIncomplete = getString(Res.string.window_incomplete),
        windowNoInput = getString(Res.string.window_no_input),
        windowInputReport = getString(Res.string.window_input_report),
        windowCheckResult = getString(Res.string.window_check_result),
        windowAutomaticChanged = getString(Res.string.window_automatic_changed),
        windowAutomaticDraft = getString(Res.string.window_automatic_draft),
        windowDeclined = getString(Res.string.window_declined),
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
    AgentTool.SYSTEM_INFO -> Res.string.agent_tool_system_info
    AgentTool.OPEN_PROGRAM -> Res.string.agent_tool_open_program
    AgentTool.SCREENSHOT -> Res.string.agent_tool_screenshot
    AgentTool.CLICK -> Res.string.agent_tool_click
    AgentTool.TYPE_TEXT -> Res.string.agent_tool_type_text
    AgentTool.PRESS_KEYS -> Res.string.agent_tool_press_keys
    AgentTool.SCROLL -> Res.string.agent_tool_scroll
    // Инструменты MCP описывает сам сервер; эта строка в промпт не попадает.
    AgentTool.MCP -> Res.string.agent_step_mcp
    AgentTool.WINDOW_CONTROL -> Res.string.window_tool_description
}
