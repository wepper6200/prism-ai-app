package ai.prism.ui.components

import ai.prism.domain.model.ImageAttachment
import ai.prism.resources.Res
import ai.prism.resources.image_description
import ai.prism.resources.input_attach_document
import ai.prism.resources.input_attach_image
import ai.prism.resources.input_attaching
import ai.prism.resources.input_draw
import ai.prism.resources.input_animate_placeholder
import ai.prism.resources.input_draw_placeholder
import ai.prism.resources.input_video
import ai.prism.resources.input_video_placeholder
import ai.prism.resources.input_redraw_placeholder
import ai.prism.resources.input_microphone
import ai.prism.resources.input_dictate_stop
import ai.prism.resources.input_recognizing
import ai.prism.resources.input_pick_gallery
import ai.prism.resources.input_placeholder
import ai.prism.resources.input_remove_image
import ai.prism.resources.input_send
import ai.prism.resources.input_stop
import ai.prism.resources.input_paste_image
import ai.prism.resources.input_take_photo
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

/** Изображения черновика и действия с ними. */
class InputAttachments(
    val items: List<ImageAttachment>,
    val isAttaching: Boolean,
    val canAttachMore: Boolean,
    val canTakePhoto: Boolean,
    val load: ImageBytesLoader,
    val onPickImages: () -> Unit,
    val onTakePhoto: () -> Unit,
    val onRemove: (String) -> Unit,
    /** Есть ли картинка в буфере обмена (проверяется при открытии меню). */
    val hasClipboardImage: () -> Boolean = { false },
    /** Вставить картинку из буфера обмена; `false` — картинки там нет (Ctrl+V вставит текст как обычно). */
    val onPaste: () -> Boolean = { false },
)

/** Микрофон: диктовка в поле ввода. [level] — громкость (0–1), пока идёт запись. */
class InputVoice(
    val listening: Boolean,
    val recognizing: Boolean,
    val level: Float,
    val enabled: Boolean,
    val onToggle: () -> Unit,
)

/** Кнопка режима «Нарисовать»: [active] — сообщение станет описанием картинки. */
class InputDrawing(
    val active: Boolean,
    val onToggle: () -> Unit,
)

