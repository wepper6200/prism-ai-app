package ai.prism.data.models

import ai.prism.domain.model.ModelFile
import java.io.File

class FileModelStorage(initialDirectory: File) : ModelStorage {

    /** Папка моделей; меняется, когда пользователь переносит модели ([ai.prism.data.models.SwitchableModelFolder]). */
    @Volatile
    var directory: File = initialDirectory

    fun file(model: ModelFile): File = File(directory, model.fileName)

    fun partFile(model: ModelFile): File = File(directory, model.fileName + PART_SUFFIX)

    /** Папка с содержимым архива и отметка о том, что он распакован полностью. */
    private fun unpackedDir(model: ModelFile): File = File(directory, model.fileName + UNPACKED_SUFFIX)

    private fun unpackedMark(model: ModelFile): File = File(unpackedDir(model), UNPACKED_MARK)

    override fun isDownloaded(file: ModelFile): Boolean =
        file(file).let { it.isFile && it.length() == file.sizeBytes }

    override fun partialBytes(file: ModelFile): Long =
        partFile(file).takeIf { it.isFile }?.length() ?: 0L

    override fun filePath(file: ModelFile): String = file(file).absolutePath

    @Synchronized
    override fun unpackedPath(file: ModelFile): String {
        val dir = unpackedDir(file)
        val mark = unpackedMark(file)
        if (!mark.isFile || mark.readText() != file.sha256) {
            TarBz2Unpacker.unpack(file(file), dir)
            mark.writeText(file.sha256)
        }
        return dir.absolutePath
    }

    override fun delete(file: ModelFile) {
        file(file).delete()
        partFile(file).delete()
        if (file.archive) unpackedDir(file).deleteRecursively()
    }

    override fun freeSpaceBytes(): Long {
        directory.mkdirs()
        return directory.usableSpace
    }

    private companion object {
        const val PART_SUFFIX = ".part"
        const val UNPACKED_SUFFIX = ".d"
        const val UNPACKED_MARK = ".prism-unpacked"
    }
}
