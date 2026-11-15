@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package ai.prism.ios

import ai.prism.domain.inference.*
import ai.prism.domain.model.*
import ai.prism.inference.llama.*
import ai.prism.data.catalog.ModelCatalog
import ai.prism.domain.voice.*
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
            engine.unload()
            val catalog = ModelCatalog().load()
            val storage = IosModelStorage(host)
            val speech = IosSpeechEngine(host, 2)
            try {
                val voice = catalog.first { it.id == "supertonic-3-int8" }
                val audio = speech.synthesize(VoiceSpec(voice.id, storage.unpackedPath(voice.mainFile), engine = SpeechEngineType.SUPERTONIC, language = "ru", speaker = 5), "Привет! Это проверка голоса.", 1f)
                check(audio.durationSeconds > 0.3f)
                val recognition = catalog.first { it.id == "gigaam-v3-ctc-punct-ru-int8" }
                val files = mapOf(recognition.mainRole to storage.filePath(recognition.mainFile)) + recognition.components.associate { it.role to storage.filePath(it.file) }
                val text = speech.transcribe(RecognizerSpec(recognition.id, SpeechEngineType.NEMO_CTC, files, "ru"), audio).lowercase()
                check("привет" in text || "проверка" in text) { "Russian offline speech round trip: $text" }
                val vad = speech.voiceDetector(checkNotNull(files["vad"]), 0.4f)
                try {
                    val resampled = FloatArray((audio.samples.size.toLong() * SPEECH_SAMPLE_RATE / audio.sampleRate).toInt()) { i ->
                        val position = i.toDouble() * audio.sampleRate / SPEECH_SAMPLE_RATE
                        val index = position.toInt().coerceAtMost(audio.samples.lastIndex)
                        val next = (index + 1).coerceAtMost(audio.samples.lastIndex)
                        audio.samples[index] + (audio.samples[next] - audio.samples[index]) * (position - index).toFloat()
                    }
                    resampled.asList().chunked(512).forEach { vad.accept(it.toFloatArray()) }
                    vad.accept(FloatArray(SPEECH_SAMPLE_RATE)); vad.flush()
                    check(vad.takeSegments().isNotEmpty())
                } finally { vad.close() }
            } finally { speech.release() }
            null
        } catch (e: Exception) { e.message ?: "Offline smoke failed" }
        finally { engine.unload() }
        withContext(Dispatchers.Main) { completion(error) }
    }
}
