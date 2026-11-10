package ai.prism.data.documents

import ai.prism.domain.documents.DocumentError
import ai.prism.domain.documents.DocumentException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import java.io.IOException

/** Текст PDF на Desktop: Apache PDFBox. */
class PdfBoxTextReader : PdfTextReader {
    override fun pages(bytes: ByteArray): List<String> = try {
        Loader.loadPDF(bytes).use { document ->
            val stripper = PDFTextStripper().apply { setSortByPosition(true) }
            (1..document.numberOfPages).map { page ->
                stripper.setStartPage(page)
                stripper.setEndPage(page)
                stripper.getText(document)
            }
        }
    } catch (e: InvalidPasswordException) {
        throw DocumentException(DocumentError.UNREADABLE, "PDF защищён паролем", e)
    } catch (e: IOException) {
        throw DocumentException(DocumentError.UNREADABLE, "PDF повреждён", e)
    }
}
