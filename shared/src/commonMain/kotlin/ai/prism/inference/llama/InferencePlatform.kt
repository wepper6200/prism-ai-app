package ai.prism.inference.llama

import kotlinx.coroutines.CoroutineDispatcher

internal expect fun inferenceDispatcher(name: String): CoroutineDispatcher
internal expect fun isGguf(path: String, expectedSize: Long): Boolean
