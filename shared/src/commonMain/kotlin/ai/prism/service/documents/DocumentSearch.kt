package ai.prism.service.documents

import ai.prism.domain.documents.DocumentChunk

/** Выбор фрагментов файлов для ответа: сходство по эмбеддингам и бюджет символов промпта. */
object DocumentSearch {
    /** Скалярное произведение векторов единичной длины — косинусное сходство. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0 until minOf(a.size, b.size)) sum += a[i] * b[i]
        return sum
    }

    /** Файлы целиком помещаются в [maxChars] — тогда искать не нужно, модель получает весь текст. */
    fun fitsWhole(chunks: List<DocumentChunk>, maxChars: Int): Boolean =
        chunks.sumOf { it.text.length } <= maxChars

    /**
     * Самые похожие на запрос фрагменты (не больше [maxChunks]), пока их текст помещается в [maxChars].
     * Результат — в порядке файлов и позиций, чтобы модель читала текст связно.
     */
    fun select(chunks: List<DocumentChunk>, query: FloatArray, maxChars: Int, maxChunks: Int = MAX_CHUNKS): List<DocumentChunk> {
        val order = chunks.withIndex().associate { (index, chunk) -> chunk.key() to index }
        val chosen = mutableListOf<DocumentChunk>()
        var used = 0
        for (chunk in chunks.sortedByDescending { similarity(it.embedding, query) }) {
            if (chosen.size >= maxChunks) break
            if (used + chunk.text.length > maxChars) continue
            chosen += chunk
            used += chunk.text.length
        }
        return chosen.sortedBy { order.getValue(it.key()) }
    }

    private fun DocumentChunk.key() = documentId + "#" + position

    const val MAX_CHUNKS = 8
}
