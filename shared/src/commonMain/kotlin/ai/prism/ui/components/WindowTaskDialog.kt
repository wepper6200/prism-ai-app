package ai.prism.ui.components

import ai.prism.domain.agent.DesktopWindow
import ai.prism.domain.agent.WorkspaceException
import ai.prism.resources.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

@Composable
fun WindowTaskDialog(
    loadWindows: suspend () -> List<DesktopWindow>,
    onStart: (DesktopWindow?, String, Boolean) -> Boolean,
    onDismiss: () -> Unit,
) {
    var windows by remember { mutableStateOf(emptyList<DesktopWindow>()) }
    var chosenWindow by remember { mutableStateOf<DesktopWindow?>(null) }
    var wholeDesktop by remember { mutableStateOf(false) }
    var automatic by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var startFailed by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        loading = true
        failed = false
        try {
            windows = loadWindows()
            chosenWindow = chosenWindow?.let { previous -> windows.find { it == previous } }
        } catch (_: WorkspaceException) {
            windows = emptyList()
            chosenWindow = null
            failed = true
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DesktopWindows, contentDescription = null) },
        title = { Text(stringResource(Res.string.computer_task_title)) },
        text = {
            Column(Modifier.testTag("window_task_dialog").verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !wholeDesktop, onClick = { wholeDesktop = false; startFailed = false },
                        label = { Text(stringResource(Res.string.window_scope_single)) })
                    FilterChip(selected = wholeDesktop, onClick = { wholeDesktop = true; startFailed = false },
                        label = { Text(stringResource(Res.string.window_scope_desktop)) }, modifier = Modifier.testTag("whole_desktop_scope"))
                }
                Text(stringResource(if (automatic) Res.string.window_automatic_hint
                    else if (wholeDesktop) Res.string.window_desktop_hint else Res.string.window_mode_hint))
                if (!wholeDesktop) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.window_mode_choose), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { reload++ }, enabled = !loading) {
                            Icon(Icons.Outlined.Refresh, stringResource(Res.string.window_mode_refresh))
                        }
                    }
                    when {
                        loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        failed -> Text(stringResource(Res.string.window_mode_error), color = MaterialTheme.colorScheme.error)
                        windows.isEmpty() -> Text(stringResource(Res.string.window_mode_empty))
                        else -> Column(
                            Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            windows.forEach { window ->
                                val active = chosenWindow == window
                                Surface(
                                    onClick = { chosenWindow = window; startFailed = false },
                                    modifier = Modifier.fillMaxWidth().semantics { selected = active },
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (active) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainer,
                                ) {
                                    ListItem(
                                        headlineContent = { Text(window.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { Text(window.app, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingContent = { RadioButton(selected = active, onClick = null) },
                                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = task,
                    onValueChange = { task = it; startFailed = false },
                    label = { Text(stringResource(Res.string.window_mode_task)) },
                    placeholder = { Text(stringResource(Res.string.window_mode_placeholder)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("window_task"),
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("window_automatic")
                        .toggleable(automatic, role = Role.Switch, onValueChange = { automatic = it }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(Res.string.window_automatic_option), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(checked = automatic, onCheckedChange = null)
                }
                if (startFailed) Text(stringResource(Res.string.window_mode_busy), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                enabled = task.isNotBlank() && (wholeDesktop || (!loading && chosenWindow != null)),
                onClick = { if (onStart(chosenWindow.takeUnless { wholeDesktop }, task, automatic)) onDismiss() else startFailed = true },
            ) { Text(stringResource(if (automatic) Res.string.window_automatic_start else Res.string.window_mode_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.window_mode_close)) } },
    )
}
