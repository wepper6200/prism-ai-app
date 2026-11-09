package ai.prism.data.models

import ai.prism.domain.model.CatalogModel
import java.io.File

class FileModelStorage(private val directory: File) : ModelStorage {

    fun file(model: CatalogModel): File = File(directory, model.fileName)

    fun partFile(model: CatalogModel): File = File(directory, model.fileName + PART_SUFFIX)

    override fun isDownloaded(model: CatalogModel): Boolean =
        file(model).let { it.isFile && it.length() == model.sizeBytes }

    override fun partialBytes(model: CatalogModel): Long =
        partFile(model).takeIf { it.isFile }?.length() ?: 0L

    override fun filePath(model: CatalogModel): String = file(model).absolutePath

    override fun delete(model: CatalogModel) {
        file(model).delete()
        partFile(model).delete()
    }

    override fun freeSpaceBytes(): Long {
        directory.mkdirs()
        return directory.usableSpace
    }

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}
