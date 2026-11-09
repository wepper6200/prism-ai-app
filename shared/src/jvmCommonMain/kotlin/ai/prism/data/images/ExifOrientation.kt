package ai.prism.data.images

/** Читает тег ориентации (0x0112) из EXIF внутри JPEG. 1 — без поворота. */
object ExifOrientation {
    private const val TAG_ORIENTATION = 0x0112

    fun read(bytes: ByteArray): Int = try {
        find(bytes) ?: 1
    } catch (e: IndexOutOfBoundsException) {
        1
    }

    private fun find(b: ByteArray): Int? {
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
        var i = 2
        while (i + 4 <= b.size) {
            if (u8(b, i) != 0xFF) return null
            val marker = u8(b, i + 1)
            if (marker == 0xD9 || marker == 0xDA) return null
            val length = u16(b, i + 2, bigEndian = true)
            if (length < 2) return null
            if (marker == 0xE1 && isExifHeader(b, i + 4)) return orientationInTiff(b, i + 10)
            i += 2 + length
        }
        return null
    }

    private fun isExifHeader(b: ByteArray, at: Int): Boolean =
        at + 6 <= b.size && b[at] == 'E'.code.toByte() && b[at + 1] == 'x'.code.toByte() &&
            b[at + 2] == 'i'.code.toByte() && b[at + 3] == 'f'.code.toByte() && b[at + 4].toInt() == 0 && b[at + 5].toInt() == 0

    private fun orientationInTiff(b: ByteArray, tiff: Int): Int? {
        val bigEndian = when {
            u8(b, tiff) == 'M'.code && u8(b, tiff + 1) == 'M'.code -> true
            u8(b, tiff) == 'I'.code && u8(b, tiff + 1) == 'I'.code -> false
            else -> return null
        }
        val ifd = tiff + u32(b, tiff + 4, bigEndian)
        val count = u16(b, ifd, bigEndian)
        for (k in 0 until count) {
            val entry = ifd + 2 + k * 12
            if (u16(b, entry, bigEndian) == TAG_ORIENTATION) {
                return u16(b, entry + 8, bigEndian).takeIf { it in 1..8 }
            }
        }
        return null
    }

    private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF

    private fun u16(b: ByteArray, at: Int, bigEndian: Boolean): Int =
        if (bigEndian) (u8(b, at) shl 8) or u8(b, at + 1) else (u8(b, at + 1) shl 8) or u8(b, at)

    private fun u32(b: ByteArray, at: Int, bigEndian: Boolean): Int =
        if (bigEndian) {
            (u16(b, at, true) shl 16) or u16(b, at + 2, true)
        } else {
            (u16(b, at + 2, false) shl 16) or u16(b, at, false)
        }
}
