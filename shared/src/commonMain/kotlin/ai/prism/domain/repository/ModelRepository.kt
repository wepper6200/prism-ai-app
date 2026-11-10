package ai.prism.domain.repository

import ai.prism.domain.model.CatalogModel
import ai.prism.domain.model.ModelEntry
import kotlinx.coroutines.flow.StateFlow

interface ModelRepository {
    /** Модели каталога с состоянием загрузки. */
    val models: StateFlow<List<ModelEntry>>

    /** Модель чата, которой отвечает ассистент (модели изображений не бывают активными). */
    val activeModelId: StateFlow<String?>

    fun download(modelId: String)

    /** Приостанавливает загрузку; скачанная часть сохраняется. */
    fun pause(modelId: String)

    fun delete(modelId: String)

    fun setActive(modelId: String)

    fun catalogModel(modelId: String): CatalogModel?

    /** Путь к полностью скачанному основному файлу модели или `null`. */
    fun downloadedFilePath(modelId: String): String?

    /** Путь к скачанному модулю зрения модели или `null` (нет в каталоге или не скачан). */
    fun downloadedProjectorPath(modelId: String): String?

    /** Файлы скачанной модели изображений или видео по назначению (`model`, `t5xxl`, `vae`…) или `null`, если скачано не всё. */
    fun imageModelFiles(modelId: String): Map<String, String>?
}
