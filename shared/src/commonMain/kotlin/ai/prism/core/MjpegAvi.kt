package ai.prism.core

/** Звук: 16-битный PCM (little-endian, каналы чередуются), частота и число каналов. */
class PcmAudio(val pcm16: ByteArray, val sampleRate: Int, val channels: Int) {
    /** Байт на одну выборку всех каналов. */
    val blockAlign: Int get() = channels * 2

    val sampleCount: Int get() = pcm16.size / blockAlign

    /** Звук начиная с [seconds] секунды (для продолжения после паузы). */
    fun from(seconds: Float): PcmAudio {
        val start = (seconds * sampleRate).toInt().coerceIn(0, sampleCount) * blockAlign
        return if (start == 0) this else PcmAudio(pcm16.copyOfRange(start, pcm16.size), sampleRate, channels)
    }

    /** Отсчёты моно в [-1, 1] (каналы усредняются) — для проигрывания. */
    fun monoSamples(): FloatArray = FloatArray(sampleCount) { i ->
        var sum = 0
        for (channel in 0 until channels) {
            val offset = (i * channels + channel) * 2
            sum += ((pcm16[offset + 1].toInt() shl 8) or (pcm16[offset].toInt() and BYTE_MASK)).toShort().toInt()
        }
        sum.toFloat() / channels / SHORT_SCALE
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val SHORT_SCALE = 32768f
    }
}

/** Кадры видео MJPEG AVI: JPEG каждого кадра, частота кадров и звук (если есть). */
class MjpegVideo(val frames: List<ByteArray>, val fps: Int, val audio: PcmAudio? = null) {
    val durationSeconds: Float get() = frames.size.toFloat() / fps
}

/**
 * MJPEG AVI, который пишут `prism_imagegen` и склейка роликов: частота — из заголовка `avih`, кадры — чанки `00dc`,
 * звук (PCM 16 бит) — поток `auds` и чанки `01wb` в списке `movi`. Другие AVI (сжатые не MJPEG) не поддерживаются.
 */
object MjpegAvi {
    fun parse(bytes: ByteArray): MjpegVideo? {
        if (bytes.size < HEADER_SIZE || fourcc(bytes, 0) != "RIFF" || fourcc(bytes, 8) != "AVI ") return null
        var fps = DEFAULT_FPS
        val frames = mutableListOf<ByteArray>()
        var streamType = ""
        var audioRate = 0
        var audioChannels = 0
        var audioBits = 0
        val audio = ByteArrayBuilder()
        walk(bytes, 12, bytes.size) { id, start, size ->
            when (id) {
                "avih" -> if (size >= 4) {
                    val microseconds = u32(bytes, start)
                    if (microseconds > 0) fps = (1_000_000L / microseconds).toInt().coerceIn(1, MAX_FPS)
                }
                "strh" -> if (size >= 4) streamType = fourcc(bytes, start)
                "strf" -> if (streamType == "auds" && size >= WAVE_FORMAT_SIZE) {
                    val format = u16(bytes, start)
                    if (format == WAVE_FORMAT_PCM) {
                        audioChannels = u16(bytes, start + 2)
                        audioRate = u32(bytes, start + 4).toInt()
                        audioBits = u16(bytes, start + 14)
                    }
                }
                "00dc", "00db" -> if (size > 0) frames += bytes.copyOfRange(start, start + size)
                "01wb" -> if (size > 0) audio.append(bytes, start, size)
            }
        }
        if (frames.isEmpty()) return null
        val sound = if (audio.size > 0 && audioRate > 0 && audioChannels > 0 && audioBits == PCM_BITS) {
            PcmAudio(audio.toByteArray(), audioRate, audioChannels)
        } else {
            null
        }
        return MjpegVideo(frames, fps, sound)
    }

