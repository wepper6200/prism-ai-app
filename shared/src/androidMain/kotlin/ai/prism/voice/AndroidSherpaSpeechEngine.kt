package ai.prism.voice

import ai.prism.domain.model.SpeechEngineType
import ai.prism.domain.voice.RecognizerSpec
import ai.prism.domain.voice.SpeechAudio
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** sherpa-onnx на Android: Kotlin API из AAR, модели читаются из файлов (не из assets). */
class AndroidSherpaSpeechEngine(threads: Int) : SherpaSpeechEngine(threads) {

    override fun createRecognizer(spec: RecognizerSpec): NativeRecognizer {
        val files = spec.files
        val model = when (spec.engine) {
            SpeechEngineType.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = files.getValue("encoder"),
                    decoder = files.getValue("decoder"),
                    language = spec.language,
                ),
                tokens = files.getValue("tokens"),
                numThreads = threads,
                modelType = "whisper",
            )
            SpeechEngineType.NEMO_CTC -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = files.getValue("model")),
                tokens = files.getValue("tokens"),
                numThreads = threads,
            )
            SpeechEngineType.PIPER -> error("${spec.modelId} is a voice, not a recognizer")
        }
        val recognizer = OfflineRecognizer(assetManager = null, config = OfflineRecognizerConfig(modelConfig = model))
        return object : NativeRecognizer {
            override fun transcribe(samples: FloatArray, sampleRate: Int): String {
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    recognizer.decode(stream)
                    return recognizer.getResult(stream).text
                } finally {
                    stream.release()
                }
            }

            override fun release() = recognizer.release()
        }
    }

    override fun createVoice(files: PiperFiles): NativeVoice {
        val tts = OfflineTts(
            assetManager = null,
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(model = files.model, tokens = files.tokens, dataDir = files.dataDir),
                    numThreads = threads,
                ),
            ),
        )
        return object : NativeVoice {
            override fun generate(text: String, speed: Float): SpeechAudio {
                val audio = tts.generate(text = text, sid = 0, speed = speed)
                return SpeechAudio(audio.samples, audio.sampleRate)
            }

            override fun release() = tts.release()
        }
    }

    override fun createVad(modelPath: String, minSilenceSeconds: Float): NativeVad {
        val vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelPath,
                    threshold = VAD_THRESHOLD,
                    minSilenceDuration = minSilenceSeconds,
                    minSpeechDuration = VAD_MIN_SPEECH_SECONDS,
                    windowSize = VAD_WINDOW,
                    maxSpeechDuration = VAD_MAX_SPEECH_SECONDS,
                ),
                numThreads = 1,
            ),
        )
        return object : NativeVad {
            override fun acceptWaveform(samples: FloatArray) = vad.acceptWaveform(samples)
            override fun isSpeechDetected(): Boolean = vad.isSpeechDetected()
            override fun empty(): Boolean = vad.empty()
            override fun frontSamples(): FloatArray = vad.front().samples
            override fun pop() = vad.pop()
            override fun flush() = vad.flush()
            override fun release() = vad.release()
        }
    }
}
