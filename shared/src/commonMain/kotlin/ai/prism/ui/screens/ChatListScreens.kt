package ai.prism.ui.screens

import ai.prism.domain.model.ChatSummary
import ai.prism.presentation.chats.ChatListComponent
import ai.prism.presentation.chats.ChatListIntent
import ai.prism.resources.Res
import ai.prism.resources.action_new_chat
import ai.prism.resources.app_name
import ai.prism.resources.chat_untitled
import ai.prism.resources.chats_empty_text
import ai.prism.resources.chats_empty_title
import ai.prism.resources.logo_description
import ai.prism.resources.nav_chats
import ai.prism.resources.nav_models
import ai.prism.resources.nav_settings
import ai.prism.resources.prism_logo
import ai.prism.ui.components.EmptyState
import ai.prism.ui.components.PrismPanel
import ai.prism.ui.components.PrismTopBar
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Раздел боковой панели, выделенный в данный момент. */
enum class SidebarDestination { Models, Settings }

@Composable
fun ChatList(
    chats: List<ChatSummary>,
    selectedChatId: String?,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 4.dp),
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(chats, key = { it.id }) { chat ->
            NavigationDrawerItem(
                label = {
                    Text(
                        text = chat.title ?: stringResource(Res.string.chat_untitled),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                selected = chat.id == selectedChatId,
                onClick = { onOpenChat(chat.id) },
                icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) },
                modifier = Modifier
                    .animateItem()
                    .testTag("chat_item_${chat.id}"),
            )
        }
    }
}

/** Список чатов на телефоне — стартовый экран. */
@Composable
fun ChatListScreen(
    component: ChatListComponent,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrismTopBar(
                title = stringResource(Res.string.app_name),
                navigationIcon = {
                    Image(
                        painter = painterResource(Res.drawable.prism_logo),
                        contentDescription = stringResource(Res.string.logo_description),
                        modifier = Modifier.padding(start = 8.dp).size(32.dp),
                    )
                },
                actions = {
                    IconButton(
                        onClick = { component.onIntent(ChatListIntent.OpenModels) },
                        modifier = Modifier.testTag("nav_models"),
                    ) {
                        Icon(Icons.Outlined.Memory, contentDescription = stringResource(Res.string.nav_models))
                    }
                    IconButton(
                        onClick = { component.onIntent(ChatListIntent.OpenSettings) },
                        modifier = Modifier.testTag("nav_settings"),
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(Res.string.nav_settings))
                    }
                },
            )
            if (state.chats.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        title = stringResource(Res.string.chats_empty_title),
                        text = stringResource(Res.string.chats_empty_text),
                    )
                }
            } else {
                PrismPanel(Modifier.fillMaxWidth().weight(1f)) {
                    ChatList(
                        chats = state.chats,
                        selectedChatId = null,
                        onOpenChat = { component.onIntent(ChatListIntent.OpenChat(it)) },
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 88.dp),
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { component.onIntent(ChatListIntent.NewChat) },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(Res.string.action_new_chat)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .testTag("new_chat"),
        )
    }
}

/** Боковая панель на широком экране: чаты и разделы приложения. */
@Composable
fun Sidebar(
    component: ChatListComponent,
    selectedChatId: String?,
    activeDestination: SidebarDestination?,
    modifier: Modifier = Modifier,
) {
    val state by component.state.collectAsState()
    PrismPanel(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(Res.drawable.prism_logo),
                    contentDescription = stringResource(Res.string.logo_description),
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { component.onIntent(ChatListIntent.NewChat) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("new_chat"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.action_new_chat))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(Res.string.nav_chats),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (state.chats.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.chats_empty_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    ChatList(
                        chats = state.chats,
                        selectedChatId = selectedChatId,
                        onOpenChat = { component.onIntent(ChatListIntent.OpenChat(it)) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            NavigationDrawerItem(
                label = { Text(stringResource(Res.string.nav_models)) },
                selected = activeDestination == SidebarDestination.Models,
                onClick = { component.onIntent(ChatListIntent.OpenModels) },
                icon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
                modifier = Modifier.testTag("nav_models"),
            )
            NavigationDrawerItem(
                label = { Text(stringResource(Res.string.nav_settings)) },
                selected = activeDestination == SidebarDestination.Settings,
                onClick = { component.onIntent(ChatListIntent.OpenSettings) },
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                modifier = Modifier.testTag("nav_settings"),
            )
        }
    }
}
