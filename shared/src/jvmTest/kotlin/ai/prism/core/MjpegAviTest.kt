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
}
