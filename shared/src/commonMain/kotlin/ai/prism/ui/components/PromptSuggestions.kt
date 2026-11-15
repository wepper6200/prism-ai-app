package ai.prism.ui.components

import ai.prism.resources.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource

@Composable
fun PromptSuggestions(onPrompt: (String) -> Unit, modifier: Modifier = Modifier) {
    var category by rememberSaveable { mutableStateOf(0) }
    val labels = listOf(Res.string.prompt_learn, Res.string.prompt_write, Res.string.prompt_plan)
    Column(modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.align(Alignment.CenterHorizontally).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                FilterChip(selected = category == index, onClick = { category = index }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("prompt_category_$index"))
            }
        }
        AnimatedContent(targetState = category, label = "promptCategory") { selected ->
            val prompts = when (selected) {
                1 -> listOf(Triple(Res.string.prompt_write_title, Res.string.prompt_write_detail, Res.string.suggestion_write_prompt), Triple(Res.string.prompt_rewrite_title, Res.string.prompt_rewrite_detail, Res.string.prompt_rewrite_text))
                2 -> listOf(Triple(Res.string.prompt_plan_title, Res.string.prompt_plan_detail, Res.string.suggestion_plan_prompt), Triple(Res.string.prompt_ideas_title, Res.string.prompt_ideas_detail, Res.string.prompt_ideas_text))
                else -> listOf(Triple(Res.string.prompt_explain_title, Res.string.prompt_explain_detail, Res.string.suggestion_explain_prompt), Triple(Res.string.prompt_compare_title, Res.string.prompt_compare_detail, Res.string.prompt_compare_text))
            }
            val icon = when (selected) { 1 -> Icons.Outlined.EditNote; 2 -> Icons.Outlined.Route; else -> Icons.Outlined.AutoAwesome }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                prompts.forEachIndexed { index, (title, detail, prompt) ->
                    val text = stringResource(prompt)
                    PrismActionCard(stringResource(title), stringResource(detail), icon, { onPrompt(text) }, Modifier.fillMaxWidth().testTag("chat_suggestion_$index"))
                }
            }
        }
    }
}
