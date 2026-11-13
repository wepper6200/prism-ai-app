package ai.prism.ui.screens

import ai.prism.domain.inference.EngineState
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelKind
import ai.prism.domain.model.ModelStatus
import ai.prism.presentation.models.ModelsComponent
import ai.prism.presentation.models.ModelsIntent
import ai.prism.presentation.models.ModelsState
import ai.prism.resources.Res
import ai.prism.resources.action_cancel
import ai.prism.resources.action_delete
import ai.prism.resources.models_active
import ai.prism.resources.models_delete_text
import ai.prism.resources.models_delete_title
import ai.prism.resources.models_details
import ai.prism.resources.models_details_image
import ai.prism.resources.models_details_embedding
import ai.prism.resources.models_details_video
import ai.prism.resources.models_draws
import ai.prism.resources.models_makes_video
import ai.prism.resources.models_device_ram
import ai.prism.resources.models_download
import ai.prism.resources.models_downloading
import ai.prism.resources.models_engine_loading
import ai.prism.resources.models_engine_ready
import ai.prism.resources.models_for_desktop
import ai.prism.resources.models_for_phone
import ai.prism.resources.models_searches_files
import ai.prism.resources.models_recognizes_speech
import ai.prism.resources.models_speaks
import ai.prism.resources.models_with_audio
import ai.prism.resources.models_details_speech
import ai.prism.resources.models_low_ram
import ai.prism.resources.models_pause
import ai.prism.resources.models_paused
import ai.prism.resources.models_internet
import ai.prism.resources.models_privacy_note
import ai.prism.resources.settings_internet_desc
import ai.prism.resources.models_resume
import ai.prism.resources.models_use
import ai.prism.resources.models_verifying
import ai.prism.resources.models_vision
import ai.prism.resources.models_vision_download
import ai.prism.resources.models_vision_missing
import ai.prism.resources.nav_models
import ai.prism.ui.components.SettingSwitch
import ai.prism.ui.LocalExpandedLayout
import ai.prism.ui.components.PrismPanel
import ai.prism.ui.components.PrismTopBar
import ai.prism.ui.components.formatBytes
import ai.prism.ui.components.formatContext
import ai.prism.ui.components.formatPercent
import ai.prism.ui.components.message
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

