package ai.prism.data.models

import java.io.File
import java.io.IOException

/**
 * Перенос папки моделей. На одном диске файлы переименовываются (мгновенно), между дисками — копируются
 * по одному через временный файл и сразу удаляются из старой папки, поэтому на исходном диске место
 * освобождается по ходу. Файлы, которые уже лежат в новой папке с тем же размером, не копируются.
 */
object ModelFolderMover {

    class NotEnoughSpaceException(message: String) : IOException(message)

    /** Бросает [IOException] (в том числе [NotEnoughSpaceException] — до начала переноса). */
    fun move(from: File, to: File, onTotal: (Long) -> Unit = {}, onProgress: (Long) -> Unit = {}) {
        to.mkdirs()
        if (!to.isDirectory) throw IOException("Не удалось создать папку ${to.absolutePath}")
        val files = if (from.isDirectory) from.walkTopDown().filter { it.isFile }.toList() else emptyList()
        val total = files.sumOf { it.length() }
        onTotal(total)
        val toCopy = files.filterNot { sameFile(it, File(to, it.relativeTo(from).path)) }
            .filterNot { canRename(it, to) }
            .sumOf { it.length() }
        if (toCopy > 0 && to.usableSpace < toCopy + SPACE_MARGIN_BYTES) {
            throw NotEnoughSpaceException("Нужно ${toCopy / MB} МБ, свободно ${to.usableSpace / MB} МБ")
        }
        var moved = 0L
        for (file in files) {
            val target = File(to, file.relativeTo(from).path)
            target.parentFile?.mkdirs()
            val size = file.length()
            when {
                sameFile(file, target) -> file.delete()
                file.renameTo(target) -> Unit
                else -> {
                    copy(file, target) { bytes -> onProgress(moved + bytes) }
                    file.delete()
                }
            }
            moved += size
            onProgress(moved)
        }
        from.walkBottomUp().filter { it.isDirectory && it != from }.forEach { it.delete() }
    }

    private fun sameFile(source: File, target: File): Boolean = target.isFile && target.length() == source.length()

    /** Один и тот же диск — переименование вместо копии (корень пути совпадает). */
    private fun canRename(file: File, to: File): Boolean =
        file.absoluteFile.toPath().root == to.absoluteFile.toPath().root &&
            file.absoluteFile.toPath().fileSystem == to.absoluteFile.toPath().fileSystem

    /** Копия через временный файл: прерванная копия не выглядит скачанной моделью. */
    private fun copy(source: File, target: File, onProgress: (Long) -> Unit) {
        val temp = File(target.parentFile, target.name + COPY_SUFFIX)
        source.inputStream().use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var copied = 0L
                var reported = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (copied - reported >= REPORT_BYTES) {
                        onProgress(copied)
                        reported = copied
                    }
                }
            }
        }
        target.delete()
        if (!temp.renameTo(target)) throw IOException("Не удалось сохранить ${target.absolutePath}")
    }

    private const val COPY_SUFFIX = ".copy"
    private const val BUFFER_SIZE = 1024 * 1024
    private const val REPORT_BYTES = 16L * 1024 * 1024
    private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
    private const val MB = 1024L * 1024
}
