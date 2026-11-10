package ai.prism.service

import ai.prism.data.documents.DocxText
import ai.prism.data.documents.EmbeddingCodec
import ai.prism.domain.documents.DocumentChunk
import ai.prism.domain.documents.DocumentFormat
import ai.prism.domain.documents.TextSection
import ai.prism.service.documents.DocumentSearch
import ai.prism.service.documents.TextChunker
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentTextTest {

    @Test
    fun formatIsDetectedByExtension() {
        assertEquals(DocumentFormat.PDF, DocumentFormat.ofFileName("Отчёт 2026.PDF"))
        assertEquals(DocumentFormat.DOCX, DocumentFormat.ofFileName("договор.docx"))
        assertEquals(DocumentFormat.MARKDOWN, DocumentFormat.ofFileName("README.md"))
        assertEquals(DocumentFormat.TEXT, DocumentFormat.ofFileName("notes.txt"))
        assertNull(DocumentFormat.ofFileName("photo.jpg"))
        assertNull(DocumentFormat.ofFileName("без расширения"))
    }

    @Test
    fun shortTextIsOneChunk() {
        val chunks = TextChunker().chunk(listOf(TextSection("Первый абзац.\n\nВторой абзац.", page = 2)))
        assertEquals(1, chunks.size)
        assertEquals("Первый абзац.\nВторой абзац.", chunks.single().text)
        assertEquals(2, chunks.single().page)
    }

    @Test
    fun longTextIsSplitWithOverlapAndPages() {
        fun sentence(number: Int) = "Это предложение номер $number о правилах компании."
        val page1 = (1..30).joinToString(" ") { sentence(it) }
        val page2 = (31..60).joinToString(" ") { sentence(it) }
        val chunks = TextChunker(targetChars = 400, overlapChars = 80)
            .chunk(listOf(TextSection(page1, page = 1), TextSection(page2, page = 2)))

        assertTrue(chunks.size > 4, "получилось ${chunks.size} фрагментов")
        assertTrue(chunks.all { it.text.length <= 400 + 80 }, chunks.map { it.text.length }.toString())
        assertEquals(1, chunks.first().page)
        assertEquals(2, chunks.last().page)
        // Конец одного фрагмента повторяется в начале следующего.
        val tail = chunks[0].text.takeLast(30)
        assertTrue(chunks[1].text.contains(tail.trim().substringAfter(' ')), "нет перекрытия: ${chunks[1].text}")
        // Весь текст сохранился.
        (1..60).forEach { number -> assertTrue(chunks.any { "номер $number " in it.text }, "потерялось предложение $number") }
    }

    @Test
    fun hugeWordIsCut() {
        val chunks = TextChunker(targetChars = 100, overlapChars = 0).chunk(listOf(TextSection("а".repeat(250))))
        assertEquals(listOf(100, 100, 50), chunks.map { it.text.length })
    }

    @Test
    fun docxXmlBecomesParagraphs() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="w"><w:body>
            <w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>Договор &amp; условия</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Срок — </w:t></w:r><w:r><w:t>30 дней</w:t></w:r><w:r><w:tab/><w:t>(рабочих)</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Цена</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>100 &#8381;</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            <w:p><w:r><w:delText>удалено</w:delText></w:r></w:p>
            </w:body></w:document>"""

        val text = DocxText.fromDocumentXml(xml)

        assertTrue(text.startsWith("Договор & условия\nСрок — 30 дней\t(рабочих)"), text)
        assertTrue("Цена" in text && "100 ₽" in text, text)
        assertTrue("удалено" !in text, text)
    }

    @Test
    fun embeddingsSurviveStorage() {
        val vector = floatArrayOf(0.5f, -0.25f, 1e-7f, 0f, -1f)
        assertContentEquals(vector, EmbeddingCodec.decode(EmbeddingCodec.encode(vector)))
    }

    @Test
    fun searchPicksSimilarChunksWithinBudgetInDocumentOrder() {
        fun chunk(position: Int, text: String, vararg values: Float) =
            DocumentChunk("doc", "file.txt", position, null, text, values)
        val chunks = listOf(
            chunk(0, "о собаках", 0f, 1f),
            chunk(1, "о котах", 1f, 0f),
            chunk(2, "снова о котах и немного о собаках", 0.9f, 0.43589f),
            chunk(3, "длинный текст о котах ".repeat(20), 1f, 0f),
        )

        val found = DocumentSearch.select(chunks, query = floatArrayOf(1f, 0f), maxChars = 60, maxChunks = 2)

        // Самые похожие — 1 и 3, но 3 не помещается в бюджет; взят следующий по сходству — 2.
        assertEquals(listOf(1, 2), found.map { it.position })
        // Далёкие от лучшего фрагменты не берутся, даже если остаётся место.
        assertEquals(listOf(1), DocumentSearch.select(chunks.take(2), floatArrayOf(1f, 0f), maxChars = 1000).map { it.position })
        assertTrue(DocumentSearch.fitsWhole(chunks.take(3), maxChars = 100))
    }
}
