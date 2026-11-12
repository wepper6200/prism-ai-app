package ai.prism.domain.model

/**
 * Какой текстовый энкодер (роль `llm`, Gemma у LTX) использовать для видео: полный файл из каталога
 * или сжатую копию. Сжатая копия создаётся один раз из полного файла и лежит рядом с ним.
 *
 * @param weightType тип весов stable-diffusion.cpp для сжатия (`null` — полный файл).
 */
enum class TextEncoderMode(val weightType: String?) {
    /** Полный файл (bf16): лучшее понимание описания, но нужно много оперативной памяти. */
    FULL(null),

    /** Q8: почти без потерь, примерно вдвое меньше. */
    Q8("q8_0"),

    /** Q4: примерно вчетверо меньше; так энкодер Gemma для LTX предлагает и stable-diffusion.cpp. */
    Q4("q4_k"),
    ;

    companion object {
        private const val GB = 1024L * 1024 * 1024

        /** Режим по умолчанию по объёму оперативной памяти (0 — неизвестен: полный файл). */
        fun auto(totalRamBytes: Long): TextEncoderMode = when {
            totalRamBytes <= 0L || totalRamBytes >= 60 * GB -> FULL
            totalRamBytes >= 44 * GB -> Q8
            else -> Q4
        }

        /** Путь к сжатой копии файла [path] или сам [path] для полного режима. */
        fun pathFor(path: String, mode: TextEncoderMode): String =
            mode.weightType?.let { "$path.$it.gguf" } ?: path

        /** Файл энкодера можно сжать: сжимаются только несжатые веса (safetensors). */
        fun canCompress(fileName: String): Boolean = fileName.endsWith(".safetensors", ignoreCase = true)

        /** Роль текстового энкодера в файлах модели. */
        const val ROLE = "llm"
    }
}
