package ai.prism.ui.screens

import ai.prism.resources.Res
import ai.prism.resources.action_new_chat
import ai.prism.resources.logo_description
import ai.prism.resources.prism_logo
import ai.prism.resources.welcome_text
import ai.prism.resources.welcome_title
import ai.prism.ui.components.PrismPanel
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

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
