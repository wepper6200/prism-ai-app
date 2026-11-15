package ai.prism.ui.components

import ai.prism.resources.Res
import ai.prism.resources.nav_chats
import ai.prism.resources.nav_models
import ai.prism.resources.nav_settings
import ai.prism.ui.theme.LocalPrismStyle
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

@Composable
fun AppNavigation(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val labels = listOf(Res.string.nav_chats, Res.string.nav_models, Res.string.nav_settings)
    val icons = listOf(Icons.Outlined.ChatBubbleOutline, Icons.Outlined.Memory, Icons.Outlined.Settings)
    val tags = listOf("nav_chats", "nav_models", "nav_settings")
    PrismPanel(modifier.padding(horizontal = 12.dp, vertical = 8.dp), RoundedCornerShape(28.dp)) {
        NavigationBar(containerColor = Color.Transparent, tonalElevation = if (LocalPrismStyle.current.isGlass) 0.dp else 2.dp) {
            labels.forEachIndexed { index, label ->
                NavigationBarItem(
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    icon = { Icon(icons[index], contentDescription = null) },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.testTag(tags[index]),
                )
            }
        }
    }
}
