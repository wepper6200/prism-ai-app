package ai.prism.ui.components

import ai.prism.domain.documents.DocumentError
import ai.prism.domain.documents.DocumentFormat
import ai.prism.domain.documents.DocumentStatus
import ai.prism.domain.documents.MessageSource
import ai.prism.presentation.chat.DocumentItem
import ai.prism.resources.Res
import ai.prism.resources.document_error_failed
import ai.prism.resources.document_error_interrupted
import ai.prism.resources.document_error_memory
import ai.prism.resources.document_error_model
import ai.prism.resources.document_error_no_model
import ai.prism.resources.document_error_no_text
import ai.prism.resources.document_error_too_large
import ai.prism.resources.document_error_unreadable
import ai.prism.resources.document_error_unsupported
import ai.prism.resources.document_indexing
import ai.prism.resources.document_ready
import ai.prism.resources.document_remove
import ai.prism.resources.document_sources
import ai.prism.resources.document_pages
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Файлы диалога над полем ввода: имя, состояние подготовки, кнопка «Убрать». */
@Composable
fun DocumentStrip(documents: List<DocumentItem>, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.testTag("document_strip"),
        contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(documents, key = { it.id }) { document -> DocumentChip(document, onRemove) }
    }
}

@Composable
private fun DocumentChip(document: DocumentItem, onRemove: (String) -> Unit) {
    val failed = document.status == DocumentStatus.FAILED
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.testTag("document_chip"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        ) {
            when (document.status) {
                DocumentStatus.INDEXING -> CircularProgressIndicator(
                    progress = { document.progress ?: 0f },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                )
                DocumentStatus.FAILED -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                DocumentStatus.READY -> Icon(document.format.icon(), contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.padding(start = 8.dp).widthIn(max = 200.dp)) {
                Text(
                    text = document.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    text = when (document.status) {
                        DocumentStatus.INDEXING -> stringResource(Res.string.document_indexing, formatPercent(document.progress ?: 0f))
                        DocumentStatus.READY -> stringResource(Res.string.document_ready)
                        DocumentStatus.FAILED -> stringResource((document.error ?: DocumentError.FAILED).message())
                    },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                )
            }
            IconButton(onClick = { onRemove(document.id) }, modifier = Modifier.testTag("document_remove")) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.document_remove), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Подпись под ответом: файлы и страницы, на которые он опирался. */
@Composable
fun SourcesLine(sources: List<MessageSource>, modifier: Modifier = Modifier) {
    val pagesLabel = stringResource(Res.string.document_pages)
    val text = sources.groupBy { it.documentName }.entries.joinToString(", ") { (name, items) ->
        val pages = items.mapNotNull { it.page }.distinct().sorted()
        if (pages.isEmpty()) name else "$name ($pagesLabel ${pages.joinToString(", ")})"
    }
    Text(
        text = stringResource(Res.string.document_sources, text),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag("message_sources"),
    )
}

private fun DocumentFormat.icon() = when (this) {
    DocumentFormat.PDF -> Icons.Outlined.PictureAsPdf
    DocumentFormat.DOCX -> Icons.Outlined.Description
    DocumentFormat.MARKDOWN, DocumentFormat.TEXT -> Icons.AutoMirrored.Outlined.Article
}

fun DocumentError.message(): StringResource = when (this) {
    DocumentError.UNSUPPORTED -> Res.string.document_error_unsupported
    DocumentError.UNREADABLE -> Res.string.document_error_unreadable
    DocumentError.NO_TEXT -> Res.string.document_error_no_text
    DocumentError.TOO_LARGE -> Res.string.document_error_too_large
    DocumentError.NO_EMBEDDING_MODEL -> Res.string.document_error_no_model
    DocumentError.MODEL_CORRUPTED -> Res.string.document_error_model
    DocumentError.NOT_ENOUGH_MEMORY -> Res.string.document_error_memory
    DocumentError.INTERRUPTED -> Res.string.document_error_interrupted
    DocumentError.FAILED -> Res.string.document_error_failed
}
