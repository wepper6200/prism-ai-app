package ai.prism.store.model

enum class StoreError {
    /** Нет сети или GitHub не ответил. */
    NETWORK,

    /** Каталог не найден в последнем релизе или повреждён. */
    CATALOG,

    /** Скачанный файл не совпал с контрольной суммой. */
    CHECKSUM,

    /** Не хватает места или нет доступа к папке. */
    STORAGE,

    /** Android: нужно разрешить Prism Store устанавливать приложения. */
    PERMISSION,

    /** Установка отменена в системном окне. */
    CANCELLED,

    /** Windows: приложение открыто — его файлы заняты. */
    APP_RUNNING,

    INSTALL,
}

class StoreException(val error: StoreError, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Откуда берётся каталог (GitHub Releases). */
interface CatalogSource {
    /** Бросает [StoreException]. */
    suspend fun load(): StoreCatalog
}

/** Загрузка файла приложения с докачкой и проверкой SHA-256. */
interface PackageDownloader {
    /** Возвращает путь к скачанному файлу. Бросает [StoreException]. */
    suspend fun download(file: StoreFile, onProgress: (downloaded: Long, total: Long) -> Unit): String
}

/** Установка, проверка версии и запуск приложений на этой платформе. */
interface AppInstaller {
    /** Файл приложения для этой платформы или `null`, если приложение здесь не работает. */
    fun fileFor(app: StoreApp): StoreFile?

    /** Установленная версия или `null`, если приложение не установлено. */
    fun installedVersion(app: StoreApp): String?

    /**
     * Ставит скачанный файл: Android — системное окно установки, Windows — распаковка в папку программ
     * (обновление самого Prism Store — с перезапуском). Бросает [StoreException].
     */
    suspend fun install(app: StoreApp, path: String)

    /** Открывает установленное приложение. */
    fun open(app: StoreApp)
}
