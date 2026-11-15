package ai.prism.ui.components

import ai.prism.domain.music.MusicRequest
import ai.prism.resources.Res
import ai.prism.resources.action_cancel
import ai.prism.resources.music_duration
import ai.prism.resources.music_duration_apply
import ai.prism.resources.music_duration_custom
import ai.prism.resources.music_duration_limit
import ai.prism.resources.music_minutes
import ai.prism.resources.music_minutes_label
import ai.prism.resources.music_minutes_seconds
import ai.prism.resources.music_seconds_label
import ai.prism.resources.video_seconds
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

@Composable
private fun durationLabel(seconds: Int): String = when {
    seconds < 60 -> stringResource(Res.string.video_seconds, seconds)
    seconds % 60 == 0 -> stringResource(Res.string.music_minutes, seconds / 60)
    else -> stringResource(Res.string.music_minutes_seconds, seconds / 60, seconds % 60)
}

/** Компактный выбор времени: пресеты и точное значение в минутах и секундах. */
@Composable
fun MusicDurationSelector(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var minutes by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf("") }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag("music_duration")) {
            Text(stringResource(Res.string.music_duration, durationLabel(selected)))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MusicRequest.DURATIONS.forEach { value ->
                DropdownMenuItem(
                    text = { Text(durationLabel(value)) },
                    onClick = { expanded = false; onSelect(value) },
                    modifier = Modifier.testTag("music_seconds_$value"),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.music_duration_custom)) },
                onClick = {
                    expanded = false
                    minutes = (selected / 60).toString()
                    seconds = (selected % 60).toString()
                    custom = true
                },
                modifier = Modifier.testTag("music_duration_custom"),
            )
        }
    }
    if (custom) {
        val minuteValue = minutes.toIntOrNull() ?: 0
        val secondValue = seconds.toIntOrNull() ?: 0
        val total = minuteValue * 60 + secondValue
        val valid = (minutes.isNotEmpty() || seconds.isNotEmpty()) && secondValue < 60 &&
            total in MusicRequest.MIN_SECONDS..MusicRequest.MAX_SECONDS
        AlertDialog(
            onDismissRequest = { custom = false },
            modifier = Modifier.testTag("music_duration_dialog"),
            title = { Text(stringResource(Res.string.music_duration_custom)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = minutes,
                            onValueChange = { minutes = it.filter(Char::isDigit).take(2) },
                            label = { Text(stringResource(Res.string.music_minutes_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            isError = !valid,
                            modifier = Modifier.weight(1f).testTag("music_custom_minutes"),
                        )
                        OutlinedTextField(
                            value = seconds,
                            onValueChange = { seconds = it.filter(Char::isDigit).take(2) },
                            label = { Text(stringResource(Res.string.music_seconds_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            isError = !valid,
                            modifier = Modifier.weight(1f).testTag("music_custom_seconds"),
                        )
                    }
                    Text(stringResource(Res.string.music_duration_limit), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = { onSelect(total); custom = false }, enabled = valid, modifier = Modifier.testTag("music_duration_apply")) {
                    Text(stringResource(Res.string.music_duration_apply))
                }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}
