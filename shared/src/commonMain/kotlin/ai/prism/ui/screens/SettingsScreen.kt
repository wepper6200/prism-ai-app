package ai.prism.ui.screens

import ai.prism.domain.model.DarkMode
import ai.prism.domain.update.AppRelease
import ai.prism.domain.model.ModelFolderError
import ai.prism.domain.model.ModelFolderState
import ai.prism.resources.settings_model_storage
import ai.prism.resources.model_folder_shared
import ai.prism.resources.model_folder_desc
import ai.prism.resources.model_folder_moving
import ai.prism.resources.model_folder_error_access
import ai.prism.resources.model_folder_error_downloads
import ai.prism.resources.model_folder_error_space
import ai.prism.resources.model_folder_error_failed
import ai.prism.domain.update.UpdateError
import ai.prism.service.update.UpdateState
import ai.prism.ui.components.formatBytes
import ai.prism.resources.settings_updates
import ai.prism.resources.update_available
import ai.prism.resources.update_cancel
import ai.prism.resources.update_check
import ai.prism.resources.update_checking
import ai.prism.resources.update_downloading
import ai.prism.resources.update_error_cancelled
import ai.prism.resources.update_error_checksum
import ai.prism.resources.update_error_install
import ai.prism.resources.update_error_network
import ai.prism.resources.update_error_no_package
import ai.prism.resources.update_error_permission
import ai.prism.resources.update_error_storage
import ai.prism.resources.update_install
import ai.prism.resources.update_installing
import ai.prism.resources.update_note
import ai.prism.resources.update_retry
import ai.prism.resources.update_up_to_date
import ai.prism.resources.update_whats_new
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import ai.prism.domain.model.AppSettings
import ai.prism.presentation.settings.VoiceModelOption
import ai.prism.resources.settings_voice
import ai.prism.resources.settings_video
import ai.prism.resources.settings_video_desc
import ai.prism.resources.settings_video_model
import ai.prism.resources.settings_voice_desc
import ai.prism.resources.settings_voice_none
import ai.prism.resources.settings_voice_rate
import ai.prism.resources.settings_voice_recognizer
import ai.prism.resources.settings_voice_voice
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import kotlin.math.roundToInt
import ai.prism.domain.model.ThemeStyle
import ai.prism.presentation.settings.SettingsComponent
import ai.prism.presentation.settings.SettingsIntent
import ai.prism.resources.Res
import ai.prism.resources.app_name
import ai.prism.resources.dark_mode_dark
import ai.prism.resources.dark_mode_light
import ai.prism.resources.dark_mode_system
import ai.prism.resources.nav_settings
import ai.prism.resources.settings_about
import ai.prism.resources.settings_appearance
import ai.prism.resources.settings_dark_mode
import ai.prism.resources.settings_dynamic_color
import ai.prism.resources.settings_dynamic_color_desc
import ai.prism.resources.settings_assistant
import ai.prism.resources.settings_auto_apply
import ai.prism.resources.settings_auto_apply_desc
import ai.prism.resources.settings_internet
import ai.prism.resources.settings_internet_desc
import ai.prism.resources.settings_safe_mode
import ai.prism.resources.settings_safe_mode_desc
import ai.prism.resources.settings_identity_note
import ai.prism.resources.settings_model_license
import ai.prism.resources.settings_models
import ai.prism.resources.settings_privacy
import ai.prism.resources.settings_privacy_text
import ai.prism.resources.settings_theme_style
import ai.prism.resources.settings_version
import ai.prism.resources.theme_expressive
import ai.prism.resources.theme_expressive_desc
import ai.prism.resources.theme_glass
import ai.prism.resources.theme_glass_desc
import ai.prism.ui.components.SettingSwitch
import ai.prism.ui.LocalExpandedLayout
import ai.prism.ui.components.PrismPanel
import ai.prism.ui.components.PrismTopBar
import ai.prism.ui.theme.LocalPrismStyle
import ai.prism.ui.theme.drawPrismBackdrop
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScreen(
    component: SettingsComponent,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()
    val expanded = LocalExpandedLayout.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(if (expanded) 0.dp else 12.dp)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrismTopBar(
            title = stringResource(Res.string.nav_settings),
            onBack = if (expanded) null else ({ component.onIntent(SettingsIntent.Back) }),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val sectionModifier = Modifier.fillMaxWidth().widthIn(max = 880.dp)

            SettingsSection(title = stringResource(Res.string.settings_appearance), modifier = sectionModifier) {
                SectionLabel(stringResource(Res.string.settings_theme_style))
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ThemeStyleOption(
                        style = ThemeStyle.EXPRESSIVE,
                        selected = state.settings.themeStyle == ThemeStyle.EXPRESSIVE,
                        title = Res.string.theme_expressive,
                        description = Res.string.theme_expressive_desc,
                        onSelect = { component.onIntent(SettingsIntent.SelectThemeStyle(ThemeStyle.EXPRESSIVE)) },
                        modifier = Modifier.weight(1f),
                    )
                    ThemeStyleOption(
                        style = ThemeStyle.LIQUID_GLASS,
                        selected = state.settings.themeStyle == ThemeStyle.LIQUID_GLASS,
                        title = Res.string.theme_glass,
                        description = Res.string.theme_glass_desc,
                        onSelect = { component.onIntent(SettingsIntent.SelectThemeStyle(ThemeStyle.LIQUID_GLASS)) },
                        modifier = Modifier.weight(1f),
                    )
                }

                SectionLabel(stringResource(Res.string.settings_dark_mode))
                DarkModeSelector(
                    selected = state.settings.darkMode,
                    onSelect = { component.onIntent(SettingsIntent.SelectDarkMode(it)) },
                )

                if (state.supportsDynamicColor) {
                    val dynamicAvailable = state.settings.themeStyle == ThemeStyle.EXPRESSIVE
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .toggleable(
                                value = state.settings.dynamicColor,
                                enabled = dynamicAvailable,
                                role = Role.Switch,
                                onValueChange = { component.onIntent(SettingsIntent.SetDynamicColor(it)) },
                            )
                            .alpha(if (dynamicAvailable) 1f else 0.5f)
                            .padding(vertical = 8.dp)
                            .testTag("dynamic_color"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.settings_dynamic_color), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = stringResource(Res.string.settings_dynamic_color_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = state.settings.dynamicColor,
                            onCheckedChange = null,
                            enabled = dynamicAvailable,
                        )
                    }
                }
            }

            SettingsSection(title = stringResource(Res.string.settings_privacy), modifier = sectionModifier) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(Res.string.settings_privacy_text),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .toggleable(
                            value = state.settings.safeMode,
                            role = Role.Switch,
                            onValueChange = { component.onIntent(SettingsIntent.SetSafeMode(it)) },
                        )
                        .padding(vertical = 8.dp)
                        .testTag("safe_mode"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.settings_safe_mode), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = stringResource(Res.string.settings_safe_mode_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Switch(checked = state.settings.safeMode, onCheckedChange = null)
                }
            }

            SettingsSection(title = stringResource(Res.string.settings_assistant), modifier = sectionModifier) {
                SettingSwitch(
                    title = stringResource(Res.string.settings_internet),
                    description = stringResource(Res.string.settings_internet_desc),
                    checked = state.settings.internetEnabled,
                    onCheckedChange = { component.onIntent(SettingsIntent.SetInternetEnabled(it)) },
                    tag = "internet_setting",
                )
                SettingSwitch(
                    title = stringResource(Res.string.settings_auto_apply),
                    description = stringResource(Res.string.settings_auto_apply_desc),
                    checked = state.settings.autoApplyEdits,
                    onCheckedChange = { component.onIntent(SettingsIntent.SetAutoApplyEdits(it)) },
                    tag = "auto_apply_setting",
                )
            }

            if (state.voice.available) {
                SettingsSection(title = stringResource(Res.string.settings_voice), modifier = sectionModifier.testTag("voice_settings")) {
                    Text(
                        text = stringResource(Res.string.settings_voice_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SectionLabel(stringResource(Res.string.settings_voice_recognizer))
                    VoiceModelChoice(
                        options = state.voice.recognizers,
                        selectedId = state.voice.selectedRecognizerId,
                        onSelect = { component.onIntent(SettingsIntent.SelectSpeechModel(it)) },
                    )
                    SectionLabel(stringResource(Res.string.settings_voice_voice))
                    VoiceModelChoice(
                        options = state.voice.voices,
                        selectedId = state.voice.selectedVoiceId,
                        onSelect = { component.onIntent(SettingsIntent.SelectVoiceModel(it)) },
                    )
                    if (state.voice.speakers.size > 1) {
                        VoiceModelChoice(
                            options = state.voice.speakers.mapIndexed { index, name -> VoiceModelOption(index.toString(), name) },
                            selectedId = state.voice.selectedSpeaker.toString(),
                            onSelect = { component.onIntent(SettingsIntent.SelectVoiceSpeaker(it.toInt())) },
                        )
                    }
                    SectionLabel(stringResource(Res.string.settings_voice_rate, formatRate(state.settings.speechRate)))
                    Slider(
                        value = state.settings.speechRate,
                        onValueChange = { component.onIntent(SettingsIntent.SetSpeechRate(it)) },
                        valueRange = AppSettings.MIN_SPEECH_RATE..AppSettings.MAX_SPEECH_RATE,
                        steps = SPEECH_RATE_STEPS,
                        modifier = Modifier.testTag("speech_rate"),
                    )
                }
            }

            if (state.videoModels.size > 1) {
                SettingsSection(title = stringResource(Res.string.settings_video), modifier = sectionModifier.testTag("video_settings")) {
                    Text(
                        text = stringResource(Res.string.settings_video_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SectionLabel(stringResource(Res.string.settings_video_model))
                    VoiceModelChoice(
                        options = state.videoModels,
                        selectedId = state.selectedVideoModelId,
                        onSelect = { component.onIntent(SettingsIntent.SelectVideoModel(it)) },
                    )
                }
            }

            SettingsSection(title = stringResource(Res.string.settings_models), modifier = sectionModifier) {
                val uriHandler = LocalUriHandler.current
                Text(
                    text = stringResource(Res.string.settings_identity_note),
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.modelLicenses.forEach { license ->
                    Text(
                        text = stringResource(Res.string.settings_model_license, license.family, license.licenseName),
                        style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { uriHandler.openUri(license.licenseUrl) },
                    )
                }
            }

            if (state.modelFolder.supported) {
                SettingsSection(title = stringResource(Res.string.settings_model_storage), modifier = sectionModifier) {
                    ModelFolderBlock(state.modelFolder, component::onIntent)
                }
            }

            SettingsSection(title = stringResource(Res.string.settings_about), modifier = sectionModifier) {
                Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(Res.string.settings_version, state.versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.updatesSupported) {
                SettingsSection(title = stringResource(Res.string.settings_updates), modifier = sectionModifier) {
                    UpdateBlock(state.update, component::onIntent)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Хранить модели в общей папке, которая остаётся после удаления приложения (Android). */
@Composable
private fun ModelFolderBlock(folder: ModelFolderState, onIntent: (SettingsIntent) -> Unit) {
    SettingSwitch(
        title = stringResource(Res.string.model_folder_shared, folder.sharedPath),
        description = stringResource(Res.string.model_folder_desc),
        checked = folder.shared,
        onCheckedChange = { if (!folder.moving) onIntent(SettingsIntent.SetSharedModelFolder(it)) },
        tag = "model_folder_setting",
    )
    if (folder.moving) {
        LinearProgressIndicator(
            progress = { if (folder.totalBytes > 0) folder.movedBytes.toFloat() / folder.totalBytes else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(Res.string.model_folder_moving, formatBytes(folder.movedBytes), formatBytes(folder.totalBytes)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    folder.error?.let { error ->
        Text(
            text = stringResource(
                when (error) {
                    ModelFolderError.NEED_ACCESS -> Res.string.model_folder_error_access
                    ModelFolderError.DOWNLOADS_ACTIVE -> Res.string.model_folder_error_downloads
                    ModelFolderError.NOT_ENOUGH_SPACE -> Res.string.model_folder_error_space
                    ModelFolderError.FAILED -> Res.string.model_folder_error_failed
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Проверка, загрузка и установка новой версии приложения (только по кнопке). */
@Composable
private fun UpdateBlock(update: UpdateState, onIntent: (SettingsIntent) -> Unit) {
    Text(
        text = stringResource(Res.string.update_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (update) {
        UpdateState.Idle -> Button(onClick = { onIntent(SettingsIntent.CheckForUpdates) }, modifier = Modifier.testTag("update_check")) {
            Text(stringResource(Res.string.update_check))
        }
        UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(Res.string.update_checking), style = MaterialTheme.typography.bodyMedium)
        }
        is UpdateState.UpToDate -> {
            Text(stringResource(Res.string.update_up_to_date, update.version), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { onIntent(SettingsIntent.CheckForUpdates) }) { Text(stringResource(Res.string.update_check)) }
        }
        is UpdateState.Available -> {
            ReleaseInfo(update.release)
            Button(onClick = { onIntent(SettingsIntent.InstallUpdate) }, modifier = Modifier.testTag("update_install")) {
                Text(stringResource(Res.string.update_install, formatBytes(update.release.asset?.sizeBytes ?: 0)))
            }
        }
        is UpdateState.Downloading -> {
            Text(stringResource(Res.string.update_available, update.release.version), style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(
                progress = { if (update.totalBytes > 0) update.downloadedBytes.toFloat() / update.totalBytes else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(Res.string.update_downloading, formatBytes(update.downloadedBytes), formatBytes(update.totalBytes)),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { onIntent(SettingsIntent.CancelUpdate) }) { Text(stringResource(Res.string.update_cancel)) }
        }
        is UpdateState.Installing -> {
            Text(stringResource(Res.string.update_installing), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { onIntent(SettingsIntent.InstallUpdate) }) { Text(stringResource(Res.string.update_retry)) }
        }
        is UpdateState.Failed -> {
            update.release?.let { ReleaseInfo(it) }
            Text(
                text = stringResource(
                    when (update.error) {
                        UpdateError.NETWORK -> Res.string.update_error_network
                        UpdateError.NO_PACKAGE -> Res.string.update_error_no_package
                        UpdateError.CHECKSUM -> Res.string.update_error_checksum
                        UpdateError.STORAGE -> Res.string.update_error_storage
                        UpdateError.PERMISSION -> Res.string.update_error_permission
                        UpdateError.CANCELLED -> Res.string.update_error_cancelled
                        UpdateError.INSTALL -> Res.string.update_error_install
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            val retry = if (update.release != null) SettingsIntent.InstallUpdate else SettingsIntent.CheckForUpdates
            Button(onClick = { onIntent(retry) }) { Text(stringResource(Res.string.update_retry)) }
        }
    }
}

@Composable
private fun ReleaseInfo(release: AppRelease) {
    Text(stringResource(Res.string.update_available, release.version), style = MaterialTheme.typography.titleSmall)
    if (release.notes.isNotBlank()) {
        Text(stringResource(Res.string.update_whats_new), style = MaterialTheme.typography.labelLarge)
        Text(
            text = release.notes.take(RELEASE_NOTES_LIMIT).trimEnd() + if (release.notes.length > RELEASE_NOTES_LIMIT) "…" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val RELEASE_NOTES_LIMIT = 1500

/** Выбор одной из скачанных моделей речи; пока ничего не скачано — подсказка про экран «Модели». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceModelChoice(
    options: List<VoiceModelOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    if (options.isEmpty()) {
        Text(
            text = stringResource(Res.string.settings_voice_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option.id == selectedId,
                onClick = { onSelect(option.id) },
                label = { Text(option.name) },
                modifier = Modifier.testTag("voice_option_${option.id}"),
            )
        }
    }
}

/** Скорость речи с шагом 0,1: «1,0×». */
private fun formatRate(rate: Float): String {
    val tenths = (rate * 10).roundToInt()
    return "${tenths / 10},${tenths % 10}×"
}

/** Деления ползунка скорости: от 0,7 до 1,5 с шагом 0,1. */
private const val SPEECH_RATE_STEPS = 7

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    PrismPanel(modifier, shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ThemeStyleOption(
    style: ThemeStyle,
    selected: Boolean,
    title: StringResource,
    description: StringResource,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.large
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "themeOptionBorder",
    )
    val borderWidth by animateDpAsState(targetValue = if (selected) 2.dp else 1.dp, label = "themeOptionBorderWidth")
    Column(
        modifier = modifier
            .clip(shape)
            .border(borderWidth, borderColor, shape)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .testTag("theme_style_${style.name}")
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemePreview(
            style = style,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = stringResource(description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Миниатюра стиля, чтобы выбор был нагляден ещё до переключения. */
@Composable
private fun ThemePreview(style: ThemeStyle, modifier: Modifier = Modifier) {
    val darkTheme = LocalPrismStyle.current.isDark
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.clip(shape)) {
        when (style) {
            ThemeStyle.EXPRESSIVE -> {
                val container = if (darkTheme) Color(0xFF5420C2) else Color(0xFFEDE4FF)
                val accent = if (darkTheme) Color(0xFFD0BCFF) else Color(0xFF7C3AED)
                Box(Modifier.fillMaxSize().background(container))
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .size(width = 64.dp, height = 10.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.6f)),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .size(28.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent),
                )
            }
            ThemeStyle.LIQUID_GLASS -> {
                Canvas(Modifier.fillMaxSize()) { drawPrismBackdrop(size, darkTheme) }
                val glass = if (darkTheme) Color(0xFF16131F).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.55f)
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.7f)
                        .height(32.dp)
                        .clip(CircleShape)
                        .background(glass)
                        .border(1.dp, Color.White.copy(alpha = if (darkTheme) 0.3f else 0.9f), CircleShape),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DarkModeSelector(
    selected: DarkMode,
    onSelect: (DarkMode) -> Unit,
) {
    val modes = DarkMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                modifier = Modifier.testTag("dark_mode_${mode.name}"),
            ) {
                Text(
                    stringResource(
                        when (mode) {
                            DarkMode.SYSTEM -> Res.string.dark_mode_system
                            DarkMode.LIGHT -> Res.string.dark_mode_light
                            DarkMode.DARK -> Res.string.dark_mode_dark
                        },
                    ),
                )
            }
        }
    }
}
