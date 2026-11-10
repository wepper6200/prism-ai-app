package ai.prism.data.documents

import ai.prism.domain.documents.DocumentError
import ai.prism.domain.documents.DocumentException
import ai.prism.domain.documents.DocumentFormat
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileTextExtractorTest {

    private val extractor = FileTextExtractor(PdfBoxTextReader())

    @Test
    fun pdfPagesKeepTheirNumbers(): Unit = runBlocking {
        val sections = extractor.extract(pdf("Vacation policy: 28 days", "Server restarts at 3 AM"), DocumentFormat.PDF)

        assertEquals(listOf(1, 2), sections.map { it.page })
        assertTrue("28 days" in sections[0].text, sections[0].text)
        assertTrue("3 AM" in sections[1].text, sections[1].text)
    }

    @Test
    fun brokenPdfIsUnreadable(): Unit = runBlocking {
        val error = assertFailsWith<DocumentException> { extractor.extract("not a pdf".encodeToByteArray(), DocumentFormat.PDF) }
        assertEquals(DocumentError.UNREADABLE, error.error)
    }

    @Test
    fun emptyPdfHasNoText(): Unit = runBlocking {
        val error = assertFailsWith<DocumentException> { extractor.extract(pdf(), DocumentFormat.PDF) }
        assertEquals(DocumentError.NO_TEXT, error.error)
    }

    @Test
    fun docxBodyIsRead(): Unit = runBlocking {
        val docx = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("[Content_Types].xml"))
                zip.write("<Types/>".encodeToByteArray())
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write("<w:document><w:body><w:p><w:r><w:t>Привет из Word</w:t></w:r></w:p></w:body></w:document>".encodeToByteArray())
            }
        }.toByteArray()

        assertEquals("Привет из Word", extractor.extract(docx, DocumentFormat.DOCX).single().text)
        assertEquals(
            DocumentError.UNREADABLE,
            assertFailsWith<DocumentException> { extractor.extract(byteArrayOf(1, 2, 3), DocumentFormat.DOCX) }.error,
        )
    }

    @Test
    fun textEncodingsAreDetected(): Unit = runBlocking {
        val text = "Съешь же ещё этих мягких французских булок"
        assertEquals(text, extractor.extract(text.toByteArray(Charsets.UTF_8), DocumentFormat.TEXT).single().text)
        assertEquals(text, extractor.extract(text.toByteArray(charset("windows-1251")), DocumentFormat.TEXT).single().text)
        assertEquals(text, FileTextExtractor.decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()))
        assertEquals(text, FileTextExtractor.decodeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
    }

    private fun pdf(vararg pages: String): ByteArray = PDDocument().use { document ->
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        if (pages.isEmpty()) document.addPage(PDPage())
        pages.forEach { text ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(font, 14f)
                stream.newLineAtOffset(72f, 700f)
                stream.showText(text)
                stream.endText()
            }
        }
        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
    }
}
