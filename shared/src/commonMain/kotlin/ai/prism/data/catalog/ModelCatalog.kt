package ai.prism.data.catalog

import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.ModelCatalogFile
import ai.prism.resources.Res
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** Каталог моделей из ресурса `files/models.json`. */
class ModelCatalog(
    private val source: suspend () -> ByteArray = ::readBundledCatalog,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): List<CatalogModel> = parse(source().decodeToString())

    fun parse(text: String): List<CatalogModel> =
        json.decodeFromString(ModelCatalogFile.serializer(), text).models

    private companion object {
        @OptIn(ExperimentalResourceApi::class)
        suspend fun readBundledCatalog(): ByteArray = Res.readBytes("files/models.json")
    }
}
