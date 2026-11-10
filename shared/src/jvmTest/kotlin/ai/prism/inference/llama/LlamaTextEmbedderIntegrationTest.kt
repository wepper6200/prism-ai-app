package ai.prism.inference.llama

import ai.prism.domain.model.EmbeddingSettings
import ai.prism.domain.model.ModelKind
import ai.prism.platform.DesktopNativeLibraryLoader
import ai.prism.service.documents.DocumentSearch
import ai.prism.testing.FakeDeviceInfo
import ai.prism.testing.testModel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Настоящая модель эмбеддингов (EmbeddingGemma, PRISM_TEST_EMBED) через llama.cpp:
 * вопрос по-русски ближе к фрагменту о том же, чем к фрагменту о другом. Без модели тест пропускается.
 */
class LlamaTextEmbedderIntegrationTest {

    @Test
    fun similarTextsAreCloser(): Unit = runBlocking {
        assumeTrue("нет нативных библиотек", System.getProperty("prism.native.dir") != null)
        val file = System.getenv("PRISM_TEST_EMBED")?.let(::File)?.takeIf { it.isFile }
        assumeTrue("не задана модель PRISM_TEST_EMBED", file != null)
        val model = testModel().copy(
            id = "embed",
            fileName = checkNotNull(file).name,
            sizeBytes = file.length(),
            kind = ModelKind.EMBEDDING,
            embedding = EmbeddingSettings(
                queryTemplate = "task: search result | query: {text}",
                documentTemplate = "title: {title} | text: {text}",
            ),
        )
        val settings = checkNotNull(model.embedding)
        val embedder = LlamaTextEmbedder(DesktopNativeLibraryLoader(), FakeDeviceInfo())
        try {
            val vectors = embedder.embed(
                model,
                file.absolutePath,
                listOf(
                    settings.query("Сколько дней отпуска положено сотруднику?"),
                    settings.document("Правила", "Сотрудник получает 28 календарных дней оплачиваемого отпуска в год."),
                    settings.document("Правила", "Сервер базы данных перезагружается каждую ночь в три часа."),
                ),
            )
            assertEquals(3, vectors.size)
            vectors.forEach { vector ->
                val norm = vector.sumOf { (it * it).toDouble() }
                assertTrue(abs(norm - 1.0) < 1e-3, "вектор не нормирован: $norm")
            }
            val relevant = DocumentSearch.similarity(vectors[0], vectors[1])
            val other = DocumentSearch.similarity(vectors[0], vectors[2])
            println("Сходство: об отпуске $relevant, о сервере $other")
            assertTrue(relevant > other + 0.05f, "фрагмент об отпуске должен быть ближе: $relevant против $other")
        } finally {
            embedder.release()
        }
    }
}
