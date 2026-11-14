package ai.prism.ui.components

import ai.prism.core.MjpegAvi
import ai.prism.core.PcmAudio
import ai.prism.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
fun MusicPlayer(fileName: String, load: ImageBytesLoader, playAudio: VideoAudioPlayer, modifier: Modifier = Modifier) {
    val bytes by produceState<ByteArray?>(null, fileName) { value = load(fileName) }
    val audio by produceState<PcmAudio?>(null, bytes) {
        value = withContext(Dispatchers.Default) { bytes?.let(MjpegAvi::parseWav) }
    }
    var playing by remember(fileName) { mutableStateOf(false) }
    var saved by remember(fileName) { mutableStateOf<Boolean?>(null) }
    val exporter = rememberImageExporter("audio/wav") { saved = it }
    LaunchedEffect(playing, audio) {
        val sound = audio
        if (playing && sound != null) {
            try { playAudio(sound) } finally { playing = false }
        }
    }
    Surface(modifier.fillMaxWidth().testTag("generated_music"), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.MusicNote, contentDescription = null)
                Text(stringResource(Res.string.music_track), modifier = Modifier.weight(1f))
                IconButton(onClick = { playing = !playing }, enabled = audio != null) {
                    Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (playing) Res.string.input_stop else Res.string.music_play))
                }
                IconButton(onClick = { bytes?.let { exporter.export(it, "prism-ai-$fileName") } }, enabled = audio != null) {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(Res.string.music_save))
                }
            }
            saved?.let { Text(stringResource(if (it) Res.string.image_saved else Res.string.image_save_failed), style = MaterialTheme.typography.labelMedium) }
        }
    }
}