    /**
     * Пишет MJPEG AVI: кадры [frames] (JPEG одного размера [width]×[height]) с частотой [fps] и звук [audio].
     * Звук идёт кусками по секунде перед кадрами этой секунды — так его играют обычные плееры.
     */
    fun write(frames: List<ByteArray>, width: Int, height: Int, fps: Int, audio: PcmAudio? = null): ByteArray {
        require(frames.isNotEmpty() && fps > 0) { "no frames" }
        val out = ByteArrayBuilder()
        out.fourcc("RIFF")
        val riffSize = out.reserve()
        out.fourcc("AVI ")

        out.fourcc("LIST")
        val hdrlSize = out.reserve()
        val hdrlStart = out.size
        out.fourcc("hdrl")
        out.fourcc("avih")
        out.u32(AVIH_SIZE)
        out.u32(1_000_000 / fps)
        out.u32(0)
        out.u32(0)
        out.u32(AVIF_HASINDEX)
        out.u32(frames.size)
        out.u32(0)
        out.u32(if (audio != null) 2 else 1)
        out.u32(width * height * 3)
        out.u32(width)
        out.u32(height)
        repeat(4) { out.u32(0) }

        // Поток видео.
        out.fourcc("LIST")
        val videoListSize = out.reserve()
        val videoListStart = out.size
        out.fourcc("strl")
        out.fourcc("strh")
        out.u32(STRH_SIZE)
        out.fourcc("vids")
        out.fourcc("MJPG")
        out.u32(0)
        out.u16(0)
        out.u16(0)
        out.u32(0)
        out.u32(1)
        out.u32(fps)
        out.u32(0)
        out.u32(frames.size)
        out.u32(width * height * 3)
        out.u32(-1)
        out.u32(0)
        out.u16(0)
        out.u16(0)
        out.u16(width)
        out.u16(height)
        out.fourcc("strf")
        out.u32(BITMAPINFO_SIZE)
        out.u32(BITMAPINFO_SIZE)
        out.u32(width)
        out.u32(height)
        out.u16(1)
        out.u16(BITS_PER_PIXEL)
        out.fourcc("MJPG")
        out.u32(width * height * 3)
        repeat(4) { out.u32(0) }
        out.set(videoListSize, out.size - videoListStart)

        // Поток звука.
        if (audio != null) {
            out.fourcc("LIST")
            val audioListSize = out.reserve()
            val audioListStart = out.size
            out.fourcc("strl")
            out.fourcc("strh")
            out.u32(STRH_SIZE)
            out.fourcc("auds")
            out.u32(0)
            out.u32(0)
            out.u16(0)
            out.u16(0)
            out.u32(0)
            out.u32(audio.blockAlign)
            out.u32(audio.sampleRate * audio.blockAlign)
            out.u32(0)
            out.u32(audio.sampleCount)
            out.u32(audio.sampleRate * audio.blockAlign)
            out.u32(-1)
            out.u32(audio.blockAlign)
            repeat(4) { out.u16(0) }
            out.fourcc("strf")
            out.u32(WAVE_FORMAT_SIZE)
            out.u16(WAVE_FORMAT_PCM)
            out.u16(audio.channels)
            out.u32(audio.sampleRate)
            out.u32(audio.sampleRate * audio.blockAlign)
            out.u16(audio.blockAlign)
            out.u16(PCM_BITS)
            out.set(audioListSize, out.size - audioListStart)
        }
        out.set(hdrlSize, out.size - hdrlStart)

        out.fourcc("LIST")
        val moviSize = out.reserve()
        val moviStart = out.size
        out.fourcc("movi")
        val index = mutableListOf<IndexEntry>()
        fun chunk(id: String, data: ByteArray, from: Int, length: Int, flags: Int) {
            index += IndexEntry(id, flags, out.size - moviStart, length)
            out.fourcc(id)
            out.u32(length)
            out.append(data, from, length)
            if (length % 2 == 1) out.append(byteArrayOf(0), 0, 1)
        }
        val audioPerSecond = audio?.let { it.sampleRate * it.blockAlign } ?: 0
        frames.forEachIndexed { i, jpeg ->
            if (audio != null && i % fps == 0) {
                val from = (i / fps) * audioPerSecond
                val to = minOf(audio.pcm16.size, from + audioPerSecond)
                if (from < to) chunk("01wb", audio.pcm16, from, to - from, 0)
            }
            chunk("00dc", jpeg, 0, jpeg.size, AVIIF_KEYFRAME)
        }
        // Звук, который длиннее видео, дописывается в конце.
        if (audio != null) {
            var from = ((frames.size + fps - 1) / fps) * audioPerSecond
            while (from < audio.pcm16.size) {
                val to = minOf(audio.pcm16.size, from + audioPerSecond)
                chunk("01wb", audio.pcm16, from, to - from, 0)
                from = to
            }
        }
        out.set(moviSize, out.size - moviStart)

        out.fourcc("idx1")
        out.u32(index.size * INDEX_ENTRY_SIZE)
        index.forEach { entry ->
            out.fourcc(entry.id)
            out.u32(entry.flags)
            out.u32(entry.offset)
            out.u32(entry.size)
        }
        out.set(riffSize, out.size - 8)
        return out.toByteArray()
    }

