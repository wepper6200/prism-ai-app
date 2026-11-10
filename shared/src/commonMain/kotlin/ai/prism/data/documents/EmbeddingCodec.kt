package ai.prism.data.documents

/** Эмбеддинг в базе — числа float32 подряд, младший байт первым. */
internal object EmbeddingCodec {
    fun encode(vector: FloatArray): ByteArray {
        val bytes = ByteArray(vector.size * Float.SIZE_BYTES)
        vector.forEachIndexed { index, value ->
            val bits = value.toRawBits()
            val offset = index * Float.SIZE_BYTES
            for (shift in 0 until Float.SIZE_BYTES) {
                bytes[offset + shift] = (bits ushr (shift * Byte.SIZE_BITS)).toByte()
            }
        }
        return bytes
    }

    fun decode(bytes: ByteArray): FloatArray = FloatArray(bytes.size / Float.SIZE_BYTES) { index ->
        val offset = index * Float.SIZE_BYTES
        var bits = 0
        for (shift in 0 until Float.SIZE_BYTES) {
            bits = bits or ((bytes[offset + shift].toInt() and BYTE_MASK) shl (shift * Byte.SIZE_BITS))
        }
        Float.fromBits(bits)
    }

    private const val BYTE_MASK = 0xFF
}
