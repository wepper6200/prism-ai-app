package ai.prism.data.models

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException

/**
 * Распаковка архивов `tar.bz2` (голоса Piper: модель, `tokens.txt` и `espeak-ng-data`).
 * Содержимое пишется во временную папку и переносится на место целиком, поэтому прерванная
 * распаковка не оставляет полупустую папку. Пути вне папки назначения и ссылки пропускаются.
 */
internal object TarBz2Unpacker {

    fun unpack(archive: File, target: File) {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        temp.deleteRecursively()
        if (!temp.mkdirs()) throw IOException("Cannot create ${temp.absolutePath}")
        val root = temp.canonicalFile.toPath()
        archive.inputStream().buffered(BUFFER_BYTES).use { raw ->
            TarArchiveInputStream(BZip2CompressorInputStream(raw)).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val out = File(temp, entry.name).canonicalFile
                    when {
                        !out.toPath().startsWith(root) || entry.isSymbolicLink || entry.isLink -> Unit
                        entry.isDirectory -> out.mkdirs()
                        else -> {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tar.copyTo(it, BUFFER_BYTES) }
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
        target.deleteRecursively()
        if (!temp.renameTo(target)) throw IOException("Cannot move ${temp.absolutePath} to ${target.absolutePath}")
    }

    private const val TEMP_SUFFIX = ".tmp"
    private const val BUFFER_BYTES = 1 shl 16
}
