package ai.prism.ui.components

import ai.prism.domain.music.MusicStudioDraft
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MusicStudio(value: MusicStudioDraft, enabled: Boolean, onChange: (MusicStudioDraft) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag("music_studio_open")) { Text(stringResource(Res.string.music_studio)) }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.testTag("music_studio_panel").fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.music_studio), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.music_studio_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value.style, { onChange(value.copy(style = it.take(2000))) }, label = { Text(stringResource(Res.string.music_style)) }, modifier = Modifier.fillMaxWidth().testTag("music_studio_style"), maxLines = 4)
            Text(stringResource(Res.string.music_language), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to Res.string.music_auto_language, "ru" to Res.string.music_russian, "en" to Res.string.music_english, "es" to Res.string.music_spanish, "fr" to Res.string.music_french, "de" to Res.string.music_german).forEach { (language, label) ->
                    FilterChip(value.language == language, onClick = { onChange(value.copy(language = language)) }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("music_language_" + language))
                }
            }
            Row {
                Checkbox(value.instrumental, { onChange(value.copy(instrumental = it)) }, modifier = Modifier.testTag("music_instrumental"))
                Text(stringResource(Res.string.music_instrumental), modifier = Modifier.padding(top = 12.dp))
            }
            if (!value.instrumental) OutlinedTextField(value.lyrics, { onChange(value.copy(lyrics = it.take(24000))) }, label = { Text(stringResource(Res.string.music_lyrics)) }, supportingText = { Text(stringResource(Res.string.music_studio_lyrics_hint)) }, minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth().testTag("music_studio_lyrics"))
            OutlinedTextField(value.structure, { onChange(value.copy(structure = it.take(1000))) }, label = { Text(stringResource(Res.string.music_structure)) }, modifier = Modifier.fillMaxWidth().testTag("music_studio_structure"), maxLines = 4)
            val songStructure = stringResource(Res.string.music_structure_song)
            val instrumentalStructure = stringResource(Res.string.music_structure_instrumental)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(onClick = { onChange(value.copy(structure = songStructure)) }, label = { Text(songStructure) })
                SuggestionChip(onClick = { onChange(value.copy(structure = instrumentalStructure)) }, label = { Text(instrumentalStructure) })
            }
            FilledTonalButton(onClick = { open = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.music_save_studio)) }
            TextButton(onClick = { onChange(MusicStudioDraft()) }) { Text(stringResource(Res.string.music_studio_reset)) }
        }
    }
}
