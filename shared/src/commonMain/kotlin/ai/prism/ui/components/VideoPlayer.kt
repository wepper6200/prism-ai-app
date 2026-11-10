package ai.prism.ui.components

import ai.prism.core.MjpegAvi
import ai.prism.resources.Res
import ai.prism.resources.video_description
import ai.prism.resources.video_play
import ai.prism.resources.video_save
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource

private class DecodedVideo(val frames: List<ImageBitmap>, val fps: Int)

/**
 * Видео из чата (MJPEG AVI): кадры раскодируются один раз и проигрываются по кругу.
 * Нажатие — пауза/продолжение, кнопка в углу — сохранить файл.
 */
@Composable
fun VideoPlayer(fileName: String, load: ImageBytesLoader, onSave: () -> Unit, modifier: Modifier = Modifier) {
    val video by produceState<DecodedVideo?>(null, fileName) { value = decodeVideo(fileName, load) }
    var index by remember(fileName) { mutableIntStateOf(0) }
    var playing by remember(fileName) { mutableStateOf(true) }
    val current = video
    LaunchedEffect(current, playing) {
        if (current == null || !playing || current.frames.size < 2) return@LaunchedEffect
        val frameMs = 1000L / current.fps.coerceAtLeast(1)
        while (true) {
            delay(frameMs)
            index = (index + 1) % current.frames.size
        }
    }
    val description = stringResource(Res.string.video_description)
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { playing = !playing }
            .semantics { contentDescription = description }
            .testTag("generated_video"),
        contentAlignment = Alignment.Center,
    ) {
        if (current == null) {
            CircularProgressIndicator(Modifier.size(32.dp))
        } else {
            Image(
                bitmap = current.frames[index.coerceIn(0, current.frames.lastIndex)],
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            if (!playing) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(Res.string.video_play),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), CircleShape)
                        .padding(8.dp),
                )
            }
            FilledTonalIconButton(
                onClick = onSave,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp).testTag("save_video"),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = stringResource(Res.string.video_save), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
private suspend fun decodeVideo(fileName: String, load: ImageBytesLoader): DecodedVideo? = withContext(Dispatchers.Default) {
    val parsed = load(fileName)?.let(MjpegAvi::parse) ?: return@withContext null
    val frames = parsed.frames.mapNotNull { jpeg ->
        try {
            jpeg.decodeToImageBitmap()
        } catch (e: IllegalArgumentException) {
            null
        }
    }
    if (frames.isEmpty()) null else DecodedVideo(frames, parsed.fps)
}
