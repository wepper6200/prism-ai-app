package ai.prism.voice

import ai.prism.domain.voice.AudioInput
import ai.prism.domain.voice.AudioOutput
import ai.prism.domain.voice.SPEECH_SAMPLE_RATE
import ai.prism.domain.voice.SpeechAudio
import ai.prism.domain.voice.VoiceError
import ai.prism.domain.voice.VoiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/** Микрофон на Windows через Java Sound: 16 кГц, 16 бит, моно. */
class JavaSoundAudioInput : AudioInput {
    override fun record(): Flow<FloatArray> = flow {
        val format = AudioFormat(SPEECH_SAMPLE_RATE.toFloat(), BITS, 1, true, false)
        val line = openLine { AudioSystem.getTargetDataLine(format).apply { open(format, CHUNK_BYTES * 4) } }
            ?: throw VoiceException(VoiceError.MICROPHONE, "Microphone is not available")
        try {
            line.start()
            val buffer = ByteArray(CHUNK_BYTES)
            while (currentCoroutineContext().isActive) {
                val read = line.read(buffer, 0, buffer.size)
                if (read <= 0) break
                emit(Pcm.toFloats(buffer, read))
            }
        } finally {
            line.stop()
            line.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun openLine(open: () -> TargetDataLine): TargetDataLine? = try {
        open()
    } catch (e: LineUnavailableException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private companion object {
        const val BITS = 16

        /** 0,1 с звука. */
        const val CHUNK_BYTES = SPEECH_SAMPLE_RATE / 10 * 2
    }
}

/** Динамики на Windows через Java Sound. */
class JavaSoundAudioOutput : AudioOutput {
    override suspend fun play(audio: SpeechAudio) = withContext(Dispatchers.IO) {
        val format = AudioFormat(audio.sampleRate.toFloat(), BITS, 1, true, false)
        val line: SourceDataLine = try {
            AudioSystem.getSourceDataLine(format).apply { open(format) }
        } catch (e: LineUnavailableException) {
            throw VoiceException(VoiceError.PLAYBACK, "Audio output is not available", e)
        } catch (e: IllegalArgumentException) {
            throw VoiceException(VoiceError.PLAYBACK, "Audio format is not supported", e)
        }
        try {
            line.start()
            val bytes = Pcm.toBytes(audio.samples)
            val chunk = audio.sampleRate / 10 * 2
            var offset = 0
            while (offset < bytes.size) {
                ensureActive()
                offset += line.write(bytes, offset, minOf(chunk, bytes.size - offset))
            }
            ensureActive()
            line.drain()
        } finally {
            line.stop()
            line.flush()
            line.close()
        }
    }

    private companion object {
        const val BITS = 16
    }
}
