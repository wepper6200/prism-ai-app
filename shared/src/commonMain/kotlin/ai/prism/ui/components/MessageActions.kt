package ai.prism.ui.components

import ai.prism.resources.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

/** The same actions are reachable by touch, long press, keyboard and accessibility. */
@Composable
fun MessageActions(text: String, onEdit: ((String) -> Unit)? = null, onSpeak: (() -> Unit)? = null, content: @Composable () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var select by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    val copy = rememberTextCopier()
    val haptic = LocalHapticFeedback.current
    Box {
        Box(Modifier.combinedClickable(
            onClickLabel = stringResource(Res.string.message_actions),
            onClick = { menu = true },
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
        ).testTag(if (onEdit == null) "assistant_message_actions" else "user_message_actions")) { content() }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(Res.string.message_copy_text)) }, onClick = { copy(text); menu = false }, modifier = Modifier.testTag("message_menu_copy"))
            DropdownMenuItem(text = { Text(stringResource(Res.string.message_select)) }, onClick = { menu = false; select = true }, modifier = Modifier.testTag("message_menu_select"))
            if (onSpeak != null) DropdownMenuItem(text = { Text(stringResource(Res.string.message_speak)) }, onClick = { menu = false; onSpeak() })
            if (onEdit != null) DropdownMenuItem(text = { Text(stringResource(Res.string.message_edit_copy)) }, onClick = { menu = false; edit = true }, modifier = Modifier.testTag("message_menu_edit"))
        }
    }
    if (select) AlertDialog(
        onDismissRequest = { select = false },
        title = { Text(stringResource(Res.string.message_select)) },
        text = {
            SelectionContainer {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 400.dp)) { item { Text(text, style = MaterialTheme.typography.bodyLarge) } }
            }
        },
        confirmButton = { TextButton(onClick = { select = false }) { Text(stringResource(Res.string.action_cancel)) } },
    )
    if (edit) {
        var revised by remember(text) { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text(stringResource(Res.string.message_edit_copy)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.message_edit_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(revised, { revised = it }, label = { Text(stringResource(Res.string.input_placeholder)) }, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).testTag("message_edit_input"))
                }
            },
            confirmButton = { TextButton(onClick = { onEdit?.invoke(revised); edit = false }, enabled = revised.isNotBlank(), modifier = Modifier.testTag("message_edit_apply")) { Text(stringResource(Res.string.message_to_draft)) } },
            dismissButton = { TextButton(onClick = { edit = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}
