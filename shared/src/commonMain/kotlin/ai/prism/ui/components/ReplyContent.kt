package ai.prism.ui.components

import ai.prism.core.ReplyBlock
import ai.prism.core.ReplyMarkup
import ai.prism.resources.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
fun ReplyContent(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { ReplyMarkup.parse(text) }
    Column(modifier.testTag("assistant_text"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ReplyBlock.Prose -> ProseContent(block.text)
                is ReplyBlock.Code -> CodeContent(block)
                is ReplyBlock.Table -> TableContent(block)
            }
        }
    }
}

@Composable
private fun ProseContent(text: String) {
    val formatted = buildAnnotatedString {
        val pattern = Regex("""\*\*(.+?)\*\*|\u0060([^\u0060]+)\u0060""")
        text.lines().forEachIndexed { index, original ->
            if (index > 0) append("\n")
            val heading = Regex("^#{1,6}\\s+").containsMatchIn(original)
            val line = original.replace(Regex("^#{1,6}\\s+"), "")
                .replace(Regex("^\\s*[-*] "), "• ")
            withStyle(SpanStyle(fontWeight = if (heading) FontWeight.Bold else FontWeight.Normal)) {
                var offset = 0
                pattern.findAll(line).forEach { match ->
                    append(line.substring(offset, match.range.first))
                    val bold = match.groups[1]?.value
                    withStyle(if (bold != null) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(bold ?: match.groups[2]!!.value)
                    }
                    offset = match.range.last + 1
                }
                append(line.substring(offset))
            }
        }
    }
    Text(formatted, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun CodeContent(block: ReplyBlock.Code) {
    val copy = rememberTextCopier()
    var copied by remember(block) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    PrismPanel(shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(block.language.ifBlank { stringResource(Res.string.reply_code) }, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp))
                IconButton(onClick = { copied = copy(block.text) }, modifier = Modifier.testTag("copy_code")) {
                    Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, stringResource(if (copied) Res.string.message_copied else Res.string.reply_code_copy))
                }
            }
            SelectionContainer {
                Text(block.text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp).testTag("reply_code"))
            }
        }
    }
}

@Composable
private fun TableContent(block: ReplyBlock.Table) {
    PrismPanel(shape = MaterialTheme.shapes.medium) {
        Column(Modifier.horizontalScroll(rememberScrollState()).testTag("reply_table")) {
            (listOf(block.header) + block.rows).forEachIndexed { index, cells ->
                Surface(color = if (index == 0) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row {
                        cells.forEach { cell ->
                            Text(cell, fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(160.dp).padding(12.dp))
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
