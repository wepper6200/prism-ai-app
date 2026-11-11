package ai.prism.voice

import ai.prism.domain.model.SpeechEngineType
import ai.prism.domain.voice.RecognizerSpec
import ai.prism.domain.voice.SpeechAudio
import ai.prism.domain.voice.VoiceSpec
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTtsCallback
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
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

    override fun createVoice(files: VoiceFiles): NativeVoice {
        val model = OfflineTtsModelConfig.builder().setNumThreads(threads).setDebug(false)
        when (files) {
            is PiperFiles -> model.setVits(
                OfflineTtsVitsModelConfig.builder()
                    .setModel(files.model)
                    .setTokens(files.tokens)
                    .setDataDir(files.dataDir)
                    .build(),
            )
            is SupertonicFiles -> model.setSupertonic(
                OfflineTtsSupertonicModelConfig.builder()
                    .setDurationPredictor(files.durationPredictor)
                    .setTextEncoder(files.textEncoder)
                    .setVectorEstimator(files.vectorEstimator)
                    .setVocoder(files.vocoder)
                    .setTtsJson(files.ttsJson)
                    .setUnicodeIndexer(files.unicodeIndexer)
                    .setVoiceStyle(files.voiceStyle)
                    .build(),
            )
        }
        val tts = OfflineTts(OfflineTtsConfig.builder().setModel(model.build()).build())
        val supertonic = files is SupertonicFiles
        return object : NativeVoice {
            override fun generate(text: String, spec: VoiceSpec, speed: Float): SpeechAudio {
                val audio = if (supertonic) {
                    val config = GenerationConfig()
                    config.setSpeed(speed)
                    config.setSid(spec.speaker)
                    config.setNumSteps(spec.steps.takeIf { it > 0 } ?: DEFAULT_SUPERTONIC_STEPS)
                    if (spec.language.isNotEmpty()) config.setExtra(mapOf("lang" to spec.language))
                    tts.generateWithConfigAndCallback(text, config, OfflineTtsCallback { CONTINUE })
                } else {
                    tts.generate(text, spec.speaker, speed)
                }
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
