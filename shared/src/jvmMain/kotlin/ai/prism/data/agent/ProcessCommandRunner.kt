package ai.prism.data.agent

import ai.prism.domain.agent.CommandResult
import ai.prism.domain.agent.CommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Команды в папке проекта: `cmd /c` на Windows, `sh -c` на других системах. Вывод и ошибки — вместе. */
class ProcessCommandRunner : CommandRunner {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    override val isSupported: Boolean = true

    override suspend fun run(directory: String, command: String, timeoutMs: Long): CommandResult = withContext(Dispatchers.IO) {
        val shell = if (windows) listOf("cmd.exe", "/d", "/s", "/c", command) else listOf("sh", "-c", command)
        val process = try {
            ProcessBuilder(shell).directory(File(directory)).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return@withContext CommandResult(exitCode = null, output = "cannot start: ${e.message}", timedOut = false)
        }
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread({
            try {
                process.inputStream.bufferedReader(if (windows) consoleCharset() else Charsets.UTF_8).forEachLine { line ->
                    synchronized(output) {
                        output.append(line).append('\n')
                        if (output.length > MAX_OUTPUT) output.delete(0, output.length - MAX_OUTPUT)
                    }
                }
            } catch (e: IOException) {
                // Процесс завершён принудительно.
            }
        }, "prism-command").apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(READER_WAIT_MS)
        CommandResult(
            exitCode = if (finished) process.exitValue() else null,
            output = synchronized(output) { output.toString() },
            timedOut = !finished,
        )
    }

    /** Кодировка консоли Windows (кириллица в выводе команд). */
    private fun consoleCharset() = runCatching { charset(System.getProperty("sun.stdout.encoding") ?: "cp866") }.getOrDefault(Charsets.UTF_8)

    private companion object {
        const val MAX_OUTPUT = 64 * 1024
        const val READER_WAIT_MS = 2_000L
    }
}
