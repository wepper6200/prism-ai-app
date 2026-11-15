@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package ai.prism.ios

import ai.prism.apple.native.*
import ai.prism.domain.model.SpeechEngineType
import ai.prism.domain.voice.*
import ai.prism.voice.Pcm
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class IosSpeechEngine(private val host: IosHost, private val threads: Int) : SpeechEngine {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private var recognizer: COpaquePointer? = null
    private var recognizerSpec: RecognizerSpec? = null
    private var voice: COpaquePointer? = null
    private var voiceKey = ""
    override val isSupported = true
    private fun failure(): Nothing = throw VoiceException(VoiceError.ENGINE, "Cannot run the local speech model")
    override suspend fun transcribe(spec: RecognizerSpec, audio: SpeechAudio): String = withContext(dispatcher) {
        if (recognizerSpec != spec) {
            prism_asr_destroy(recognizer); recognizer = null; recognizerSpec = null
            recognizer = prism_asr_create(
                if (spec.engine == SpeechEngineType.WHISPER) "whisper" else "nemo_ctc",
                spec.files["encoder"] ?: spec.files["model"].orEmpty(), spec.files["decoder"].orEmpty(),
                spec.files["tokens"].orEmpty(), spec.language, threads,
            ) ?: failure()
            recognizerSpec = spec
        }
        if (audio.samples.isEmpty()) return@withContext ""
        audio.samples.usePinned { samples ->
            val result = prism_asr_decode(recognizer, samples.addressOf(0), audio.samples.size, audio.sampleRate) ?: failure()
            try { result.toKString() } finally { prism_free(result) }
        }
    }
    private fun files(path: String, depth: Int = 0): List<String> = if (depth > 3) emptyList() else host.children(path).flatMap {
        val child = "$path/$it"
        if (host.isDirectory(child)) files(child, depth + 1) else listOf(child)
    }
    override suspend fun synthesize(spec: VoiceSpec, text: String, speed: Float): SpeechAudio = withContext(dispatcher) {
        val key = spec.modelId + "|" + spec.directory
        if (voiceKey != key) {
            prism_tts_destroy(voice); voice = null; voiceKey = ""
            val files = files(spec.directory)
            val json = files.firstOrNull { it.endsWith("/tts.json") } ?: failure()
            val directory = json.substringBeforeLast('/')
            fun model(prefix: String) = files.filter { it.substringBeforeLast('/') == directory && it.substringAfterLast('/').startsWith(prefix) && it.endsWith(".onnx") }.minByOrNull { it.length } ?: failure()
            voice = prism_tts_create(model("duration_predictor"), model("text_encoder"), model("vector_estimator"), model("vocoder"), json, "$directory/unicode_indexer.bin", "$directory/voice.bin", threads) ?: failure()
            voiceKey = key
        }
        val language = spec.language.filter { it.isLetter() || it == '-' }.take(16)
        val result = prism_tts_generate(voice, text, spec.speaker, speed, spec.steps.takeIf { it > 0 } ?: 5, "{\"lang\":\"$language\"}")
        try { result.useContents { if (count <= 0 || samples == null) failure(); SpeechAudio(FloatArray(count) { samples!![it] }, sample_rate) } }
        finally { prism_audio_destroy(result) }
    }
    override fun voiceDetector(modelPath: String, minSilenceSeconds: Float): VoiceDetector {
        val pointer = prism_vad_create(modelPath, minSilenceSeconds) ?: failure()
        return object : VoiceDetector {
            private var closed = false
            override fun accept(samples: FloatArray) { check(!closed); if (samples.isNotEmpty()) samples.usePinned { if (prism_vad_accept(pointer, it.addressOf(0), samples.size) == 0) failure() } }
            override val isSpeaking: Boolean get() = if (closed) false else prism_vad_speaking(pointer).let { if (it < 0) failure(); it != 0 }
            override fun takeSegments(): List<FloatArray> = buildList {
                check(!closed)
                while (true) {
                    val result = prism_vad_pop(pointer)
                    val segment = result.useContents { if (count < 0) failure(); if (count == 0 || samples == null) null else FloatArray(count) { samples!![it] } }
                    result.useContents { prism_free(samples) }
                    if (segment == null) break
                    add(segment)
                }
            }
            override fun flush() { check(!closed); if (prism_vad_flush(pointer) == 0) failure() }
            override fun close() { if (!closed) { closed = true; prism_vad_destroy(pointer) } }
        }
    }
    override suspend fun release() = withContext(dispatcher) {
        prism_asr_destroy(recognizer); recognizer = null; recognizerSpec = null
        prism_tts_destroy(voice); voice = null; voiceKey = ""
    }
}

internal class IosAudioInput(private val host: IosHost) : AudioInput {
    override fun record() = callbackFlow {
        val task = host.recordPcm(SPEECH_SAMPLE_RATE, { data ->
            val bytes = data.toBytes()
            trySend(Pcm.toFloats(bytes, bytes.size))
        }, { close(VoiceException(VoiceError.MICROPHONE, it)) })
        awaitClose { task.cancel() }
    }
}
internal class IosAudioOutput(private val host: IosHost) : AudioOutput {
    override suspend fun play(audio: SpeechAudio) = suspendCancellableCoroutine { continuation ->
        val task = host.playPcm(Pcm.toBytes(audio.samples).toNativeData(), audio.sampleRate) { error ->
            if (continuation.isActive) {
                if (error == null) continuation.resume(Unit)
                else continuation.resumeWithException(VoiceException(VoiceError.PLAYBACK, error))
            }
        }
        continuation.invokeOnCancellation { task.cancel() }
    }
}
