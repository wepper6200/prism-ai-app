package ai.prism.ui.components

import ai.prism.presentation.chat.ChatState
import ai.prism.resources.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickModelPicker(state: ChatState, onSelect: (String) -> Unit, onCatalog: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("quick_model_picker")) {
        Icon(Icons.Outlined.Memory, stringResource(Res.string.model_quick_select))
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(bottom = 24.dp).testTag("quick_models_panel")) {
            Text(stringResource(Res.string.model_quick_select), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
            state.modelLoadingProgress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            }
            Text(
                stringResource(if (state.isGenerating) Res.string.model_answer_status else if (state.modelDownloaded) Res.string.model_ready_status else Res.string.model_download_status),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 360.dp)) {
                items(state.modelChoices, key = { it.model.id }) { entry ->
                    val enabled = entry.chatReady && !state.isGenerating && !state.busyInOtherChat
                    ListItem(
                        headlineContent = { Text(entry.model.name) },
                        supportingContent = {
                            Text(stringResource(if (entry.model.id == state.activeModelId) Res.string.model_current else if (entry.chatReady) Res.string.model_ready_status else Res.string.model_download_status))
                        },
                        leadingContent = { Icon(Icons.Outlined.Memory, null) },
                        trailingContent = { if (entry.model.id == state.activeModelId) Icon(Icons.Outlined.Check, null) },
                        modifier = Modifier.clickable(enabled = enabled) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelect(entry.model.id)
                            open = false
                        }.testTag("quick_model_" + entry.model.id),
                    )
                }
            }
            TextButton(onClick = { open = false; onCatalog() }, modifier = Modifier.padding(horizontal = 12.dp).testTag("quick_model_catalog")) {
                Text(stringResource(Res.string.nav_models))
            }
        }
    }
}
