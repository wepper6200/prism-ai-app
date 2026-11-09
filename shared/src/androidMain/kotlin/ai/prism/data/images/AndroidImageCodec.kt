package ai.prism.data.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * ImageDecoder понимает JPEG, PNG, WebP, HEIC/HEIF и GIF и сам поворачивает фото по EXIF.
 * Изображение уменьшается уже при декодировании — большие фото не занимают лишнюю память.
 */
class AndroidImageCodec : ImageCodec {

    override fun toJpeg(bytes: ByteArray, maxSide: Int): EncodedImage? {
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val largest = max(width, height)
                if (largest > maxSide) {
                    val scale = maxSide.toFloat() / largest
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        val opaque = onWhite(decoded)
        return try {
            val out = ByteArrayOutputStream()
            if (!opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
            EncodedImage(out.toByteArray(), opaque.width, opaque.height)
        } finally {
            if (opaque !== decoded) opaque.recycle()
            decoded.recycle()
        }
    }

    override fun decodeRgb(file: File): RgbImage? {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            RgbImage(bitmap.width, bitmap.height, argbToRgb(pixels))
        } finally {
            bitmap.recycle()
        }
    }

    /** Прозрачные области (PNG, WebP) кладутся на белый фон — JPEG не хранит прозрачность. */
    private fun onWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha() && bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return result
    }

    private companion object {
        const val JPEG_QUALITY = 90
    }
}
