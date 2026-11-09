package ai.prism.ui.screens

import ai.prism.presentation.chat.ChatComponent
import ai.prism.presentation.chat.ChatIntent
import ai.prism.presentation.models.ModelsComponent
import ai.prism.presentation.models.ModelsIntent
import ai.prism.resources.Res
import ai.prism.resources.action_new_chat
import ai.prism.resources.chat_empty_text
import ai.prism.resources.chat_empty_title
import ai.prism.resources.chat_no_model
import ai.prism.resources.chat_open_models
import ai.prism.resources.chat_untitled
import ai.prism.resources.logo_description
import ai.prism.resources.models_empty_text
import ai.prism.resources.models_empty_title
import ai.prism.resources.nav_models
import ai.prism.resources.prism_logo
import ai.prism.resources.welcome_text
import ai.prism.resources.welcome_title
import ai.prism.ui.LocalExpandedLayout
import ai.prism.ui.components.ChatInputBar
import ai.prism.ui.components.EmptyState
import ai.prism.ui.components.PrismPanel
import ai.prism.ui.components.PrismTopBar
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Отступ экрана: на телефоне экран сам отступает от краёв, на широком — это делает общий макет. */
@Composable
private fun screenPadding(): Dp = if (LocalExpandedLayout.current) 0.dp else 12.dp

@Composable
fun ChatScreen(
    component: ChatComponent,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()
    val expanded = LocalExpandedLayout.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(screenPadding())
            .testTag("chat_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrismTopBar(
            title = state.title ?: stringResource(Res.string.chat_untitled),
            subtitle = stringResource(Res.string.chat_no_model),
            onBack = if (expanded) null else ({ component.onIntent(ChatIntent.Back) }),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                icon = Icons.Outlined.AutoAwesome,
                title = stringResource(Res.string.chat_empty_title),
                text = stringResource(Res.string.chat_empty_text),
                action = {
                    FilledTonalButton(onClick = { component.onIntent(ChatIntent.OpenModels) }) {
                        Text(stringResource(Res.string.chat_open_models))
                    }
                },
            )
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ChatInputBar(
                text = state.draft,
                onTextChange = { component.onIntent(ChatIntent.DraftChanged(it)) },
                modifier = Modifier.widthIn(max = 880.dp),
            )
        }
    }
}

@Composable
fun ModelsScreen(
    component: ModelsComponent,
    modifier: Modifier = Modifier,
) {
    val expanded = LocalExpandedLayout.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(screenPadding())
            .testTag("models_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrismTopBar(
            title = stringResource(Res.string.nav_models),
            onBack = if (expanded) null else ({ component.onIntent(ModelsIntent.Back) }),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                icon = Icons.Outlined.Memory,
                title = stringResource(Res.string.models_empty_title),
                text = stringResource(Res.string.models_empty_text),
            )
        }
    }
}

/** Приветствие в области контента на широком экране, пока чат не выбран. */
@Composable
fun WelcomePane(
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().testTag("welcome"), contentAlignment = Alignment.Center) {
        PrismPanel(
            Modifier
                .widthIn(max = 520.dp)
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Image(
                    painter = painterResource(Res.drawable.prism_logo),
                    contentDescription = stringResource(Res.string.logo_description),
                    modifier = Modifier.size(96.dp),
                )
                Text(
                    text = stringResource(Res.string.welcome_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(Res.string.welcome_text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onNewChat) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.action_new_chat))
                }
            }
        }
    }
}
