package ai.prism.ui

import ai.prism.presentation.chats.ChatListIntent
import ai.prism.presentation.root.RootComponent
import ai.prism.ui.components.PrismBackground
import ai.prism.ui.screens.ChatListScreen
import ai.prism.ui.screens.ChatScreen
import ai.prism.ui.screens.ModelsScreen
import ai.prism.ui.screens.SettingsScreen
import ai.prism.ui.screens.Sidebar
import ai.prism.ui.screens.SidebarDestination
import ai.prism.ui.screens.WelcomePane
import ai.prism.ui.theme.PrismTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.slide
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState

/** `true` — широкий макет: боковая панель со списком чатов и область контента рядом. */
val LocalExpandedLayout = compositionLocalOf { false }

/** Ширина окна, начиная с которой используется двухпанельный макет (класс Expanded в Material 3). */
private val ExpandedBreakpoint = 840.dp
private val SidebarWidth = 300.dp

@Composable
fun PrismApp(
    root: RootComponent,
    modifier: Modifier = Modifier,
) {
    val settings by root.settings.collectAsState()
    PrismTheme(settings) {
        PrismBackground(modifier) {
            RootContent(
                component = root,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
}

@Composable
private fun RootContent(
    component: RootComponent,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val expanded = maxWidth >= ExpandedBreakpoint
        CompositionLocalProvider(LocalExpandedLayout provides expanded) {
            if (expanded) {
                ExpandedLayout(component)
            } else {
                CompactLayout(component)
            }
        }
    }
}

/** Телефон: каждый раздел — отдельный экран со сдвигом при переходе. */
@Composable
private fun CompactLayout(component: RootComponent) {
    Children(
        stack = component.stack,
        modifier = Modifier.fillMaxSize(),
        animation = stackAnimation(slide()),
    ) { child ->
        when (val instance = child.instance) {
            RootComponent.Child.Home -> ChatListScreen(component.chatList)
            is RootComponent.Child.Chat -> ChatScreen(instance.component)
            is RootComponent.Child.Models -> ModelsScreen(instance.component)
            is RootComponent.Child.Settings -> SettingsScreen(instance.component)
        }
    }
}

/** Windows и планшеты: боковая панель со списком чатов + область контента. */
@Composable
private fun ExpandedLayout(component: RootComponent) {
    val stack by component.stack.subscribeAsState()
    val active = stack.active.instance
    val selectedChatId = (active as? RootComponent.Child.Chat)?.component?.state?.value?.chatId
    val destination = when (active) {
        is RootComponent.Child.Models -> SidebarDestination.Models
        is RootComponent.Child.Settings -> SidebarDestination.Settings
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Sidebar(
            component = component.chatList,
            selectedChatId = selectedChatId,
            activeDestination = destination,
            modifier = Modifier
                .width(SidebarWidth)
                .fillMaxHeight(),
        )
        Children(
            stack = component.stack,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            animation = stackAnimation(fade()),
        ) { child ->
            when (val instance = child.instance) {
                RootComponent.Child.Home -> WelcomePane(onNewChat = { component.chatList.onIntent(ChatListIntent.NewChat) })
                is RootComponent.Child.Chat -> ChatScreen(instance.component)
                is RootComponent.Child.Models -> ModelsScreen(instance.component)
                is RootComponent.Child.Settings -> SettingsScreen(instance.component)
            }
        }
    }
}
