package ai.prism.domain.documents

/** Формат файла, который можно приложить к диалогу. */
enum class DocumentFormat(val extensions: Set<String>) {
    PDF(setOf("pdf")),
    DOCX(setOf("docx")),
    MARKDOWN(setOf("md", "markdown")),
    TEXT(setOf("txt", "text", "csv", "log")),
    ;

    companion object {
        /** Формат по имени файла или `null`, если такие файлы не поддерживаются. */
        fun ofFileName(name: String): DocumentFormat? {
            val extension = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { extension in it.extensions }
        }
    }
}

enum class DocumentStatus { INDEXING, READY, FAILED }

/** Почему файл не удалось подготовить к поиску. */
enum class DocumentError {
    /** Формат не поддерживается. */
    UNSUPPORTED,

    /** Файл повреждён или защищён паролем. */
    UNREADABLE,

    /** В файле нет текста (например, PDF из сканов). */
    NO_TEXT,

    /** Файл слишком большой. */
    TOO_LARGE,

    /** Модель поиска по файлам не скачана. */
    NO_EMBEDDING_MODEL,

    /** Модель поиска по файлам повреждена. */
    MODEL_CORRUPTED,
    NOT_ENOUGH_MEMORY,

    /** Приложение закрылось во время подготовки файла. */
    INTERRUPTED,
    FAILED,
}

/** Файл, приложенный к диалогу. Ассистент ищет в нём фрагменты, подходящие к вопросу. */
data class ChatDocument(
    val id: String,
    val chatId: String,
    val name: String,
    val format: DocumentFormat,
    val sizeBytes: Long,
    val status: DocumentStatus,
    val error: DocumentError?,
    val chunkCount: Int,
    val addedAt: Long,
)

/** Часть текста файла; [page] — номер страницы PDF (с 1) или `null`. */
data class TextSection(val text: String, val page: Int? = null)

/** Фрагмент файла для поиска: текст и его эмбеддинг (вектор единичной длины). */
data class DocumentChunk(
    val documentId: String,
    val documentName: String,
    val position: Int,
    val page: Int?,
    val text: String,
    val embedding: FloatArray,
) {
    override fun equals(other: Any?): Boolean = other is DocumentChunk && documentId == other.documentId &&
        position == other.position && text == other.text && embedding.contentEquals(other.embedding)

    override fun hashCode(): Int = documentId.hashCode() * 31 + position
}

/** Фрагмент файла, на который опирался ответ: имя файла и страница. */
data class MessageSource(val documentName: String, val page: Int? = null)

class DocumentException(val error: DocumentError, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Извлекает текст из файла поддерживаемого формата. */
fun interface DocumentTextExtractor {
    /** @throws DocumentException файл не читается или в нём нет текста. */
    suspend fun extract(bytes: ByteArray, format: DocumentFormat): List<TextSection>
}
