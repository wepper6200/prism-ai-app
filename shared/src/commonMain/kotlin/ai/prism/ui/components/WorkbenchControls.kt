package ai.prism.ui.components

import ai.prism.domain.model.GenerationProfile
import ai.prism.presentation.chat.ChatIntent
import ai.prism.presentation.chat.ChatState
import ai.prism.presentation.chat.ChatWorkbench
import ai.prism.resources.*
import ai.prism.service.WorkbenchNotice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

fun GenerationProfile.label(): StringResource = when (this) {
    GenerationProfile.STANDARD -> Res.string.work_standard
    GenerationProfile.CODING -> Res.string.work_coding
    GenerationProfile.CONVERSATION -> Res.string.work_conversation
    GenerationProfile.CREATIVE -> Res.string.work_creative
}
private fun GenerationProfile.hint(): StringResource = when (this) {
    GenerationProfile.STANDARD -> Res.string.work_standard_hint
    GenerationProfile.CODING -> Res.string.work_coding_hint
    GenerationProfile.CONVERSATION -> Res.string.work_conversation_hint
    GenerationProfile.CREATIVE -> Res.string.work_creative_hint
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkbenchControls(state: ChatState, tools: ChatWorkbench, onIntent: (ChatIntent) -> Unit, open: Boolean, onDismiss: () -> Unit) {
    if (open) ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val enabled = !state.modelChangeBlocked && !state.isGenerating && !state.busyInOtherChat && !tools.work.busy
        Column(Modifier.testTag("workbench_panel").fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.work_title), style = MaterialTheme.typography.headlineSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GenerationProfile.entries.forEach { profile ->
                    FilterChip(selected = profile == tools.profile, enabled = enabled, onClick = { onIntent(ChatIntent.SetProfile(profile)) },
                        label = { Text(stringResource(profile.label())) }, modifier = Modifier.testTag("profile_" + profile.name))
                }
            }
            Text(stringResource(tools.profile.hint()), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text(stringResource(Res.string.work_bench), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.work_bench_hint), style = MaterialTheme.typography.bodySmall)
            if (tools.work.benchmarking != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(Res.string.work_bench_running))
                TextButton(onClick = { onIntent(ChatIntent.Stop) }, modifier = Modifier.testTag("benchmark_stop")) { Text(stringResource(Res.string.action_cancel)) }
            }
            val choices = state.modelChoices.filter { it.chatReady }
            if (choices.isEmpty()) Text(stringResource(Res.string.work_bench_empty))
            choices.forEach { entry ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.model.name, style = MaterialTheme.typography.titleSmall)
                        if (!entry.fitsInMemory) Text(stringResource(Res.string.error_not_enough_memory), style = MaterialTheme.typography.bodySmall)
                        tools.work.benchmarks.firstOrNull { it.modelId == entry.model.id }?.let { result ->
                            Text(stringResource(Res.string.work_bench_load, result.loadMillis), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(Res.string.work_bench_first, result.firstTextMillis), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(Res.string.work_bench_speed, result.charactersPerSecond.roundToInt()), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(Res.string.work_bench_memory, result.estimatedRamMb, result.context), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onIntent(ChatIntent.Benchmark(entry.model.id)) }, enabled = enabled && entry.fitsInMemory, modifier = Modifier.testTag("benchmark_" + entry.model.id)) { Text(stringResource(Res.string.work_bench_run)) }
                    }
                }
            }
            val best = tools.work.benchmarks.filter { result -> choices.any { it.model.id == result.modelId && it.fitsInMemory } && result.charactersPerSecond > 0 }.maxByOrNull { it.charactersPerSecond }
            if (best != null) {
                Text(stringResource(Res.string.work_bench_recommend), style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = { onIntent(ChatIntent.ApplyBenchmark(best.modelId)); onDismiss() }, enabled = enabled, modifier = Modifier.testTag("benchmark_apply")) {
                    Text(stringResource(Res.string.work_bench_apply))
                }
            }
        }
    }
}

@Composable
fun WorkbenchNoticeBanner(notice: WorkbenchNotice, onDismiss: () -> Unit) {
    val label = when (notice) {
        WorkbenchNotice.UNDONE -> Res.string.work_undone
        WorkbenchNotice.CONFLICT -> Res.string.work_undo_conflict
        WorkbenchNotice.FAILED -> Res.string.work_failed
        WorkbenchNotice.BRANCH_FAILED -> Res.string.work_branch_failed
        WorkbenchNotice.BENCHMARK_FAILED -> Res.string.work_bench_failed
        WorkbenchNotice.CANCELLED -> Res.string.work_cancelled
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("workbench_notice")) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.image_close)) }
        }
    }
}
