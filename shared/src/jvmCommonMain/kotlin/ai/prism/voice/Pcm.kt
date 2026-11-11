package ai.prism.voice

import kotlin.math.roundToInt

/** Преобразования звука между 16-битным PCM (little-endian) и отсчётами float в [-1, 1]. */
internal object Pcm {
    fun toFloats(bytes: ByteArray, length: Int): FloatArray {
        val count = length / 2
        return FloatArray(count) { i ->
            val low = bytes[2 * i].toInt() and 0xFF
            val high = bytes[2 * i + 1].toInt()
            ((high shl 8) or low).toShort() / SHORT_SCALE
        }
    }

    fun toFloats(samples: ShortArray, length: Int): FloatArray = FloatArray(length) { samples[it] / SHORT_SCALE }

    fun toBytes(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, sample ->
            val value = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
            bytes[2 * i] = value.toByte()
            bytes[2 * i + 1] = (value shr 8).toByte()
        }
        return bytes
    }

    private const val SHORT_SCALE = 32768f
}
