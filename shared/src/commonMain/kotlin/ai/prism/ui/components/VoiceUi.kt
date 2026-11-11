package ai.prism.ui.components

import ai.prism.domain.voice.VoiceError
import ai.prism.presentation.chat.ChatVoiceState
import ai.prism.resources.Res
import ai.prism.resources.action_cancel
import ai.prism.resources.chat_conversation_stop
import ai.prism.resources.chat_open_models
import ai.prism.resources.message_speak
import ai.prism.resources.message_speak_stop
import ai.prism.resources.voice_conversation_hint
import ai.prism.resources.voice_error_engine
import ai.prism.resources.voice_error_microphone
import ai.prism.resources.voice_error_no_recognizer
import ai.prism.resources.voice_error_no_voice
import ai.prism.resources.voice_error_nothing_heard
import ai.prism.resources.voice_error_permission
import ai.prism.resources.voice_error_playback
import ai.prism.resources.voice_phase_listening
import ai.prism.resources.voice_phase_recognizing
import ai.prism.resources.voice_phase_speaking
import ai.prism.resources.voice_phase_thinking
import ai.prism.resources.voice_recognizer_dialog_text
import ai.prism.resources.voice_recognizer_dialog_title
import ai.prism.resources.voice_voice_dialog_text
import ai.prism.resources.voice_voice_dialog_title
import ai.prism.service.voice.VoicePhase
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Какой модели речи не хватает. */
enum class MissingVoiceModel { RECOGNIZER, VOICE }

/** Объясняет, какую модель речи скачать, и ведёт на экран «Модели». */
@Composable
fun VoiceModelDialog(
    missing: MissingVoiceModel,
    voice: ChatVoiceState,
    onOpenModels: () -> Unit,
    onDismiss: () -> Unit,
) {
    val recognizer = missing == MissingVoiceModel.RECOGNIZER
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (recognizer) Icons.Outlined.Mic else Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null) },
        title = {
            Text(stringResource(if (recognizer) Res.string.voice_recognizer_dialog_title else Res.string.voice_voice_dialog_title))
        },
        text = {
            Text(
                if (recognizer) {
                    stringResource(Res.string.voice_recognizer_dialog_text, voice.recognizerName.orEmpty(), formatBytes(voice.recognizerBytes))
                } else {
                    stringResource(Res.string.voice_voice_dialog_text, voice.voiceName.orEmpty(), formatBytes(voice.voiceBytes))
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenModels, modifier = Modifier.testTag("voice_open_models")) {
                Text(stringResource(Res.string.chat_open_models))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
        modifier = Modifier.testTag("voice_dialog"),
    )
}

/** Панель разговора голосом: что сейчас происходит, громкость микрофона и кнопка «Закончить». */
@Composable
fun ConversationPanel(voice: ChatVoiceState, onStop: () -> Unit, modifier: Modifier = Modifier) {
    PrismPanel(modifier.fillMaxWidth().testTag("conversation_panel"), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (voice.phase == VoicePhase.SPEAKING) Icons.AutoMirrored.Outlined.VolumeUp else Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(voice.phase.label()),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).testTag("conversation_phase"),
                )
                FilledTonalButton(onClick = onStop, modifier = Modifier.testTag("conversation_stop")) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(Res.string.chat_conversation_stop), modifier = Modifier.padding(start = 6.dp))
                }
            }
            when (voice.phase) {
                VoicePhase.LISTENING -> {
                    val level by animateFloatAsState(voice.level, label = "micLevel")
                    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
                }
                VoicePhase.RECOGNIZING, VoicePhase.THINKING -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                VoicePhase.SPEAKING, VoicePhase.IDLE -> Unit
            }
            Text(
                text = stringResource(Res.string.voice_conversation_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Ошибка голоса над полем ввода. */
@Composable
fun VoiceErrorBanner(error: VoiceError, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.testTag("voice_error"),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(error.message()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_cancel)) }
        }
    }
}

/** Кнопка «Озвучить» под ответом; пока ответ звучит — «Остановить». */
@Composable
fun SpeakButton(speaking: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(36.dp).testTag("speak_button")) {
        Icon(
            if (speaking) Icons.Filled.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = stringResource(if (speaking) Res.string.message_speak_stop else Res.string.message_speak),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun VoicePhase.label(): StringResource = when (this) {
    VoicePhase.LISTENING, VoicePhase.IDLE -> Res.string.voice_phase_listening
    VoicePhase.RECOGNIZING -> Res.string.voice_phase_recognizing
    VoicePhase.THINKING -> Res.string.voice_phase_thinking
    VoicePhase.SPEAKING -> Res.string.voice_phase_speaking
}

fun VoiceError.message(): StringResource = when (this) {
    VoiceError.NO_RECOGNIZER -> Res.string.voice_error_no_recognizer
    VoiceError.NO_VOICE -> Res.string.voice_error_no_voice
    VoiceError.PERMISSION_DENIED -> Res.string.voice_error_permission
    VoiceError.MICROPHONE -> Res.string.voice_error_microphone
    VoiceError.NOTHING_HEARD -> Res.string.voice_error_nothing_heard
    VoiceError.ENGINE -> Res.string.voice_error_engine
    VoiceError.PLAYBACK -> Res.string.voice_error_playback
}
