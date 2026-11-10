package ai.prism.domain.documents

import ai.prism.domain.model.CatalogModel

/**
 * Модель эмбеддингов: переводит текст в вектор, близкий для текстов с похожим смыслом.
 * Модель загружается при первом вызове и остаётся в памяти до [release].
 */
interface TextEmbedder {
    /**
     * Эмбеддинги [texts] по порядку (векторы единичной длины). [onProgress] получает число готовых текстов.
     * @throws ai.prism.domain.inference.InferenceException модель не загрузилась или не хватило памяти.
     */
    suspend fun embed(
        model: CatalogModel,
        filePath: String,
        texts: List<String>,
        onProgress: (Int) -> Unit = {},
    ): List<FloatArray>

    /** Освобождает память модели. */
    suspend fun release()
}
