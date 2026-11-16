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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.time.TimeSource
import kotlin.math.abs
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
    var position by remember(fileName) { mutableFloatStateOf(0f) }
    var seekRevision by remember(fileName) { mutableIntStateOf(0) }
    var seeking by remember(fileName) { mutableStateOf(false) }
    var resumeAfterSeek by remember(fileName) { mutableStateOf(false) }
    val duration = audio?.let { it.sampleCount.toFloat() / it.sampleRate } ?: 0f
    var saved by remember(fileName) { mutableStateOf<Boolean?>(null) }
    val exporter = rememberImageExporter("audio/wav") { saved = it }
    LaunchedEffect(playing, audio, seekRevision) {
        val sound = audio
        if (playing && sound != null) {
            val start = position.takeIf { it < duration } ?: 0f
            position = start
            coroutineScope {
                val started = TimeSource.Monotonic.markNow()
                val playback = launch { playAudio(sound.from(start)) }
                while (playback.isActive) {
                    position = (start + started.elapsedNow().inWholeMilliseconds / 1000f).coerceAtMost(duration)
                    delay(80)
                }
                playback.join()
            }
            position = duration
            playing = false
        }
    }
    Surface(modifier.fillMaxWidth().testTag("generated_music"), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.MusicNote, contentDescription = null)
                Text(stringResource(Res.string.music_track), modifier = Modifier.weight(1f))
                IconButton(onClick = { playing = !playing }, enabled = audio != null, modifier = Modifier.testTag("music_play_pause")) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (playing) Res.string.music_pause else Res.string.music_play))
                }
                IconButton(onClick = { bytes?.let { exporter.export(it, "prism-ai-$fileName") } }, enabled = audio != null) {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(Res.string.music_save))
                }
            }
            val peaks = remember(audio) {
                val sound = audio
                if (sound == null) emptyList() else List(64) { bar ->
                    var peak = 0f
                    repeat(64) { sample ->
                        val index = ((bar + sample / 64f) / 64f * sound.sampleCount).toInt().coerceIn(0, (sound.sampleCount - 1).coerceAtLeast(0)) * sound.blockAlign
                        if (index + 1 < sound.pcm16.size) {
                            val value = ((sound.pcm16[index + 1].toInt() shl 8) or (sound.pcm16[index].toInt() and 255)).toShort().toInt()
                            peak = maxOf(peak, abs(value / 32768f))
                        }
                    }
                    peak
                }
            }
            val activeColor = MaterialTheme.colorScheme.primary
            val inactiveColor = MaterialTheme.colorScheme.outlineVariant
            Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                peaks.forEachIndexed { index, peak ->
                    val step = size.width / peaks.size
                    val height = maxOf(4f, peak * size.height)
                    drawRect(if (index.toFloat() / peaks.size <= position / duration.coerceAtLeast(0.1f)) activeColor else inactiveColor,
                        Offset(index * step, (size.height - height) / 2), Size(step * 0.55f, height))
                }
            }
            val seekDescription = stringResource(Res.string.music_seek)
            Slider(value = position.coerceIn(0f, duration.coerceAtLeast(0.1f)), valueRange = 0f..duration.coerceAtLeast(0.1f), enabled = audio != null,
                onValueChange = {
                    if (!seeking) { resumeAfterSeek = playing; playing = false; seeking = true }
                    position = it
                },
                onValueChangeFinished = { seeking = false; seekRevision++; playing = resumeAfterSeek },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = seekDescription }.testTag("music_seek"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(audioTime(position), style = MaterialTheme.typography.labelMedium)
                Text(audioTime(duration), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("music_duration"))
            }
            saved?.let { Text(stringResource(if (it) Res.string.image_saved else Res.string.image_save_failed), style = MaterialTheme.typography.labelMedium) }
        }
    }
}

internal fun audioTime(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return (total / 60).toString() + ":" + (total % 60).toString().padStart(2, '0')
}
