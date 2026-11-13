package ai.prism.ui.components

import ai.prism.domain.agent.AgentStep
import ai.prism.domain.agent.AgentStepStatus
import ai.prism.domain.agent.AgentTool
import ai.prism.domain.agent.PendingAction
import ai.prism.domain.model.ThinkingLevel
import ai.prism.resources.Res
import ai.prism.resources.agent_apply
import ai.prism.resources.agent_approval_command
import ai.prism.resources.agent_approval_download
import ai.prism.resources.agent_approval_edit
import ai.prism.resources.agent_reject
import ai.prism.resources.agent_run
import ai.prism.resources.agent_status_failed
import ai.prism.resources.agent_status_rejected
import ai.prism.resources.agent_step_download_file
import ai.prism.resources.agent_step_edit_file
import ai.prism.resources.agent_step_fetch_page
import ai.prism.resources.agent_step_list_files
import ai.prism.resources.agent_step_read_file
import ai.prism.resources.agent_step_run_command
import ai.prism.resources.agent_step_search_files
import ai.prism.resources.agent_step_web_search
import ai.prism.resources.agent_step_write_file
import ai.prism.resources.chat_internet
import ai.prism.resources.chat_thinking_level
import ai.prism.resources.thinking_high
import ai.prism.resources.thinking_low
import ai.prism.resources.thinking_medium
import ai.prism.resources.thinking_off
import ai.prism.resources.chat_workspace_choose
import ai.prism.resources.chat_workspace_remove
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Шаги ассистента под ответом: инструмент, кратко что сделано; нажатие раскрывает подробности. */
@Composable
fun AgentSteps(steps: List<AgentStep>, modifier: Modifier = Modifier) {
    Column(modifier.testTag("agent_steps"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEach { step -> StepRow(step) }
    }
}

@Composable
private fun StepRow(step: AgentStep) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = step.detail.isNotBlank()) { expanded = !expanded }
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(step.tool.icon(), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(step.tool.label()),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = step.title,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            when (step.status) {
                AgentStepStatus.DONE -> Unit
                AgentStepStatus.REJECTED -> Text(
                    stringResource(Res.string.agent_status_rejected),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AgentStepStatus.FAILED -> Text(
                    stringResource(Res.string.agent_status_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        AnimatedVisibility(expanded) { DetailBox(step.detail, Modifier.padding(start = 26.dp, top = 4.dp)) }
    }
}

/** Запрос разрешения: что хочет сделать ассистент (разница «было/стало» или команда) и кнопки. */
@Composable
fun PendingActionCard(action: PendingAction, onResolve: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag("pending_action"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(action.tool.icon(), contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    text = stringResource(
                        when (action.tool) {
                            AgentTool.RUN_COMMAND -> Res.string.agent_approval_command
                            AgentTool.DOWNLOAD_FILE -> Res.string.agent_approval_download
                            else -> Res.string.agent_approval_edit
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(action.title, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            if (action.detail.isNotBlank() && action.detail != action.title) DetailBox(action.detail)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onResolve(true) }, modifier = Modifier.testTag("pending_approve")) {
                    Text(stringResource(if (action.tool == AgentTool.RUN_COMMAND) Res.string.agent_run else Res.string.agent_apply))
                }
                OutlinedButton(onClick = { onResolve(false) }, modifier = Modifier.testTag("pending_reject")) {
                    Text(stringResource(Res.string.agent_reject))
                }
            }
        }
    }
}

@Composable
private fun DetailBox(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            )
        }
    }
}

/** Над полем ввода: папка проекта диалога, переключатель интернета и уровень размышлений (`null` — модель не умеет). */
@Composable
fun AgentContextRow(
    workspaceName: String?,
    internetEnabled: Boolean,
    onPickWorkspace: () -> Unit,
    onRemoveWorkspace: () -> Unit,
    onToggleInternet: () -> Unit,
    thinkingLevel: ThinkingLevel? = null,
    onSelectThinking: (ThinkingLevel) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (workspaceName == null) {
            FilterChip(
                selected = false,
                onClick = onPickWorkspace,
                label = { Text(stringResource(Res.string.chat_workspace_choose)) },
                leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("workspace_chip"),
            )
        } else {
            InputChip(
                selected = true,
                onClick = onPickWorkspace,
                label = { Text(workspaceName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(Res.string.chat_workspace_remove),
                        modifier = Modifier.size(18.dp).clickable(onClick = onRemoveWorkspace).testTag("workspace_remove"),
                    )
                },
                modifier = Modifier.testTag("workspace_chip"),
            )
        }
        FilterChip(
            selected = internetEnabled,
            onClick = onToggleInternet,
            label = { Text(stringResource(Res.string.chat_internet)) },
            leadingIcon = { Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.testTag("internet_toggle"),
        )
        if (thinkingLevel != null) ThinkingChip(thinkingLevel, onSelectThinking)
    }
}

/** Уровень размышлений: на чипе — текущий, по нажатию — выбор (та же настройка, что в «Настройки → Ассистент»). */
@Composable
private fun ThinkingChip(level: ThinkingLevel, onSelect: (ThinkingLevel) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = level != ThinkingLevel.OFF,
            onClick = { open = true },
            label = { Text(stringResource(Res.string.chat_thinking_level, thinkingName(level))) },
            leadingIcon = { Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.testTag("thinking_chip"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ThinkingLevel.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(thinkingName(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    modifier = Modifier.testTag("thinking_${option.name}"),
                )
            }
        }
    }
}

@Composable
private fun thinkingName(level: ThinkingLevel): String = stringResource(
    when (level) {
        ThinkingLevel.OFF -> Res.string.thinking_off
        ThinkingLevel.LOW -> Res.string.thinking_low
        ThinkingLevel.MEDIUM -> Res.string.thinking_medium
        ThinkingLevel.HIGH -> Res.string.thinking_high
    },
)

private fun AgentTool.icon(): ImageVector = when (this) {
    AgentTool.WEB_SEARCH -> Icons.Outlined.Search
    AgentTool.FETCH_PAGE -> Icons.Outlined.Language
    AgentTool.DOWNLOAD_FILE -> Icons.Outlined.Download
    AgentTool.LIST_FILES -> Icons.Outlined.Folder
    AgentTool.READ_FILE -> Icons.Outlined.Description
    AgentTool.SEARCH_FILES -> Icons.AutoMirrored.Outlined.ManageSearch
    AgentTool.WRITE_FILE, AgentTool.EDIT_FILE -> Icons.Outlined.Edit
    AgentTool.RUN_COMMAND -> Icons.Outlined.Terminal
}

private fun AgentTool.label(): StringResource = when (this) {
    AgentTool.WEB_SEARCH -> Res.string.agent_step_web_search
    AgentTool.FETCH_PAGE -> Res.string.agent_step_fetch_page
    AgentTool.DOWNLOAD_FILE -> Res.string.agent_step_download_file
    AgentTool.LIST_FILES -> Res.string.agent_step_list_files
    AgentTool.READ_FILE -> Res.string.agent_step_read_file
    AgentTool.SEARCH_FILES -> Res.string.agent_step_search_files
    AgentTool.WRITE_FILE -> Res.string.agent_step_write_file
    AgentTool.EDIT_FILE -> Res.string.agent_step_edit_file
    AgentTool.RUN_COMMAND -> Res.string.agent_step_run_command
}
