package ai.prism.data.images

import java.io.File

/** Платформенное декодирование изображений (Android — ImageDecoder, Desktop — ImageIO). */
interface ImageCodec {
    /**
     * Декодирует файл изображения, поворачивает по EXIF, уменьшает до [maxSide] пикселей по большей стороне
     * (прозрачность — на белом фоне) и кодирует в JPEG. `null` — формат не поддерживается или файл повреждён.
     */
    fun toJpeg(bytes: ByteArray, maxSide: Int): EncodedImage?

    /** Пиксели сохранённого изображения в формате RGB (3 байта на пиксель) или `null`. */
    fun decodeRgb(file: File): RgbImage?
}

class EncodedImage(val jpeg: ByteArray, val width: Int, val height: Int)

class RgbImage(val width: Int, val height: Int, val rgb: ByteArray)

/** Переводит пиксели ARGB (как в Bitmap.getPixels / BufferedImage.getRGB) в RGB-байты. */
fun argbToRgb(argb: IntArray): ByteArray {
    val out = ByteArray(argb.size * 3)
    for (i in argb.indices) {
        val pixel = argb[i]
        out[i * 3] = (pixel shr 16 and 0xFF).toByte()
        out[i * 3 + 1] = (pixel shr 8 and 0xFF).toByte()
        out[i * 3 + 2] = (pixel and 0xFF).toByte()
    }
    return out
}