@Composable
fun ModelsScreen(
    component: ModelsComponent,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()
    val expanded = LocalExpandedLayout.current
    var pendingDelete by remember { mutableStateOf<ModelEntry?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(if (expanded) 0.dp else 12.dp)
            .testTag("models_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrismTopBar(
            title = stringResource(Res.string.nav_models),
            onBack = if (expanded) null else ({ component.onIntent(ModelsIntent.Back) }),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "device") {
                DeviceCard(state, Modifier.widthIn(max = 880.dp).fillMaxWidth())
            }
            item(key = "internet") {
                PrismPanel(Modifier.widthIn(max = 880.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    SettingSwitch(
                        title = stringResource(Res.string.models_internet),
                        description = stringResource(Res.string.settings_internet_desc),
                        checked = state.internetEnabled,
                        onCheckedChange = { component.onIntent(ModelsIntent.SetInternetEnabled(it)) },
                        tag = "models_internet",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            items(state.entries, key = { it.model.id }) { entry ->
                ModelCard(
                    entry = entry,
                    isActive = entry.model.id == state.activeModelId,
                    onIntent = component::onIntent,
                    onDeleteRequest = { pendingDelete = entry },
                    modifier = Modifier.widthIn(max = 880.dp).fillMaxWidth(),
                )
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(Res.string.models_delete_title)) },
            text = { Text(stringResource(Res.string.models_delete_text, entry.model.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        component.onIntent(ModelsIntent.Delete(entry.model.id))
                        pendingDelete = null
                    },
                    modifier = Modifier.testTag("confirm_delete"),
                ) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun DeviceCard(state: ModelsState, modifier: Modifier = Modifier) {
    PrismPanel(modifier, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(Res.string.models_device_ram, formatBytes(state.totalRamBytes)),
                style = MaterialTheme.typography.titleSmall,
            )
            when (val engine = state.engineState) {
                is EngineState.Ready -> Text(
                    text = stringResource(Res.string.models_engine_ready, engine.info.name, engine.info.accelerator),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                is EngineState.Loading -> Text(
                    text = stringResource(Res.string.models_engine_loading, formatPercent(engine.progress)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                is EngineState.Failed, EngineState.Idle -> Unit
            }
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.models_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    entry: ModelEntry,
    isActive: Boolean,
    onIntent: (ModelsIntent) -> Unit,
    onDeleteRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = entry.model
    PrismPanel(modifier.animateContentSize().testTag("model_${model.id}"), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (isActive && entry.chatReady) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(Res.string.models_active),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if ("android" in model.recommendedFor) Tag(stringResource(Res.string.models_for_phone))
                if ("desktop" in model.recommendedFor) Tag(stringResource(Res.string.models_for_desktop))
                if (model.supportsImages) Tag(stringResource(Res.string.models_vision))
                if (model.kind == ModelKind.IMAGE) Tag(stringResource(Res.string.models_draws))
                if (model.kind == ModelKind.EMBEDDING) Tag(stringResource(Res.string.models_searches_files))
                if (model.kind == ModelKind.VIDEO) Tag(stringResource(Res.string.models_makes_video))
                if (model.video?.audio == true) Tag(stringResource(Res.string.models_with_audio))
                if (model.kind == ModelKind.SPEECH_RECOGNITION) Tag(stringResource(Res.string.models_recognizes_speech))
                if (model.kind == ModelKind.SPEECH_SYNTHESIS) Tag(stringResource(Res.string.models_speaks))
            }
            val image = model.image
            Text(
                text = if (model.kind == ModelKind.IMAGE && image != null) {
                    stringResource(
                        Res.string.models_details_image,
                        model.quantization,
                        formatBytes(model.totalSizeBytes),
                        image.width,
                        image.height,
                    )
                } else if (model.kind == ModelKind.VIDEO && model.video != null) {
                    val video = checkNotNull(model.video)
                    stringResource(
                        Res.string.models_details_video,
                        model.quantization,
                        formatBytes(model.totalSizeBytes),
                        video.width,
                        video.height,
                        video.frames,
                    )
                } else if (model.kind == ModelKind.EMBEDDING) {
                    stringResource(Res.string.models_details_embedding, model.quantization, formatBytes(model.totalSizeBytes))
                } else if (model.kind == ModelKind.SPEECH_RECOGNITION || model.kind == ModelKind.SPEECH_SYNTHESIS) {
                    stringResource(Res.string.models_details_speech, model.quantization, formatBytes(model.totalSizeBytes))
                } else {
                    stringResource(
                        Res.string.models_details,
                        model.quantization,
                        formatBytes(model.totalSizeBytes),
                        formatContext(model.contextLength),
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            model.description["ru"]?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (!entry.fitsInMemory) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(Res.string.models_low_ram, formatBytes(model.minRamMb.toLong() * 1024 * 1024)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            ModelStatusSection(entry, isActive, onIntent, onDeleteRequest)
        }
    }
}

@Composable
private fun ModelStatusSection(
    entry: ModelEntry,
    isActive: Boolean,
    onIntent: (ModelsIntent) -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val id = entry.model.id
    when (val status = entry.status) {
        ModelStatus.NotDownloaded -> Button(
            onClick = { onIntent(ModelsIntent.Download(id)) },
            modifier = Modifier.testTag("download_$id"),
        ) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.models_download))
        }
        is ModelStatus.Downloading -> {
            val fraction = if (status.totalBytes > 0) status.downloadedBytes.toFloat() / status.totalBytes else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        Res.string.models_downloading,
                        formatBytes(status.downloadedBytes),
                        formatBytes(status.totalBytes),
                    ) + " · " + formatPercent(fraction),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { onIntent(ModelsIntent.Pause(id)) }, modifier = Modifier.testTag("pause_$id")) {
                    Icon(Icons.Outlined.Pause, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.models_pause))
                }
            }
        }
        ModelStatus.Verifying -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(Res.string.models_verifying), style = MaterialTheme.typography.bodySmall)
        }
        is ModelStatus.Paused -> if (entry.visionMissing) {
            VisionMissingSection(entry, status, isActive, onIntent, onDeleteRequest)
        } else {
            if (status.totalBytes > 0 && status.downloadedBytes > 0) {
                LinearProgressIndicator(
                    progress = { status.downloadedBytes.toFloat() / status.totalBytes },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        Res.string.models_paused,
                        formatBytes(status.downloadedBytes),
                        formatBytes(status.totalBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            status.error?.let { error ->
                Text(stringResource(error.message()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onIntent(ModelsIntent.Download(id)) }, modifier = Modifier.testTag("resume_$id")) {
                    Text(
                        stringResource(if (status.downloadedBytes > 0) Res.string.models_resume else Res.string.models_download),
                    )
                }
                if (status.downloadedBytes > 0) {
                    TextButton(onClick = onDeleteRequest) { Text(stringResource(Res.string.action_delete)) }
                }
            }
        }
        ModelStatus.Downloaded -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isActive && entry.model.kind == ModelKind.CHAT) {
                FilledTonalButton(onClick = { onIntent(ModelsIntent.Activate(id)) }, modifier = Modifier.testTag("use_$id")) {
                    Text(stringResource(Res.string.models_use))
                }
            }
            TextButton(onClick = onDeleteRequest, modifier = Modifier.testTag("delete_$id")) {
                Icon(Icons.Outlined.Delete, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.action_delete))
            }
        }
    }
}

/** Модель уже отвечает на текст, а модуль зрения не скачан (или его загрузка приостановлена). */
@Composable
private fun VisionMissingSection(
    entry: ModelEntry,
    status: ModelStatus.Paused,
    isActive: Boolean,
    onIntent: (ModelsIntent) -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val id = entry.model.id
    val vision = entry.model.vision ?: return
    Text(
        text = stringResource(Res.string.models_vision_missing, formatBytes(vision.sizeBytes)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    status.error?.let { error ->
        Text(stringResource(error.message()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    FlowRowButtons {
        Button(onClick = { onIntent(ModelsIntent.Download(id)) }, modifier = Modifier.testTag("download_vision_$id")) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.models_vision_download))
        }
        if (!isActive) {
            FilledTonalButton(onClick = { onIntent(ModelsIntent.Activate(id)) }, modifier = Modifier.testTag("use_$id")) {
                Text(stringResource(Res.string.models_use))
            }
        }
        TextButton(onClick = onDeleteRequest, modifier = Modifier.testTag("delete_$id")) {
            Text(stringResource(Res.string.action_delete))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}
