package ai.prism.data.agent

import ai.prism.domain.agent.CommandResult
import ai.prism.domain.agent.CommandRunner
import ai.prism.domain.agent.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Команды: `cmd /c` на Windows (`sh -c` на других системах) или PowerShell. Вывод и ошибки — вместе,
 * каждая строка сразу уходит в `onOutput`. Отмена корутины и истёкшее время завершают процесс вместе с дочерними
 * (сборка, запущенная из cmd, тоже останавливается).
 */
class ProcessCommandRunner : CommandRunner {
    private val windows = isWindows()

    override val isSupported: Boolean = true

    override suspend fun run(
        directory: String,
        command: String,
        timeoutMs: Long,
        shell: Shell,
        onOutput: (String) -> Unit,
    ): CommandResult = withContext(Dispatchers.IO) {
        val (commandLine, charset) = when {
            shell == Shell.POWERSHELL -> powerShell(command, windows) to Charsets.UTF_8
            windows -> listOf("cmd.exe", "/d", "/s", "/c", command) to consoleCharset()
            else -> listOf("sh", "-c", command) to Charsets.UTF_8
        }
        val process = try {
            ProcessBuilder(commandLine).directory(File(directory)).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return@withContext CommandResult(exitCode = null, output = "cannot start: ${e.message}", timedOut = false)
        }
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread({
            try {
                process.inputStream.bufferedReader(charset).forEachLine { line ->
                    synchronized(output) {
                        output.append(line).append('\n')
                        if (output.length > MAX_OUTPUT) output.delete(0, output.length - MAX_OUTPUT)
                    }
                    onOutput(line)
                }
            } catch (e: IOException) {
                // Процесс завершён принудительно.
            }
        }, "prism-command").apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var finished = false
        try {
            while (true) {
                if (process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
                if (System.nanoTime() >= deadline) break
                ensureActive()
            }
        } finally {
            if (!finished) killTree(process)
        }
        reader.join(READER_WAIT_MS)
        CommandResult(
            exitCode = if (finished) process.exitValue() else null,
            output = synchronized(output) { output.toString() },
            timedOut = !finished,
        )
    }

    /** Кодировка консоли Windows (кириллица в выводе команд). */
    private fun consoleCharset() = runCatching { charset(System.getProperty("sun.stdout.encoding") ?: "cp866") }.getOrDefault(Charsets.UTF_8)

    companion object {
        const val MAX_OUTPUT = 64 * 1024
        private const val READER_WAIT_MS = 2_000L
        private const val POLL_MS = 100L

        internal fun isWindows() = System.getProperty("os.name").orEmpty().startsWith("Windows")

        /**
         * PowerShell без профиля и вопросов. Скрипт передаётся в Base64 (UTF-16LE): кавычки и кириллица не ломаются,
         * вывод — в UTF-8, без полосы прогресса (она засоряет вывод).
         */
        internal fun powerShell(script: String, windows: Boolean): List<String> {
            val full = "\$ProgressPreference='SilentlyContinue'; [Console]::OutputEncoding=[System.Text.Encoding]::UTF8; $script"
            val encoded = java.util.Base64.getEncoder().encodeToString(full.toByteArray(Charsets.UTF_16LE))
            val executable = if (windows) "powershell.exe" else "pwsh"
            return listOf(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
        }

        internal fun killTree(process: Process) {
            runCatching { process.descendants().forEach { it.destroyForcibly() } }
            process.destroyForcibly()
        }
    }
}
