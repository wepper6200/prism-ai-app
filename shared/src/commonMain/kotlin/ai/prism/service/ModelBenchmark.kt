package ai.prism.service

import ai.prism.data.settings.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class ModelBenchmarkResult(
    val modelId: String,
    val loadMillis: Long,
    val firstTextMillis: Long,
    val generationMillis: Long,
    val characters: Int,
    val estimatedRamMb: Int,
    val at: Long,
    val context: Int = 4096,
) {
    val charactersPerSecond: Double get() = if (generationMillis > 0) characters * 1000.0 / generationMillis else 0.0
}

class ModelBenchmarkHistory(private val store: KeyValueStore? = null) {
    private val serializer = ListSerializer(ModelBenchmarkResult.serializer())
    fun load(): List<ModelBenchmarkResult> = try {
        store?.getString("model_benchmarks")?.let { Json.decodeFromString(serializer, it) }?.takeLast(12).orEmpty()
    } catch (_: IllegalArgumentException) { emptyList() }
    fun save(results: List<ModelBenchmarkResult>) { store?.putString("model_benchmarks", Json.encodeToString(serializer, results.takeLast(12))) }
}

enum class WorkbenchNotice { UNDONE, CONFLICT, FAILED, BRANCH_FAILED, BENCHMARK_FAILED, CANCELLED }
data class WorkbenchState(
    val undoReplies: Set<String> = emptySet(),
    val notice: WorkbenchNotice? = null,
    val busy: Boolean = false,
    val benchmarking: String? = null,
    val benchmarks: List<ModelBenchmarkResult> = emptyList(),
)
