package ai.prism.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Файл `three-frames.avi` записан тем же кодом, что и в `prism_imagegen` (3 кадра 64×32, 16 кадров в секунду). */
class MjpegAviTest {

    @Test
    fun framesAndRateAreRead() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/three-frames.avi")).readBytes()

        val video = checkNotNull(MjpegAvi.parse(bytes))

        assertEquals(3, video.frames.size)
        assertEquals(16, video.fps)
        assertTrue(video.frames.all { it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }, "каждый кадр — JPEG")
    }

    @Test
    fun otherFilesAreRejected() {
        assertNull(MjpegAvi.parse(ByteArray(10)))
        assertNull(MjpegAvi.parse("RIFF\\u0000\\u0000\\u0000\\u0000WAVEfmt ".encodeToByteArray()))
        // Обрезанный файл не роняет разбор.
        val bytes = checkNotNull(javaClass.getResourceAsStream("/three-frames.avi")).readBytes()
        val truncated = MjpegAvi.parse(bytes.copyOf(bytes.size / 2))
        assertTrue(truncated == null || truncated.frames.size < 3)
    }

    /** Склейка и ролики LTX: кадры и звук записываются и читаются обратно без потерь. */
    @Test
    fun framesAndAudioRoundTrip() {
        val frames = checkNotNull(MjpegAvi.parse(checkNotNull(javaClass.getResourceAsStream("/three-frames.avi")).readBytes())).frames
        // 0,25 с стерео 8 кГц: левый канал растёт, правый — убывает.
        val samples = 2000
        val pcm = ByteArray(samples * 4)
        for (i in 0 until samples) {
            val left = (i * 10).toShort().toInt()
            val right = (-i * 10).toShort().toInt()
            pcm[i * 4] = left.toByte()
            pcm[i * 4 + 1] = (left shr 8).toByte()
            pcm[i * 4 + 2] = right.toByte()
            pcm[i * 4 + 3] = (right shr 8).toByte()
        }
        val audio = PcmAudio(pcm, 8000, 2)

        val bytes = MjpegAvi.write(frames + frames + frames, 64, 32, 12, audio)
        val video = checkNotNull(MjpegAvi.parse(bytes))

        assertEquals(9, video.frames.size)
        assertEquals(12, video.fps)
        assertTrue(video.frames.indices.all { video.frames[it].contentEquals(frames[it % 3]) })
        val sound = checkNotNull(video.audio)
        assertEquals(8000 to 2, sound.sampleRate to sound.channels)
        assertTrue(sound.pcm16.contentEquals(pcm))
        // Моно — среднее каналов: здесь каналы противоположны, сумма — ноль.
        assertTrue(sound.monoSamples().all { it == 0f })
        assertEquals(1000, sound.from(0.125f).sampleCount)
    }

    @Test
    fun wavIsRead() {
        val data = byteArrayOf(0, 64, 0, -64)
        val wav = java.io.ByteArrayOutputStream().apply {
            fun u32(v: Int) = write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun u16(v: Int) = write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            write("RIFF".encodeToByteArray()); u32(36 + data.size); write("WAVE".encodeToByteArray())
            write("fmt ".encodeToByteArray()); u32(16); u16(1); u16(1); u32(24000); u32(48000); u16(2); u16(16)
            write("data".encodeToByteArray()); u32(data.size); write(data)
        }.toByteArray()

        val audio = checkNotNull(MjpegAvi.parseWav(wav))

        assertEquals(24000 to 1, audio.sampleRate to audio.channels)
        assertEquals(listOf(0.5f, -0.5f), audio.monoSamples().toList())
    }
}
