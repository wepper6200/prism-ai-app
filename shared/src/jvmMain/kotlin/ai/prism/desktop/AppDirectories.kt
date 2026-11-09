package ai.prism.desktop

import java.io.File
import java.io.IOException

/** Каталоги данных Prism AI на Desktop. На Windows — `%LOCALAPPDATA%\PrismAI`. */
object AppDirectories {

    private const val APP_DIR = "PrismAI"

    fun dataDir(): File {
        val home = File(System.getProperty("user.home"))
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val base = when {
            os.contains("win") -> System.getenv("LOCALAPPDATA")?.let(::File) ?: File(home, "AppData/Local")
            os.contains("mac") -> File(home, "Library/Application Support")
            else -> System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
        }
        val dir = File(base, APP_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Не удалось создать каталог данных: ${dir.absolutePath}")
        }
        return dir
    }
}
