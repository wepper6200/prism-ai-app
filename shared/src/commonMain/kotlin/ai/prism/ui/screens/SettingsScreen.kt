package ai.prism.ui.screens

import ai.prism.domain.model.DarkMode
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

            SettingsSection(title = stringResource(Res.string.settings_about), modifier = sectionModifier) {
                Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(Res.string.settings_version, state.versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

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
