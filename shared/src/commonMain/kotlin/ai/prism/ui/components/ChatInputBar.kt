package ai.prism.ui.components

import ai.prism.resources.Res
import ai.prism.resources.input_attach_file
import ai.prism.resources.input_attach_image
import ai.prism.resources.input_microphone
import ai.prism.resources.input_placeholder
import ai.prism.resources.input_send
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

/**
 * Поле ввода сообщения. Кнопки вложений, микрофона и отправки пока неактивны:
 * они заработают вместе с инференсом, файлами и голосом.
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrismPanel(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = {}, enabled = false) {
                Icon(Icons.Outlined.AttachFile, contentDescription = stringResource(Res.string.input_attach_file))
            }
            IconButton(onClick = {}, enabled = false) {
                Icon(Icons.Outlined.Image, contentDescription = stringResource(Res.string.input_attach_image))
            }
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
                    .testTag("message_input"),
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.input_placeholder),
                                style = textStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            IconButton(onClick = {}, enabled = false) {
                Icon(Icons.Outlined.Mic, contentDescription = stringResource(Res.string.input_microphone))
            }
            FilledIconButton(onClick = {}, enabled = false) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(Res.string.input_send))
            }
        }
    }
}
