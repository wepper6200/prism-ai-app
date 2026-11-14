package ai.prism.domain.music

import ai.prism.domain.imagegen.ImageGenerationError
import ai.prism.domain.imagegen.ImageGenerationException
import ai.prism.domain.imagegen.ImageGenerationProgress

data class MusicRequest(val prompt: String, val seconds: Int = 30)

/** Локальный музыкальный движок в отдельном процессе. Результат — стерео WAV PCM16, 48 кГц. */
interface MusicGenerator {
    val isSupported: Boolean

    suspend fun generate(
        files: Map<String, String>,
        request: MusicRequest,
        onProgress: (ImageGenerationProgress) -> Unit,
    ): ByteArray

    fun cancel()

    /** Рабочий процесс завершается после каждого трека. */
    suspend fun release()

    object None : MusicGenerator {
        override val isSupported = false
        override suspend fun generate(
            files: Map<String, String>,
            request: MusicRequest,
            onProgress: (ImageGenerationProgress) -> Unit,
        ): ByteArray = throw ImageGenerationException(ImageGenerationError.NOT_SUPPORTED, "Music worker unavailable")
        override fun cancel() = Unit
        override suspend fun release() = Unit
    }
}
