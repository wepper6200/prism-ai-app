package ai.prism.core

/** Кадры видео MJPEG AVI: JPEG каждого кадра и частота кадров. */
class MjpegVideo(val frames: List<ByteArray>, val fps: Int)

/**
 * Разбор MJPEG AVI, который пишет `prism_imagegen`: частота — из заголовка `avih`, кадры — чанки `00dc`
 * в списке `movi`. Другие AVI (сжатые не MJPEG) не поддерживаются.
 */
object MjpegAvi {
    fun parse(bytes: ByteArray): MjpegVideo? {
        if (bytes.size < HEADER_SIZE || fourcc(bytes, 0) != "RIFF" || fourcc(bytes, 8) != "AVI ") return null
        var fps = DEFAULT_FPS
        val frames = mutableListOf<ByteArray>()
        walk(bytes, 12, bytes.size) { id, start, size ->
            when (id) {
                "avih" -> if (size >= 4) {
                    val microseconds = u32(bytes, start)
                    if (microseconds > 0) fps = (1_000_000L / microseconds).toInt().coerceIn(1, MAX_FPS)
                }
                "00dc", "00db" -> if (size > 0) frames += bytes.copyOfRange(start, start + size)
            }
        }
        return if (frames.isEmpty()) null else MjpegVideo(frames, fps)
    }

    /** Обходит чанки в [from, to); списки `LIST` (hdrl, strl, movi) — рекурсивно. */
    private fun walk(bytes: ByteArray, from: Int, to: Int, onChunk: (String, Int, Int) -> Unit) {
        var pos = from
        while (pos + CHUNK_HEADER <= to) {
            val id = fourcc(bytes, pos)
            val start = pos + CHUNK_HEADER
            val declared = u32(bytes, pos + 4)
            if (start + declared > to) return
            val size = declared.toInt()
            if (id == "LIST" || id == "RIFF") {
                if (size >= 4) walk(bytes, start + 4, start + size, onChunk)
            } else {
                onChunk(id, start, size)
            }
            pos = start + size + (size and 1)
        }
    }

    private fun fourcc(bytes: ByteArray, offset: Int): String =
        CharArray(4) { (bytes[offset + it].toInt() and BYTE_MASK).toChar() }.concatToString()

    private fun u32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    private const val HEADER_SIZE = 12
    private const val CHUNK_HEADER = 8
    private const val DEFAULT_FPS = 16
    private const val MAX_FPS = 60
    private const val BYTE_MASK = 0xFF
}
