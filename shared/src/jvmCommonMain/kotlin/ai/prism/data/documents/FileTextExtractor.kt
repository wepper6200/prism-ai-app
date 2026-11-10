package ai.prism.data.documents

import ai.prism.domain.documents.DocumentError
import ai.prism.domain.documents.DocumentException
import ai.prism.domain.documents.DocumentFormat
import ai.prism.domain.documents.DocumentTextExtractor
import ai.prism.domain.documents.TextSection
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Текст страниц PDF по порядку (у каждой платформы своя библиотека PDFBox). */
fun interface PdfTextReader {
    /** @throws DocumentException файл повреждён или защищён паролем. */
    fun pages(bytes: ByteArray): List<String>
}

/** Извлечение текста из PDF, DOCX, Markdown и текстовых файлов. */
class FileTextExtractor(private val pdf: PdfTextReader) : DocumentTextExtractor {

    override suspend fun extract(bytes: ByteArray, format: DocumentFormat): List<TextSection> {
        val sections = when (format) {
            DocumentFormat.PDF -> pdf.pages(bytes).mapIndexed { index, text -> TextSection(text, page = index + 1) }
            DocumentFormat.DOCX -> listOf(TextSection(docx(bytes)))
            DocumentFormat.MARKDOWN, DocumentFormat.TEXT -> listOf(TextSection(decodeText(bytes)))
        }.filter { it.text.isNotBlank() }
        if (sections.isEmpty()) throw DocumentException(DocumentError.NO_TEXT, "В файле нет текста")
        return sections
    }

    private fun docx(bytes: ByteArray): String {
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == DOCX_BODY) {
                        val xml = zip.readBytes()
                        if (xml.size > MAX_XML_BYTES) throw DocumentException(DocumentError.TOO_LARGE, "Слишком большой документ")
                        return DocxText.fromDocumentXml(xml.decodeToString())
                    }
                }
            }
        } catch (e: ZipException) {
            throw DocumentException(DocumentError.UNREADABLE, "Файл DOCX повреждён", e)
        } catch (e: IOException) {
            throw DocumentException(DocumentError.UNREADABLE, "Файл DOCX не читается", e)
        }
        throw DocumentException(DocumentError.UNREADABLE, "В файле нет текста документа Word")
    }

    companion object {
        private const val DOCX_BODY = "word/document.xml"
        private const val MAX_XML_BYTES = 200 * 1024 * 1024

        /**
         * Текст в UTF-8 (с BOM или без) или UTF-16 с BOM; если это не UTF-8 — в Windows-1251,
         * в которой часто сохранены старые русские текстовые файлы.
         */
        fun decodeText(bytes: ByteArray): String {
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).drop(1)
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).drop(1)
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
            } catch (e: CharacterCodingException) {
                String(bytes, Charset.forName("windows-1251"))
            }
        }
    }
}
