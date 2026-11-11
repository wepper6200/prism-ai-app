package ai.prism.service.video

import ai.prism.core.MjpegAvi
import ai.prism.core.PcmAudio
import ai.prism.domain.imagegen.GeneratedVideo

/**
 * Склейка ролика из кусков: каждый следующий кусок начинается с последнего кадра предыдущего,
 * поэтому его первый кадр (и звук этого кадра) пропускается. Звук остаётся, только если он есть у всех кусков.
 */
class VideoClipBuilder {
    private var single: GeneratedVideo? = null
    private val frames = mutableListOf<ByteArray>()
    private var width = 0
    private var height = 0
    private var fps = 0
    private var audio: MutableList<ByteArray>? = null
    private var audioRate = 0
    private var audioChannels = 0
    private var segments = 0

    val isEmpty: Boolean get() = single == null && frames.isEmpty()

    /** Ролик из одного куска — без перепаковки. */
    fun single(video: GeneratedVideo) {
        single = video
    }

    /** Добавляет кусок и возвращает JPEG его последнего кадра (`null` — кусок не читается). */
    fun append(video: GeneratedVideo): ByteArray? {
        val parsed = MjpegAvi.parse(video.avi) ?: return null
        val first = frames.isEmpty()
        if (first) {
            width = video.width
            height = video.height
            fps = parsed.fps
        }
        frames += if (first) parsed.frames else parsed.frames.drop(1)
        appendAudio(parsed.audio, skipFrame = !first)
        segments++
        return parsed.frames.lastOrNull()
    }

    fun build(): GeneratedVideo? {
        single?.let { return it }
        if (frames.isEmpty()) return null
        val sound = audio?.let { chunks ->
            val bytes = ByteArray(chunks.sumOf { it.size })
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(bytes, offset)
                offset += chunk.size
            }
            PcmAudio(bytes, audioRate, audioChannels)
        }
        return GeneratedVideo(
            avi = MjpegAvi.write(frames, width, height, fps, sound),
            width = width,
            height = height,
            frames = frames.size,
            fps = fps,
            seed = -1,
        )
    }

    private fun appendAudio(sound: PcmAudio?, skipFrame: Boolean) {
        val first = segments == 0
        if (sound == null || (!first && (audio == null || sound.sampleRate != audioRate || sound.channels != audioChannels))) {
            audio = null
            return
        }
        if (first) {
            audio = mutableListOf()
            audioRate = sound.sampleRate
            audioChannels = sound.channels
        }
        val skip = if (skipFrame) (sound.sampleRate / fps.coerceAtLeast(1)) * sound.blockAlign else 0
        audio?.add(sound.pcm16.copyOfRange(skip.coerceAtMost(sound.pcm16.size), sound.pcm16.size))
    }
}
