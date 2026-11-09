package ai.prism.data.images

import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/** ImageIO: JPEG, PNG, BMP, GIF. Поворот по EXIF выполняется вручную — ImageIO его не учитывает. */
class DesktopImageCodec : ImageCodec {

    override fun toJpeg(bytes: ByteArray, maxSide: Int): EncodedImage? {
        val source = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (e: IOException) {
            null
        } ?: return null
        val oriented = orient(source, ExifOrientation.read(bytes))
        val scaled = downscale(oriented, maxSide)
        val jpeg = encodeJpeg(scaled) ?: return null
        return EncodedImage(jpeg, scaled.width, scaled.height)
    }

    override fun decodeRgb(file: File): RgbImage? {
        val image = try {
            ImageIO.read(file)
        } catch (e: IOException) {
            null
        } ?: return null
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return RgbImage(image.width, image.height, argbToRgb(pixels))
    }

    /** Применяет ориентацию EXIF (1–8) и рисует результат на белом фоне. */
    internal fun orient(source: BufferedImage, orientation: Int): BufferedImage {
        val w = source.width.toDouble()
        val h = source.height.toDouble()
        val transform = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)
            8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)
            else -> AffineTransform()
        }
        val swap = orientation in 5..8
        val result = BufferedImage(
            if (swap) source.height else source.width,
            if (swap) source.width else source.height,
            BufferedImage.TYPE_INT_RGB,
        )
        val g = result.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, result.width, result.height)
            g.drawImage(source, transform, null)
        } finally {
            g.dispose()
        }
        return result
    }

    /** Уменьшает по шагам вдвое, затем бикубически до точного размера — без «лесенок» на больших фото. */
    private fun downscale(image: BufferedImage, maxSide: Int): BufferedImage {
        val largest = max(image.width, image.height)
        if (largest <= maxSide) return image
        val scale = maxSide.toDouble() / largest
        val targetW = (image.width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (image.height * scale).roundToInt().coerceAtLeast(1)
        var current = image
        while (current.width / 2 >= targetW && current.height / 2 >= targetH) {
            current = resize(current, current.width / 2, current.height / 2, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        }
        return resize(current, targetW, targetH, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    }

    private fun resize(image: BufferedImage, width: Int, height: Int, interpolation: Any): BufferedImage {
        val result = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = result.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(image, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return result
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray? {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
        val out = ByteArrayOutputStream()
        return try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = JPEG_QUALITY
                }
                writer.write(null, IIOImage(image, null, null), params)
            }
            out.toByteArray()
        } catch (e: IOException) {
            null
        } finally {
            writer.dispose()
        }
    }

    private companion object {
        const val JPEG_QUALITY = 0.9f
    }
}
