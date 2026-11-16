package ai.prism.ui.components

import ai.prism.presentation.chat.MessageItem
import ai.prism.domain.agent.AgentTool
import ai.prism.domain.agent.AgentStepStatus
import ai.prism.service.agent.ProjectTaskProgress
import ai.prism.service.agent.TaskStageStatus
import ai.prism.resources.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectTaskCard(message: MessageItem, enabled: Boolean, canUndo: Boolean, canTest: Boolean, onUndo: () -> Unit, onVerify: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val stages = ProjectTaskProgress.stages(message.steps, message.status, message.isStreaming)
    OutlinedCard(Modifier.fillMaxWidth().testTag("project_plan")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.work_plan), style = MaterialTheme.typography.titleSmall)
            listOf(Res.string.work_prepare, Res.string.work_modify, Res.string.work_check).forEachIndexed { index, label ->
                val state = when (stages[index]) {
                    TaskStageStatus.WAITING -> Res.string.work_waiting
                    TaskStageStatus.RUNNING -> Res.string.work_running
                    TaskStageStatus.DONE -> Res.string.work_done
                    TaskStageStatus.NEEDS_HELP -> Res.string.work_help
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(state), style = MaterialTheme.typography.labelMedium)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canUndo) TextButton(onClick = { confirm = true }, enabled = enabled, modifier = Modifier.testTag("project_undo")) { Text(stringResource(Res.string.work_undo)) }
                if (canTest && message.steps.any { it.verified && it.status == AgentStepStatus.DONE }) TextButton(onClick = onVerify, enabled = enabled, modifier = Modifier.testTag("project_verify")) { Text(stringResource(Res.string.work_verify_tests)) }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(Res.string.work_undo_title)) }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.work_undo_hint))
            Text(message.steps.filter { it.tool == AgentTool.WRITE_FILE || it.tool == AgentTool.EDIT_FILE }.map { it.title }.distinct().take(8).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { confirm = false; onUndo() }, enabled = enabled, modifier = Modifier.testTag("project_undo_confirm")) { Text(stringResource(Res.string.work_undo_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.action_cancel)) } })
}
