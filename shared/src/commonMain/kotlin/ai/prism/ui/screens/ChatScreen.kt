package ai.prism.ui.screens

import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.presentation.chat.ChatComponent
import ai.prism.presentation.chat.ChatIntent
import ai.prism.presentation.chat.ChatState
import ai.prism.presentation.chat.MessageItem
import ai.prism.resources.Res
import ai.prism.resources.chat_busy_other
import ai.prism.resources.chat_greeting_text
import ai.prism.resources.chat_greeting_title
import ai.prism.resources.chat_model_loading
import ai.prism.resources.chat_need_model_text
import ai.prism.resources.chat_need_model_title
import ai.prism.resources.chat_no_model
import ai.prism.resources.chat_open_models
import ai.prism.resources.chat_retry
import ai.prism.resources.chat_stopped
import ai.prism.resources.chat_thinking
import ai.prism.resources.chat_untitled
import ai.prism.resources.logo_description
import ai.prism.resources.nav_models
import ai.prism.resources.prism_logo
import ai.prism.ui.LocalExpandedLayout
import ai.prism.ui.components.ChatInputBar
import ai.prism.ui.components.EmptyState
import ai.prism.ui.components.PrismTopBar
import ai.prism.ui.components.formatPercent
import ai.prism.ui.components.message
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

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
            .padding(if (expanded) 0.dp else 12.dp)
            .testTag("chat_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrismTopBar(
            title = state.title ?: stringResource(Res.string.chat_untitled),
            subtitle = state.modelName ?: stringResource(Res.string.chat_no_model),
            onBack = if (expanded) null else ({ component.onIntent(ChatIntent.Back) }),
            actions = {
                IconButton(onClick = { component.onIntent(ChatIntent.OpenModels) }) {
                    Icon(Icons.Outlined.Memory, contentDescription = stringResource(Res.string.nav_models))
                }
            },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (state.messages.isEmpty()) {
                ChatEmptyState(state, onOpenModels = { component.onIntent(ChatIntent.OpenModels) })
            } else {
                MessageList(
                    state = state,
                    onRetry = { component.onIntent(ChatIntent.Retry) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (state.busyInOtherChat) {
            Text(
                text = stringResource(Res.string.chat_busy_other),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ChatInputBar(
                text = state.draft,
                onTextChange = { component.onIntent(ChatIntent.DraftChanged(it)) },
                canSend = state.canSend,
                isGenerating = state.isGenerating,
                onSend = { component.onIntent(ChatIntent.Send) },
                onStop = { component.onIntent(ChatIntent.Stop) },
                modifier = Modifier.widthIn(max = 880.dp),
            )
        }
    }
}

@Composable
private fun ChatEmptyState(state: ChatState, onOpenModels: () -> Unit) {
    if (state.modelName != null && state.modelDownloaded) {
        EmptyState(
            icon = Icons.Outlined.AutoAwesome,
            title = stringResource(Res.string.chat_greeting_title),
            text = stringResource(Res.string.chat_greeting_text),
        )
    } else {
        EmptyState(
            icon = Icons.Outlined.Memory,
            title = stringResource(Res.string.chat_need_model_title),
            text = stringResource(Res.string.chat_need_model_text),
            action = {
                FilledTonalButton(onClick = onOpenModels, modifier = Modifier.testTag("open_models")) {
                    Text(stringResource(Res.string.chat_open_models))
                }
            },
        )
    }
}

@Composable
private fun MessageList(
    state: ChatState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Следуем за ответом, пока пользователь сам не прокрутил историю вверх.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) follow = !listState.canScrollForward
    }
    val lastLength = state.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(state.messages.size) { follow = true }
    LaunchedEffect(state.messages.size, lastLength) {
        if (follow && state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex, Int.MAX_VALUE)
        }
    }
    val lastId = state.messages.lastOrNull()?.id
    LazyColumn(
        state = listState,
        modifier = modifier.testTag("message_list"),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(state.messages, key = { it.id }) { message ->
            Box(Modifier.widthIn(max = 880.dp).fillMaxWidth()) {
                when (message.role) {
                    MessageRole.USER -> UserMessage(message)
                    MessageRole.ASSISTANT -> AssistantMessage(
                        message = message,
                        loadingProgress = state.modelLoadingProgress.takeIf { message.isStreaming },
                        canRetry = message.id == lastId && !state.isGenerating && !state.busyInOtherChat,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun UserMessage(message: MessageItem) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 600.dp).padding(start = 48.dp),
        ) {
            SelectionContainer {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: MessageItem,
    loadingProgress: Float?,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(end = 24.dp)) {
        Image(
            painter = painterResource(Res.drawable.prism_logo),
            contentDescription = stringResource(Res.string.logo_description),
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                loadingProgress != null && message.text.isEmpty() -> ModelLoading(loadingProgress)
                message.isStreaming && message.text.isEmpty() -> TypingIndicator()
                message.text.isNotEmpty() -> SelectionContainer {
                    Text(
                        text = if (message.isStreaming) message.text + CURSOR else message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("assistant_text"),
                    )
                }
            }
            when (message.status) {
                MessageStatus.STOPPED -> MessageFooter(
                    text = stringResource(Res.string.chat_stopped),
                    isError = false,
                    canRetry = canRetry,
                    onRetry = onRetry,
                )
                MessageStatus.FAILED -> MessageFooter(
                    text = stringResource((message.error ?: ai.prism.domain.model.GenerationError.UNKNOWN).message()),
                    isError = true,
                    canRetry = canRetry,
                    onRetry = onRetry,
                )
                MessageStatus.COMPLETE, MessageStatus.GENERATING -> Unit
            }
        }
    }
}

@Composable
private fun ModelLoading(progress: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(Res.string.chat_model_loading, formatPercent(progress)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth())
    }
}

/** Три пульсирующие точки, пока модель обрабатывает промпт. */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1200), RepeatMode.Restart),
        label = "typingPhase",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 6.dp).testTag("typing_indicator"),
    ) {
        repeat(3) { index ->
            val active = phase.toInt() == index
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(if (active) 1f else 0.35f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(Res.string.chat_thinking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageFooter(
    text: String,
    isError: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            if (canRetry) {
                TextButton(onClick = onRetry, modifier = Modifier.testTag("retry_button")) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.chat_retry))
                }
            }
        }
    }
}

private const val CURSOR = " ▍"
