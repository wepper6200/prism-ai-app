@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package ai.prism.ios

import ai.prism.domain.inference.*
import ai.prism.domain.model.*
import ai.prism.inference.llama.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

/** Called only by the simulator test launch argument; no network or user data is involved. */
fun runOfflineSmoke(modelPath: String, completion: (String?) -> Unit) {
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        val host = appleHost
        val device = object : DeviceInfo {
            override val totalRamBytes = host.totalMemory()
            override fun availableRamBytes() = host.availableMemory()
            override val inferenceThreads = 2
            override val preferGpu = false
        }
        val engine = LlamaInferenceEngine(NativeLibraryLoader { "" }, device, IosImageCodec(host))
        val error = try {
            val model = CatalogModel("apple-smoke", "Prism test", "fixture", "tiny", "F32", emptyMap(), "", "tiny.gguf", host.size(modelPath), "", 2048, 1, ChatFormat.RAW)
            engine.load(model, modelPath)
            val request = GenerationRequest("", listOf(PromptMessage(MessageRole.USER, "Once upon a time")), maxTokens = 32, sampling = SamplingParams(temperature = 0f))
            val first = engine.generate(request).toList().joinToString("")
            check(first.isNotBlank())
            check(engine.generate(request).toList().joinToString("") == first)
            check(engine.generate(request.copy(maxTokens = 300)).take(1).toList().size == 1)
            check(engine.generate(request).toList().joinToString("") == first)
            null
        } catch (e: Exception) { e.message ?: "Offline smoke failed" }
        finally { engine.unload() }
        withContext(Dispatchers.Main) { completion(error) }
    }
}
