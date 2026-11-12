package ai.prism.store.ui

import ai.prism.store.AppAction
import ai.prism.store.AppItem
import ai.prism.store.AppProgress
import ai.prism.store.StoreController
import ai.prism.store.model.StoreError
import ai.prism.store.resources.Res
import ai.prism.store.resources.action_cancel
import ai.prism.store.resources.action_install
import ai.prism.store.resources.action_open
import ai.prism.store.resources.action_update
import ai.prism.store.resources.app_installed
import ai.prism.store.resources.app_installed_unknown
import ai.prism.store.resources.app_version
import ai.prism.store.resources.bytes_gb
import ai.prism.store.resources.bytes_mb
import ai.prism.store.resources.error_app_running
import ai.prism.store.resources.error_cancelled
import ai.prism.store.resources.error_catalog
import ai.prism.store.resources.error_checksum
import ai.prism.store.resources.error_install
import ai.prism.store.resources.error_network
import ai.prism.store.resources.error_permission
import ai.prism.store.resources.error_storage
import ai.prism.store.resources.progress_downloading
import ai.prism.store.resources.progress_installing
import ai.prism.store.resources.store_empty
import ai.prism.store.resources.store_loading
import ai.prism.store.resources.store_privacy
import ai.prism.store.resources.store_refresh
import ai.prism.store.resources.store_retry
import ai.prism.store.resources.store_subtitle
import ai.prism.store.resources.store_title
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4BDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    background = Color(0xFFFCF8FF),
    surface = Color(0xFFFCF8FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC6BFFF),
    onPrimary = Color(0xFF2A1F7A),
    primaryContainer = Color(0xFF3F33A8),
    onPrimaryContainer = Color(0xFFE4DFFF),
    background = Color(0xFF13111C),
    surface = Color(0xFF13111C),
)

/** Prism Store: список приложений с установкой, обновлением и запуском. */
@Composable
fun StoreScreen(controller: StoreController) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        val state by controller.state.collectAsState()
        val error = state.error
        LaunchedEffect(controller) { controller.refresh() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("store_list"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(Res.string.store_title),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    stringResource(Res.string.store_subtitle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = controller::refresh, modifier = Modifier.testTag("store_refresh")) {
                                Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.store_refresh))
                            }
                        }
                    }
                    when {
                        state.loading && state.apps.isEmpty() -> item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(stringResource(Res.string.store_loading))
                            }
                        }
                        error != null && state.apps.isEmpty() -> item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ErrorText(error)
                                Button(onClick = controller::refresh) { Text(stringResource(Res.string.store_retry)) }
                            }
                        }
                        state.apps.isEmpty() -> item { Text(stringResource(Res.string.store_empty)) }
                    }
                    items(state.apps, key = { it.app.id }) { item ->
                        AppCard(
                            item = item,
                            onInstall = { controller.install(item.app.id) },
                            onOpen = { controller.open(item.app.id) },
                            onCancel = { controller.cancel(item.app.id) },
                        )
                    }
                    item {
                        Text(
                            stringResource(Res.string.store_privacy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCard(item: AppItem, onInstall: () -> Unit, onOpen: () -> Unit, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("app_${item.app.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AppIcon(item.app.id)
                Column(Modifier.weight(1f)) {
                    Text(item.app.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(Res.string.app_version, item.app.version, formatSize(item.sizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    item.installedVersion?.let { installed ->
                        Text(
                            if (installed.isBlank()) stringResource(Res.string.app_installed_unknown)
                            else stringResource(Res.string.app_installed, installed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (item.app.description.isNotBlank()) {
                Text(item.app.description, style = MaterialTheme.typography.bodyMedium)
            }
            when (val progress = item.progress) {
                is AppProgress.Downloading -> {
                    LinearProgressIndicator(
                        progress = { if (progress.totalBytes > 0) progress.downloadedBytes.toFloat() / progress.totalBytes else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.progress_downloading, formatSize(progress.downloadedBytes), formatSize(progress.totalBytes)),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = onCancel) { Text(stringResource(Res.string.action_cancel)) }
                    }
                }
                AppProgress.Installing -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(Res.string.progress_installing), style = MaterialTheme.typography.bodySmall)
                }
                null -> {
                    item.error?.let { ErrorText(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.weight(1f))
                        when (item.action) {
                            AppAction.INSTALL -> Button(onClick = onInstall, modifier = Modifier.testTag("install_${item.app.id}")) {
                                Text(stringResource(Res.string.action_install))
                            }
                            AppAction.UPDATE -> {
                                OutlinedButton(onClick = onOpen) { Text(stringResource(Res.string.action_open)) }
                                Button(onClick = onInstall, modifier = Modifier.testTag("update_${item.app.id}")) {
                                    Text(stringResource(Res.string.action_update))
                                }
                            }
                            AppAction.OPEN -> Button(onClick = onOpen, modifier = Modifier.testTag("open_${item.app.id}")) {
                                Text(stringResource(Res.string.action_open))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(appId: String) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(
                Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF5B4BDB))),
                RoundedCornerShape(16.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (appId == "prism-store") Icons.Filled.Storefront else Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun ErrorText(error: StoreError) {
    Text(
        text = stringResource(
            when (error) {
                StoreError.NETWORK -> Res.string.error_network
                StoreError.CATALOG -> Res.string.error_catalog
                StoreError.CHECKSUM -> Res.string.error_checksum
                StoreError.STORAGE -> Res.string.error_storage
                StoreError.PERMISSION -> Res.string.error_permission
                StoreError.CANCELLED -> Res.string.error_cancelled
                StoreError.APP_RUNNING -> Res.string.error_app_running
                StoreError.INSTALL -> Res.string.error_install
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/** «118 МБ», «1,6 ГБ». */
@Composable
private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) {
        val tenths = (mb / 1024 * 10).roundToLong()
        stringResource(Res.string.bytes_gb, "${tenths / 10},${tenths % 10}")
    } else {
        stringResource(Res.string.bytes_mb, mb.roundToLong().toString())
    }
}

/** Заголовок окна магазина (строка из ресурсов). */
@Composable
fun storeTitle(): String = stringResource(Res.string.store_title)