/**
 * Поле ввода сообщения. Enter — отправить, Shift+Enter — новая строка.
 * Во время ответа кнопка отправки превращается в «Стоп».
 * Над полем — миниатюры приложенных изображений. Микрофон диктует текст в поле: первое нажатие — запись,
 * второе — закончить (запись заканчивается и сама после паузы).
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    canSend: Boolean,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    attachments: InputAttachments,
    modifier: Modifier = Modifier,
    /** `null` — на этой платформе рисовать нельзя, кнопки нет. */
    drawing: InputDrawing? = null,
    /** Приложить файл для поиска по нему; `null` — поиска по файлам нет, кнопка неактивна. */
    onPickDocuments: (() -> Unit)? = null,
    /** Кнопка режима «Видео»; `null` — видео на этой платформе нет. */
    video: InputDrawing? = null,
    /** Микрофон; `null` — голоса на этой платформе нет, кнопки нет. */
    voice: InputVoice? = null,
) {
    val drawActive = drawing?.active == true
    val videoActive = video?.active == true
    PrismPanel(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column {
            AnimatedVisibility(visible = attachments.items.isNotEmpty() || attachments.isAttaching) {
                AttachmentStrip(attachments)
            }
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(
                    onClick = { onPickDocuments?.invoke() },
                    enabled = onPickDocuments != null && !isGenerating && !drawActive && !videoActive,
                    modifier = Modifier.testTag("attach_document"),
                ) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = stringResource(Res.string.input_attach_document))
                }
                if (drawing != null) DrawButton(drawing, enabled = !isGenerating)
                if (video != null) VideoButton(video, enabled = !isGenerating)
                ImageButton(attachments, enabled = !isGenerating && attachments.canAttachMore)
                val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 8.dp, vertical = 13.dp)
                        .onPreviewKeyEvent { event ->
                            // Ctrl+V / Shift+Insert: картинка из буфера прикладывается к сообщению, текст вставляется как обычно.
                            val isPaste = (event.key == Key.V && (event.isCtrlPressed || event.isMetaPressed)) ||
                                (event.key == Key.Insert && event.isShiftPressed)
                            if (isPaste && event.type == KeyEventType.KeyDown && attachments.canAttachMore && !isGenerating) {
                                if (attachments.onPaste()) return@onPreviewKeyEvent true
                            }
                            val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (isEnter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                                if (canSend) onSend()
                                true
                            } else {
                                false
                            }
                        }
                        .testTag("message_input"),
                    decorationBox = { innerTextField ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    text = stringResource(
                                        when {
                                            drawActive && attachments.items.isNotEmpty() -> Res.string.input_redraw_placeholder
                                            drawActive -> Res.string.input_draw_placeholder
                                            videoActive && attachments.items.isNotEmpty() -> Res.string.input_animate_placeholder
                                            videoActive -> Res.string.input_video_placeholder
                                            else -> Res.string.input_placeholder
                                        },
                                    ),
                                    style = textStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                if (voice != null) MicButton(voice)
                AnimatedContent(targetState = isGenerating, label = "sendOrStop") { generating ->
                    if (generating) {
                        FilledIconButton(onClick = onStop, modifier = Modifier.testTag("stop_button")) {
                            Icon(Icons.Filled.Stop, contentDescription = stringResource(Res.string.input_stop))
                        }
                    } else {
                        FilledIconButton(onClick = onSend, enabled = canSend, modifier = Modifier.testTag("send_button")) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(Res.string.input_send))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MicButton(voice: InputVoice) {
    when {
        voice.recognizing -> {
            val description = stringResource(Res.string.input_recognizing)
            Box(
                Modifier
                    .size(48.dp)
                    .semantics { contentDescription = description }
                    .testTag("mic_recognizing"),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
            }
        }
        voice.listening -> {
            val level by animateFloatAsState(voice.level, label = "micLevel")
            FilledIconButton(
                onClick = voice.onToggle,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = 1f + level * MIC_PULSE
                        scaleY = 1f + level * MIC_PULSE
                    }
                    .testTag("mic_button"),
            ) {
                Icon(Icons.Filled.Mic, contentDescription = stringResource(Res.string.input_dictate_stop))
            }
        }
        else -> IconButton(onClick = voice.onToggle, enabled = voice.enabled, modifier = Modifier.testTag("mic_button")) {
            Icon(Icons.Outlined.Mic, contentDescription = stringResource(Res.string.input_microphone))
        }
    }
}

private const val MIC_PULSE = 0.25f

@Composable
private fun DrawButton(drawing: InputDrawing, enabled: Boolean) {
    val description = stringResource(Res.string.input_draw)
    if (drawing.active) {
        FilledTonalIconButton(onClick = drawing.onToggle, enabled = enabled, modifier = Modifier.testTag("draw_toggle")) {
            Icon(Icons.Outlined.Brush, contentDescription = description)
        }
    } else {
        IconButton(onClick = drawing.onToggle, enabled = enabled, modifier = Modifier.testTag("draw_toggle")) {
            Icon(Icons.Outlined.Brush, contentDescription = description)
        }
    }
}

@Composable
private fun VideoButton(video: InputDrawing, enabled: Boolean) {
    val description = stringResource(Res.string.input_video)
    if (video.active) {
        FilledTonalIconButton(onClick = video.onToggle, enabled = enabled, modifier = Modifier.testTag("video_toggle")) {
            Icon(Icons.Outlined.Movie, contentDescription = description)
        }
    } else {
        IconButton(onClick = video.onToggle, enabled = enabled, modifier = Modifier.testTag("video_toggle")) {
            Icon(Icons.Outlined.Movie, contentDescription = description)
        }
    }
}

/** Кнопка изображения: сразу открывает выбор фото, а где есть камера — меню «Выбрать фото / Сделать снимок». */
@Composable
private fun ImageButton(attachments: InputAttachments, enabled: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    var canPaste by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = {
                canPaste = attachments.hasClipboardImage()
                if (attachments.canTakePhoto || canPaste) menuOpen = true else attachments.onPickImages()
            },
            enabled = enabled,
            modifier = Modifier.testTag("attach_image"),
        ) {
            Icon(Icons.Outlined.Image, contentDescription = stringResource(Res.string.input_attach_image))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.input_pick_gallery)) },
                leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    attachments.onPickImages()
                },
            )
            if (attachments.canTakePhoto) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.input_take_photo)) },
                    leadingIcon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        attachments.onTakePhoto()
                    },
                )
            }
            if (canPaste) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.input_paste_image)) },
                    leadingIcon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        attachments.onPaste()
                    },
                    modifier = Modifier.testTag("paste_image"),
                )
            }
        }
    }
}

@Composable
private fun AttachmentStrip(attachments: InputAttachments) {
    val imageDescription = stringResource(Res.string.image_description)
    val attachingDescription = stringResource(Res.string.input_attaching)
    LazyRow(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("attachment_strip"),
    ) {
        items(attachments.items, key = { it.id }) { image ->
            Box(Modifier.size(72.dp)) {
                StoredImage(
                    fileName = image.fileName,
                    load = attachments.load,
                    contentDescription = imageDescription,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Surface(
                    onClick = { attachments.onRemove(image.id) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(24.dp)
                        .testTag("remove_attachment"),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.input_remove_image),
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
        }
        if (attachments.isAttaching) {
            item(key = "attaching") {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .semantics { contentDescription = attachingDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
        }
    }
}
