package ai.prism.inference.llama

/** Загружает библиотеку `prism_llama` и её зависимости. */
fun interface NativeLibraryLoader {
    /**
     * Загружает библиотеки и возвращает каталог, где лежат бэкенды ggml.
     * Бросает [ai.prism.domain.inference.InferenceException] с NATIVE_LIBRARY_MISSING, если их нет.
     */
    fun load(): String
}
