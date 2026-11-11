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

/**
 * sherpa-onnx на Windows (и Linux в CI): Java API, нативные библиотеки sherpa-onnx и onnxruntime
 * распаковываются из jar при первом обращении.
 */
class DesktopSherpaSpeechEngine(threads: Int) : SherpaSpeechEngine(threads) {

    override fun createRecognizer(spec: RecognizerSpec): NativeRecognizer {
        val files = spec.files
        val model = OfflineModelConfig.builder()
            .setTokens(files.getValue("tokens"))
            .setNumThreads(threads)
            .setDebug(false)
        when (spec.engine) {
            SpeechEngineType.WHISPER -> model
                .setWhisper(
                    OfflineWhisperModelConfig.builder()
                        .setEncoder(files.getValue("encoder"))
                        .setDecoder(files.getValue("decoder"))
                        .setLanguage(spec.language)
                        .build(),
                )
                .setModelType("whisper")
            SpeechEngineType.NEMO_CTC -> model
                .setNemo(OfflineNemoEncDecCtcModelConfig.builder().setModel(files.getValue("model")).build())
            SpeechEngineType.PIPER -> error("${spec.modelId} is a voice, not a recognizer")
        }
        val recognizer = OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(model.build()).build())
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
        val vits = OfflineTtsVitsModelConfig.builder()
            .setModel(files.model)
            .setTokens(files.tokens)
            .setDataDir(files.dataDir)
            .build()
        val model = OfflineTtsModelConfig.builder().setVits(vits).setNumThreads(threads).setDebug(false).build()
        val tts = OfflineTts(OfflineTtsConfig.builder().setModel(model).build())
        return object : NativeVoice {
            override fun generate(text: String, speed: Float): SpeechAudio {
                val audio = tts.generate(text, 0, speed)
                return SpeechAudio(audio.samples, audio.sampleRate)
            }

            override fun release() = tts.release()
        }
    }

    override fun createVad(modelPath: String, minSilenceSeconds: Float): NativeVad {
        val silero = SileroVadModelConfig.builder()
            .setModel(modelPath)
            .setThreshold(VAD_THRESHOLD)
            .setMinSilenceDuration(minSilenceSeconds)
            .setMinSpeechDuration(VAD_MIN_SPEECH_SECONDS)
            .setWindowSize(VAD_WINDOW)
            .setMaxSpeechDuration(VAD_MAX_SPEECH_SECONDS)
            .build()
        val vad = Vad(VadModelConfig.builder().setSileroVadModelConfig(silero).setNumThreads(1).setDebug(false).build())
        return object : NativeVad {
            override fun acceptWaveform(samples: FloatArray) = vad.acceptWaveform(samples)
            override fun isSpeechDetected(): Boolean = vad.isSpeechDetected
            override fun empty(): Boolean = vad.empty()
            override fun frontSamples(): FloatArray = vad.front().samples
            override fun pop() = vad.pop()
            override fun flush() = vad.flush()
            override fun release() = vad.release()
        }
    }
}
