package ai.prism.domain.update

/** Файл новой версии приложения для этой платформы (APK или zip для Windows). */
data class ReleaseAsset(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** Последний релиз приложения: версия, что нового и файл для этой платформы (если он есть). */
data class AppRelease(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val asset: ReleaseAsset?,
)

enum class UpdateError {
    /** Нет сети или сервер не ответил. */
    NETWORK,

    /** В релизе нет файла для этой платформы. */
    NO_PACKAGE,

    /** Скачанный файл не совпал с контрольной суммой. */
    CHECKSUM,

    /** Не удалось сохранить или распаковать файл (место на диске, права). */
    STORAGE,

    /** Android: приложению не разрешено устанавливать обновления — открыт экран разрешения. */
    PERMISSION,

    /** Установка отменена в системном окне. */
    CANCELLED,

    /** Установка не удалась. */
    INSTALL,
}

class UpdateException(
    val error: UpdateError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Обновление приложения из релизов GitHub. Сеть — только по явному действию пользователя
 * (кнопка «Проверить обновления»); фоновых проверок нет.
 */
interface AppUpdater {
    /** Можно ли обновиться из приложения на этой платформе. */
    val supported: Boolean

    /** Последний опубликованный релиз. Бросает [UpdateException]. */
    suspend fun latestRelease(): AppRelease

    /** Скачивает файл (с докачкой), проверяет SHA-256 и возвращает путь к нему. Бросает [UpdateException]. */
    suspend fun download(asset: ReleaseAsset, onProgress: (downloaded: Long, total: Long) -> Unit): String

    /**
     * Ставит скачанную версию: Android — системное окно установки (поверх текущей версии, модели остаются),
     * Windows — замена файлов программы и перезапуск. Бросает [UpdateException].
     */
    suspend fun install(path: String, version: String)

    /** Платформа без обновлений из приложения. */
    object None : AppUpdater {
        override val supported: Boolean = false

        override suspend fun latestRelease(): AppRelease = unsupported()

        override suspend fun download(asset: ReleaseAsset, onProgress: (Long, Long) -> Unit): String = unsupported()

        override suspend fun install(path: String, version: String) = unsupported()

        private fun unsupported(): Nothing = throw UpdateException(UpdateError.NO_PACKAGE, "Обновление из приложения недоступно")
    }
}

/** Сравнение версий вида `0.8.0` / `v0.10.1` (числа по частям; суффиксы вроде `-beta` не учитываются). */
object AppVersion {

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
