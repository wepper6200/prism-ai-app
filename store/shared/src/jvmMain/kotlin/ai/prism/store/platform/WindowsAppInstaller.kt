package ai.prism.store.platform

import ai.prism.store.model.AppInstaller
import ai.prism.store.model.StoreApp
import ai.prism.store.model.StoreError
import ai.prism.store.model.StoreException
import ai.prism.store.model.StoreFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Windows: приложения ставятся в `%LOCALAPPDATA%\Programs\Prism\<папка>` (без прав администратора), в меню «Пуск»
 * появляется ярлык. Версия хранится рядом в [VERSION_FILE]. Сам Prism Store обновляется на месте: скрипт PowerShell
 * ждёт его выхода, копирует новые файлы и запускает его снова.
 */
class WindowsAppInstaller(
    private val programsDir: File,
    private val workDir: File,
    private val selfVersion: String,
    private val selfDir: File? = System.getProperty("jpackage.app-path")?.let { File(it).absoluteFile.parentFile },
    private val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win"),
    private val startMenuDir: File? = System.getenv("APPDATA")?.let { File(it, "Microsoft/Windows/Start Menu/Programs") },
) : AppInstaller {

    override fun fileFor(app: StoreApp): StoreFile? = if (isWindows) app.windows?.file else null

    override fun installedVersion(app: StoreApp): String? {
        if (app.id == SELF_ID) return selfVersion
        val windows = app.windows ?: return null
        val dir = installDir(app) ?: return null
        if (!File(dir, windows.exe).isFile) return null
        // Нет файла версии — программу положили вручную: считаем её старой, «Обновить» поставит текущую.
        return File(dir, VERSION_FILE).takeIf { it.isFile }?.readText()?.trim() ?: ""
    }

    override suspend fun install(app: StoreApp, path: String) {
        val windows = app.windows ?: throw StoreException(StoreError.INSTALL, "Нет версии для Windows")
        val target = installDir(app) ?: throw StoreException(StoreError.INSTALL, "Неизвестна папка программы")
        val self = app.id == SELF_ID
        withContext(Dispatchers.IO) {
            val unpacked = File(workDir, "unpack-${app.id}")
            val source = try {
                unzip(File(path), unpacked, windows.exe)
            } catch (e: IOException) {
                unpacked.deleteRecursively()
                throw StoreException(StoreError.STORAGE, "Не удалось распаковать: ${e.message}", e)
            }
            if (self) {
                startSelfUpdate(source, target, app.version)
            } else {
                if (isRunning(target)) {
                    unpacked.deleteRecursively()
                    throw StoreException(StoreError.APP_RUNNING, "${app.name} открыт")
                }
                try {
                    source.copyRecursively(target, overwrite = true)
                    File(target, VERSION_FILE).writeText(app.version)
                } catch (e: IOException) {
                    throw StoreException(StoreError.STORAGE, "Не удалось установить: ${e.message}", e)
                } finally {
                    unpacked.deleteRecursively()
                }
                createShortcut(app.name, File(target, windows.exe))
            }
        }
        if (self) exitProcess(0)
    }

    override fun open(app: StoreApp) {
        val windows = app.windows ?: return
        val dir = installDir(app) ?: return
        try {
            ProcessBuilder(File(dir, windows.exe).absolutePath).directory(dir).start()
        } catch (e: IOException) {
            System.err.println("Prism Store: не удалось запустить ${app.name}: ${e.message}")
        }
    }

    private fun installDir(app: StoreApp): File? = when {
        app.id == SELF_ID -> selfDir
        else -> app.windows?.let { File(programsDir, it.folder) }
    }

    private fun isRunning(dir: File): Boolean {
        val prefix = dir.absolutePath
        return ProcessHandle.allProcesses().anyMatch { process ->
            process.info().command().map { it.startsWith(prefix, ignoreCase = true) }.orElse(false)
        }
    }

    /** Распаковывает zip в [destination] и возвращает папку, где лежит [exe]. */
    private fun unzip(zip: File, destination: File, exe: String): File {
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
        return base.walkTopDown().maxDepth(3).firstOrNull { it.isFile && it.name.equals(exe, ignoreCase = true) }
            ?.parentFile ?: throw IOException("В архиве нет $exe")
    }

    /** Ярлык в меню «Пуск» (без него программу пришлось бы искать в папке). Ошибка ярлыка установку не отменяет. */
    private fun createShortcut(name: String, exe: File) {
        val dir = startMenuDir ?: return
        dir.mkdirs()
        val link = File(dir, "$name.lnk")
        val command = "\$s = (New-Object -ComObject WScript.Shell).CreateShortcut(${quote(link)}); " +
            "\$s.TargetPath = ${quote(exe)}; \$s.WorkingDirectory = ${quote(exe.parentFile)}; \$s.Save()"
        try {
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command).start().waitFor()
        } catch (e: IOException) {
            System.err.println("Prism Store: ярлык не создан: ${e.message}")
        }
    }

    private fun startSelfUpdate(source: File, target: File, version: String) {
        val pid = ProcessHandle.current().pid()
        val script = File(workDir, "update-store.ps1")
        // UTF-8 с BOM: PowerShell 5 правильно читает пути с кириллицей.
        script.writeText(
            "\uFEFF" + """
            |${'$'}source = ${quote(source)}
            |${'$'}target = ${quote(target)}
            |Wait-Process -Id $pid -Timeout 60 -ErrorAction SilentlyContinue
            |Start-Sleep -Milliseconds 500
            |robocopy ${'$'}source ${'$'}target /E /R:10 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
            |if (${'$'}LASTEXITCODE -lt 8) { Set-Content -Path (Join-Path ${'$'}target '$VERSION_FILE') -Value '$version' }
            |Remove-Item -Recurse -Force (Split-Path ${'$'}source -Parent) -ErrorAction SilentlyContinue
            |Start-Process -FilePath (Join-Path ${'$'}target '$SELF_EXE')
            |""".trimMargin(),
            Charsets.UTF_8,
        )
        try {
            ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.absolutePath,
            ).start()
        } catch (e: IOException) {
            throw StoreException(StoreError.INSTALL, "Не удалось запустить обновление: ${e.message}", e)
        }
    }

    private fun quote(file: File) = "'" + file.absolutePath.replace("'", "''") + "'"

    companion object {
        const val SELF_ID = "prism-store"
        const val VERSION_FILE = "prism-version.txt"
        private const val SELF_EXE = "PrismStore.exe"

        /** Папка программ пользователя: `%LOCALAPPDATA%\Programs\Prism`. */
        fun defaultProgramsDir(): File {
            val local = System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), "AppData/Local")
            return File(local, "Programs/Prism")
        }
    }
}
