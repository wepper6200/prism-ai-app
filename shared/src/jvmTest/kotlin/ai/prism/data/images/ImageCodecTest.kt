package ai.prism.data.images

import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageCodecTest {

    private val codec = DesktopImageCodec()

    private fun image(width: Int, height: Int, fill: (Int, Int) -> Color): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until height) for (x in 0 until width) setRGB(x, y, fill(x, y).rgb)
        }

    private fun BufferedImage.encode(format: String): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(this, format, it) }.toByteArray()

    /** Минимальный JPEG-заголовок с сегментом EXIF и заданной ориентацией. */
    private fun exifJpeg(orientation: Int, bigEndian: Boolean): ByteArray {
        fun u16(v: Int) = if (bigEndian) byteArrayOf((v shr 8).toByte(), v.toByte()) else byteArrayOf(v.toByte(), (v shr 8).toByte())
        fun u32(v: Int) = if (bigEndian) u16(v shr 16) + u16(v and 0xFFFF) else u16(v and 0xFFFF) + u16(v shr 16)
        val tiff = (if (bigEndian) "MM".toByteArray() else "II".toByteArray()) + u16(42) + u32(8) +
            u16(1) + u16(0x0112) + u16(3) + u32(1) + u16(orientation) + u16(0) + u32(0)
        val app1 = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = app1.size + 2
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) +
            app1 + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    @Test
    fun exifOrientationIsReadInBothByteOrders() {
        assertEquals(6, ExifOrientation.read(exifJpeg(6, bigEndian = true)))
        assertEquals(8, ExifOrientation.read(exifJpeg(8, bigEndian = false)))
        assertEquals(1, ExifOrientation.read(byteArrayOf(1, 2, 3)))
        assertEquals(1, ExifOrientation.read(exifJpeg(6, bigEndian = true).copyOf(20)))
    }

    @Test
    fun orientationSixRotatesClockwise() {
        // Красный пиксель в левом верхнем углу после поворота на 90° по часовой оказывается в правом верхнем.
        val source = image(4, 2) { x, y -> if (x == 0 && y == 0) Color.RED else Color.BLUE }
        val rotated = codec.orient(source, 6)
        assertEquals(2, rotated.width)
        assertEquals(4, rotated.height)
        assertEquals(Color.RED.rgb, rotated.getRGB(1, 0))
    }

    @Test
    fun largePngIsShrunkToJpegOnWhite() {
        val transparent = image(3000, 1500) { _, _ -> Color(0, 0, 0, 0) }
        val encoded = assertNotNull(codec.toJpeg(transparent.encode("png"), maxSide = 1024))
        assertEquals(1024, encoded.width)
        assertEquals(512, encoded.height)
        val decoded = ImageIO.read(encoded.jpeg.inputStream())
        assertTrue(Color(decoded.getRGB(10, 10)).red > 240, "прозрачность должна стать белой")
    }

    @Test
    fun garbageIsRejected() {
        assertNull(codec.toJpeg(ByteArray(100) { 7 }, maxSide = 1024))
    }

    @Test
    fun storeSavesReadsAndDecodesRgb() = runBlocking {
        val directory = Files.createTempDirectory("prism-images").toFile()
        val store = FileImageStore(directory, codec)
        val red = image(8, 4) { _, _ -> Color.RED }

        val saved = store.save(red.encode("png"))

        assertEquals(8 to 4, saved.width to saved.height)
        assertTrue(File(store.path(saved.fileName)).isFile)
        assertNotNull(store.read(saved.fileName))
        val rgb = assertNotNull(store.decodeRgb(saved.fileName))
        assertEquals(8 * 4 * 3, rgb.rgb.size)
        assertTrue((rgb.rgb[0].toInt() and 0xFF) > 200 && (rgb.rgb[2].toInt() and 0xFF) < 60, "ожидался красный")

        assertFailsWith<ai.prism.domain.repository.ImageException> { store.save(byteArrayOf(1, 2, 3)) }

        store.deleteOrphans(referenced = setOf(saved.fileName), olderThanMillis = -1)
        assertTrue(File(store.path(saved.fileName)).isFile)
        store.deleteOrphans(referenced = emptySet(), olderThanMillis = -1)
        assertNull(store.read(saved.fileName))
    }

    @Test
    fun argbIsConvertedToRgb() {
        assertContentEquals(byteArrayOf(0x11, 0x22, 0x33), argbToRgb(intArrayOf(0x7F112233)))
    }
}
