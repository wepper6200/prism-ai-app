package ai.prism.data.agent

import ai.prism.domain.agent.Computer
import ai.prism.domain.agent.ComputerPath
import ai.prism.domain.agent.WorkspaceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Компьютер пользователя (Windows и другие настольные системы): файлы по полному пути, сведения о системе, программы. */
class DesktopComputer(
    private val ioContext: CoroutineContext = Dispatchers.IO,
    override val homePath: String = System.getProperty("user.home").orEmpty(),
    private val windows: Boolean = ProcessCommandRunner.isWindows(),
) : Computer {

    override fun isAbsolute(path: String): Boolean {
        val clean = path.trim()
        if (clean == "~" || clean.startsWith("~/") || clean.startsWith("~\\")) return true
        if (windows) return WINDOWS_DRIVE.containsMatchIn(clean) || clean.startsWith("\\\\")
        if (!clean.startsWith("/")) return false
        // «/src/main.py» модель часто пишет, имея в виду папку проекта: полным считается путь от существующей папки корня.
        val first = clean.trimStart('/').substringBefore('/')
        return first.isNotEmpty() && File("/$first").exists()
    }

    override suspend fun locate(path: String): ComputerPath? = withContext(ioContext) {
        if (!isAbsolute(path)) return@withContext null
        val file = try {
            expand(path).canonicalFile
        } catch (e: IOException) {
            return@withContext null
        }
        var folder: File? = if (file.isDirectory) file else file.parentFile
        while (folder != null && !folder.isDirectory) folder = folder.parentFile
        val root = folder ?: return@withContext null
        val relative = if (file == root) "." else file.relativeTo(root).invariantSeparatorsPath
        ComputerPath(FileWorkspace(root, ioContext), relative, file.path)
    }

    override fun relativeTo(root: String, path: String): String? {
        if (!isAbsolute(path)) return null
        return try {
            val base = File(root).canonicalFile
            val file = expand(path).canonicalFile
            when {
                file == base -> "."
                file.path.startsWith(base.path.trimEnd(File.separatorChar) + File.separator) -> file.relativeTo(base).invariantSeparatorsPath
                else -> null
            }
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun systemInfo(): String = withContext(ioContext) {
        val lines = mutableListOf<String>()
        lines += "OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})"
        runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.let { lines += "Computer name: $it" }
        lines += "User: ${System.getProperty("user.name")}"
        lines += "CPU: ${cpuName() ?: "unknown"}, ${Runtime.getRuntime().availableProcessors()} logical cores"
        memory()?.let { (total, free) -> lines += "RAM: ${gb(total)} total, ${gb(free)} free" }
        gpus().takeIf { it.isNotEmpty() }?.let { lines += "GPU: ${it.joinToString("; ")}" }
        screens().takeIf { it.isNotEmpty() }?.let { lines += "Screen: ${it.joinToString("; ")}" }
        File.listRoots().filter { it.totalSpace > 0 }.forEach { root ->
            lines += "Disk ${root.path}: ${gb(root.usableSpace)} free of ${gb(root.totalSpace)}"
        }
        lines += "Home folder: $homePath"
        listOf("Desktop", "Documents", "Downloads", "Pictures", "Music", "Videos")
            .map { File(homePath, it) }
            .filter { it.isDirectory }
            .forEach { lines += "${it.name}: ${it.path}" }
        lines += "Time zone: ${java.util.TimeZone.getDefault().id}"
        lines.joinToString("\n")
    }

    override suspend fun open(target: String, arguments: String) = withContext(ioContext) {
        val clean = target.trim().removeSurrounding("\"")
        val commandLine = when {
            windows -> {
                // Start-Process открывает как двойной щелчок: программы из PATH и «App Paths» (chrome, winword), файлы, папки, адреса.
                val args = if (arguments.isBlank()) "" else " -ArgumentList ${quote(arguments)}"
                ProcessCommandRunner.powerShell("Start-Process -FilePath ${quote(clean)}$args", windows = true)
            }
            System.getProperty("os.name").orEmpty().startsWith("Mac") ->
                listOf("open", clean) + if (arguments.isBlank()) emptyList() else listOf("--args") + arguments.trim().split(Regex("\\s+"))
            arguments.isBlank() -> listOf("xdg-open", expand(clean).path.takeIf { isAbsolute(clean) } ?: clean)
            else -> listOf(clean) + arguments.trim().split(Regex("\\s+"))
        }
        val process = try {
            ProcessBuilder(commandLine).redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw WorkspaceException("cannot open $clean: ${e.message}", e)
        }
        process.outputStream.close()
        // Start-Process и xdg-open сразу возвращаются; сама программа продолжает работать.
        if (!process.waitFor(OPEN_WAIT_SECONDS, TimeUnit.SECONDS)) return@withContext
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.exitValue() != 0) throw WorkspaceException("cannot open $clean${if (output.isBlank()) "" else ": " + output.take(500)}")
    }

    private fun expand(path: String): File {
        val clean = path.trim()
        return if (clean == "~" || clean.startsWith("~/") || clean.startsWith("~\\")) File(homePath, clean.drop(2)) else File(clean)
    }

    private fun quote(text: String) = "'" + text.replace("'", "''") + "'"

    private fun cpuName(): String? = if (windows) {
        System.getenv("PROCESSOR_IDENTIFIER")
    } else {
        runCatching {
            File("/proc/cpuinfo").useLines { lines -> lines.firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim() }
        }.getOrNull()
    }

    private fun memory(): Pair<Long, Long>? = runCatching {
        val bean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        bean.totalMemorySize to bean.freeMemorySize
    }.getOrNull()

    private fun gpus(): List<String> {
        if (!windows) return emptyList()
        return runCatching {
            val process = ProcessBuilder(
                ProcessCommandRunner.powerShell("(Get-CimInstance Win32_VideoController).Name", windows = true),
            ).redirectErrorStream(true).start()
            process.outputStream.close()
            if (!process.waitFor(GPU_WAIT_SECONDS, TimeUnit.SECONDS)) {
                ProcessCommandRunner.killTree(process)
                return emptyList()
            }
            process.inputStream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    private fun screens(): List<String> {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        return runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
                val mode = device.displayMode
                "${mode.width}×${mode.height}"
            }
        }.getOrDefault(emptyList())
    }

    private fun gb(bytes: Long) = "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))

    private companion object {
        val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/]")
        const val OPEN_WAIT_SECONDS = 20L
        const val GPU_WAIT_SECONDS = 8L
    }
}
