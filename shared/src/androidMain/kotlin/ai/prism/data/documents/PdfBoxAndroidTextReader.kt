package ai.prism.data.documents

import ai.prism.domain.documents.DocumentError
import ai.prism.domain.documents.DocumentException
import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException

/** Текст PDF на Android: PdfBox-Android (порт Apache PDFBox). */
class PdfBoxAndroidTextReader(private val context: Context) : PdfTextReader {
    @Volatile
    private var initialized = false

    override fun pages(bytes: ByteArray): List<String> {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
        return try {
            PDDocument.load(bytes).use { document ->
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
}
