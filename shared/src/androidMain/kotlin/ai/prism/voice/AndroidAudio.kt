package ai.prism.voice

import ai.prism.domain.voice.AudioInput
import ai.prism.domain.voice.AudioOutput
import ai.prism.domain.voice.SPEECH_SAMPLE_RATE
import ai.prism.domain.voice.SpeechAudio
import ai.prism.domain.voice.VoiceError
import ai.prism.domain.voice.VoiceException
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Микрофон Android: источник «распознавание речи» (без обработки, которая мешает моделям), 16 кГц моно. */
class AndroidAudioInput(private val context: Context) : AudioInput {
    override fun record(): Flow<FloatArray> = flow {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw VoiceException(VoiceError.PERMISSION_DENIED, "RECORD_AUDIO is not granted")
        }
        val minBuffer = AudioRecord.getMinBufferSize(SPEECH_SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) throw VoiceException(VoiceError.MICROPHONE, "Recording at $SPEECH_SAMPLE_RATE Hz is not supported")
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SPEECH_SAMPLE_RATE, CHANNEL, ENCODING, maxOf(minBuffer, CHUNK * 4 * 2))
        } catch (e: SecurityException) {
            throw VoiceException(VoiceError.PERMISSION_DENIED, "RECORD_AUDIO is not granted", e)
        } catch (e: IllegalArgumentException) {
            throw VoiceException(VoiceError.MICROPHONE, "Microphone is not available", e)
        }
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) throw VoiceException(VoiceError.MICROPHONE, "Microphone is busy")
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw VoiceException(VoiceError.MICROPHONE, "Microphone is busy")
            }
            val buffer = ShortArray(CHUNK)
            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) throw VoiceException(VoiceError.MICROPHONE, "Recording failed: $read")
                if (read > 0) emit(Pcm.toFloats(buffer, read))
            }
        } finally {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 0,1 с звука. */
        const val CHUNK = SPEECH_SAMPLE_RATE / 10
    }
}

/** Озвучка на Android: поток «ассистент», формат float. */
class AndroidAudioOutput : AudioOutput {
    override suspend fun play(audio: SpeechAudio) = withContext(Dispatchers.IO) {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(audio.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuffer, audio.sampleRate / 5 * FLOAT_BYTES))
                .build()
        } catch (e: UnsupportedOperationException) {
            throw VoiceException(VoiceError.PLAYBACK, "Audio output is not available", e)
        } catch (e: IllegalArgumentException) {
            throw VoiceException(VoiceError.PLAYBACK, "Audio format is not supported", e)
        }
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) throw VoiceException(VoiceError.PLAYBACK, "Audio output is not available")
            track.play()
            val chunk = audio.sampleRate / 10
            var offset = 0
            while (offset < audio.samples.size) {
                ensureActive()
                val written = track.write(audio.samples, offset, minOf(chunk, audio.samples.size - offset), AudioTrack.WRITE_BLOCKING)
                if (written < 0) throw VoiceException(VoiceError.PLAYBACK, "Playback failed: $written")
                offset += written
            }
            // write() возвращается, когда звук в буфере; ждём, пока он доиграет (но не дольше длины звука с запасом).
            val deadline = System.nanoTime() + ((audio.durationSeconds + DRAIN_MARGIN_S) * NANOS_IN_SECOND).toLong()
            while (track.playbackHeadPosition < audio.samples.size && System.nanoTime() < deadline) {
                ensureActive()
                delay(POLL_MS)
            }
        } finally {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                track.pause()
                track.flush()
                track.stop()
            }
            track.release()
        }
    }

    private companion object {
        const val FLOAT_BYTES = 4
        const val POLL_MS = 20L
        const val DRAIN_MARGIN_S = 2f
        const val NANOS_IN_SECOND = 1_000_000_000f
    }
}
