package ai.prism.data.models

import ai.prism.domain.model.ModelFile
import java.io.File

class FileModelStorage(private val directory: File) : ModelStorage {

    fun file(model: ModelFile): File = File(directory, model.fileName)

    fun partFile(model: ModelFile): File = File(directory, model.fileName + PART_SUFFIX)

    override fun isDownloaded(file: ModelFile): Boolean =
        file(file).let { it.isFile && it.length() == file.sizeBytes }

    override fun partialBytes(file: ModelFile): Long =
        partFile(file).takeIf { it.isFile }?.length() ?: 0L

    override fun filePath(file: ModelFile): String = file(file).absolutePath

    override fun delete(file: ModelFile) {
        file(file).delete()
        partFile(file).delete()
    }

    override fun freeSpaceBytes(): Long {
        directory.mkdirs()
        return directory.usableSpace
    }

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}