    /** Звук из WAV (PCM 16 бит), который пишет `prism_imagegen` для моделей со звуком. */
    fun parseWav(bytes: ByteArray): PcmAudio? {
        if (bytes.size < HEADER_SIZE || fourcc(bytes, 0) != "RIFF" || fourcc(bytes, 8) != "WAVE") return null
        var rate = 0
        var channels = 0
        var bits = 0
        var data: ByteArray? = null
        walk(bytes, 12, bytes.size) { id, start, size ->
            when (id) {
                "fmt " -> if (size >= WAVE_FORMAT_PCM_SIZE && u16(bytes, start) == WAVE_FORMAT_PCM) {
                    channels = u16(bytes, start + 2)
                    rate = u32(bytes, start + 4).toInt()
                    bits = u16(bytes, start + 14)
                }
                "data" -> data = bytes.copyOfRange(start, start + size)
            }
        }
        val pcm = data ?: return null
        return if (rate > 0 && channels > 0 && bits == PCM_BITS) PcmAudio(pcm, rate, channels) else null
    }

    private class IndexEntry(val id: String, val flags: Int, val offset: Int, val size: Int)

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

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and BYTE_MASK) or ((bytes[offset + 1].toInt() and BYTE_MASK) shl 8)

    private const val HEADER_SIZE = 12
    private const val CHUNK_HEADER = 8
    private const val DEFAULT_FPS = 16
    private const val MAX_FPS = 60
    private const val BYTE_MASK = 0xFF
    private const val AVIH_SIZE = 56
    private const val STRH_SIZE = 56
    private const val BITMAPINFO_SIZE = 40
    private const val BITS_PER_PIXEL = 24
    private const val AVIF_HASINDEX = 0x10
    private const val AVIIF_KEYFRAME = 0x10
    private const val INDEX_ENTRY_SIZE = 16
    private const val WAVE_FORMAT_PCM = 1
    private const val WAVE_FORMAT_SIZE = 16
    private const val WAVE_FORMAT_PCM_SIZE = 16
    private const val PCM_BITS = 16
}

/** Растущий массив байтов с записью чисел little-endian. */
private class ByteArrayBuilder {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        var capacity = buffer.size
        while (capacity < size + extra) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    fun append(bytes: ByteArray, from: Int, length: Int) {
        ensure(length)
        bytes.copyInto(buffer, size, from, from + length)
        size += length
    }

    fun u32(value: Int) {
        ensure(4)
        for (i in 0 until 4) buffer[size + i] = (value ushr (8 * i)).toByte()
        size += 4
    }

    fun u16(value: Int) {
        ensure(2)
        buffer[size] = value.toByte()
        buffer[size + 1] = (value ushr 8).toByte()
        size += 2
    }

    fun fourcc(code: String) {
        ensure(4)
        for (i in 0 until 4) buffer[size + i] = code[i].code.toByte()
        size += 4
    }

    /** Место под число, которое станет известно позже ([set]). */
    fun reserve(): Int {
        val position = size
        u32(0)
        return position
    }

    fun set(position: Int, value: Int) {
        for (i in 0 until 4) buffer[position + i] = (value ushr (8 * i)).toByte()
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private companion object {
        const val INITIAL_CAPACITY = 1 shl 16
    }
}
