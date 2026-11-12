package ai.prism.store.model

import kotlinx.serialization.Serializable

/**
 * Каталог Prism Store: файл `prism-store-catalog.json` в последнем релизе GitHub. Его собирает задание release
 * (`.github/store_catalog.py`) из `store/apps.json` и файлов релиза — версии, размеры и SHA-256 всегда точные.
 */
@Serializable
data class StoreCatalog(
    val version: Int = 1,
    val apps: List<StoreApp> = emptyList(),
)

@Serializable
data class StoreApp(
    /** `prism-ai`, `prism-store`. */
    val id: String,
    val name: String,
    val description: String = "",
    val version: String,
    /** Файл для каждой платформы: `android`, `windows`. Нет файла — приложение для неё недоступно. */
    val android: AndroidPackage? = null,
    val windows: WindowsPackage? = null,
    val license: String = "",
)

/** Файл приложения в релизе: ссылка, размер и SHA-256 (проверяются после загрузки). */
@Serializable
data class StoreFile(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class AndroidPackage(
    /** Идентификатор пакета: по нему видно, установлено ли приложение и какой версии. */
    val packageName: String,
    val file: StoreFile,
)

@Serializable
data class WindowsPackage(
    /** Папка внутри zip и папка установки (`PrismAI`). */
    val folder: String,
    /** Запускаемый файл в этой папке (`PrismAI.exe`). */
    val exe: String,
    val file: StoreFile,
)

/** Сравнение версий вида `0.9.0` / `v1.0.1` по числам. */
object Versions {
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    fun compare(a: String, b: String): Int {
        val left = parts(a)
        val right = parts(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    private fun parts(version: String): List<Int> =
        version.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
}
