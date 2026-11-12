package ai.prism.update

import ai.prism.data.update.GitHubAppUpdater
import ai.prism.desktop.AppDirectories
import ai.prism.domain.update.UpdateError
import ai.prism.domain.update.UpdateException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Обновление на Windows: zip из релиза распаковывается в папку данных, затем скрипт PowerShell ждёт выхода
 * программы, копирует новые файлы поверх папки программы и запускает её снова. Модели лежат отдельно
 * (`%LOCALAPPDATA%\PrismAI`) и не затрагиваются.
 */
class DesktopAppUpdater(
    private val updatesDir: File = File(AppDirectories.dataDir(), "updates"),
) : GitHubAppUpdater(updatesDir, "-windows-x64.zip") {

    /** Папка программы — там, где лежит PrismAI.exe (свойство задаёт запускатель jpackage). */
    private val installDir: File? = System.getProperty("jpackage.app-path")?.let { File(it).absoluteFile.parentFile }

    override val supported: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win") && installDir != null

    override suspend fun install(path: String, version: String) {
        val target = installDir ?: throw UpdateException(UpdateError.INSTALL, "Неизвестна папка программы")
        val script = withContext(Dispatchers.IO) {
            if (!canWrite(target)) {
                throw UpdateException(UpdateError.INSTALL, "Нет прав на запись в папку программы: ${target.absolutePath}")
            }
            val unpacked = File(updatesDir, "PrismAI-$version")
            val root = try {
                unzip(File(path), unpacked)
            } catch (e: IOException) {
                unpacked.deleteRecursively()
                throw UpdateException(UpdateError.STORAGE, "Не удалось распаковать обновление: ${e.message}", e)
            }
            File(updatesDir, "install-update.ps1").also { it.writeText(script(root, target, version), Charsets.UTF_8) }
        }
        try {
            ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-File", script.absolutePath,
            ).start()
        } catch (e: IOException) {
            throw UpdateException(UpdateError.INSTALL, "Не удалось запустить установку: ${e.message}", e)
        }
        // Файлы программы заняты, пока она работает: скрипт ждёт выхода и запускает новую версию.
        exitProcess(0)
    }

    /** Распаковывает zip в [destination] и возвращает папку, где лежит PrismAI.exe. */
    private fun unzip(zip: File, destination: File): File {
        destination.deleteRecursively()
        destination.mkdirs()
        val base = destination.canonicalFile
        ZipFile(zip).use { archive ->
            for (entry in archive.entries()) {
                val out = File(base, entry.name).canonicalFile
                if (!out.toPath().startsWith(base.toPath())) throw IOException("Недопустимый путь в архиве: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        return base.walkTopDown().maxDepth(3).firstOrNull { it.isFile && it.name.equals(EXE, ignoreCase = true) }
            ?.parentFile ?: throw IOException("В архиве нет $EXE")
    }

    private fun canWrite(dir: File): Boolean = try {
        File.createTempFile("prism-update", ".tmp", dir).delete()
        true
    } catch (e: IOException) {
        false
    }

    /**
     * Скрипт установки (UTF-8 с BOM, чтобы PowerShell 5 правильно прочитал пути с кириллицей); после копирования
     * записывает версию в prism-version.txt — её читает Prism Store.
     */
    private fun script(source: File, target: File, version: String): String {
        fun quote(file: File) = "'" + file.absolutePath.replace("'", "''") + "'"
        val pid = ProcessHandle.current().pid()
        return "\uFEFF" + """
            |${'$'}ErrorActionPreference = 'Continue'
            |${'$'}source = ${quote(source)}
            |${'$'}target = ${quote(target)}
            |Wait-Process -Id $pid -Timeout 60 -ErrorAction SilentlyContinue
            |Start-Sleep -Milliseconds 500
            |robocopy ${'$'}source ${'$'}target /E /R:10 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
            |if (${'$'}LASTEXITCODE -ge 8) {
            |    "Обновление не установлено: robocopy вернул ${'$'}LASTEXITCODE" | Out-File -Encoding utf8 (Join-Path ${'$'}target '$ERROR_FILE')
            |} else {
            |    Set-Content -Path (Join-Path ${'$'}target 'prism-version.txt') -Value '$version'
            |    Remove-Item -Recurse -Force (Split-Path ${'$'}source -Parent) -ErrorAction SilentlyContinue
            |}
            |Start-Process -FilePath (Join-Path ${'$'}target '$EXE')
            |""".trimMargin()
    }

    private companion object {
        const val EXE = "PrismAI.exe"
        const val ERROR_FILE = "update-error.txt"
    }
}
