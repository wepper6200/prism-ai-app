package ai.prism.data.agent

import ai.prism.domain.agent.DesktopWindow
import ai.prism.domain.agent.WindowControl
import ai.prism.domain.agent.WindowActionResult
import ai.prism.domain.agent.WindowTitleChanged
import kotlinx.serialization.json.JsonPrimitive
import ai.prism.domain.agent.WindowElement
import ai.prism.domain.agent.WindowOperation
import ai.prism.domain.agent.WindowObservation
import ai.prism.domain.agent.WorkspaceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

/** Uses the Windows accessibility provider; actions address a control in one window by runtime ID. */
class WindowsWindowControl : WindowControl {
    override suspend fun windows(): List<DesktopWindow> =
        call<List<DesktopWindow>>("list").filter { it.pid.toLong() != ProcessHandle.current().pid() }

    override suspend fun inspect(window: DesktopWindow): WindowObservation = call("inspect", window)

    override suspend fun capture(window: DesktopWindow): ByteArray = Base64.getDecoder().decode(call<String>("screenshot", window))

    override suspend fun act(window: DesktopWindow, element: WindowElement, operation: WindowOperation, text: String) {
        actWithResult(window, element, operation, text)
    }

    override suspend fun actWithResult(window: DesktopWindow, element: WindowElement, operation: WindowOperation, text: String): WindowActionResult {
        val result = if (operation == WindowOperation.SET_TEXT || operation == WindowOperation.SUBMIT_TEXT) {
            withPrismWindowsHidden { call<JsonPrimitive>(operation.wireName, window, element, text) }
        } else call<JsonPrimitive>(operation.wireName, window, element, text)
        return if (result.isString && result.content == "typed_unverified") WindowActionResult.TYPED_UNVERIFIED else WindowActionResult.DONE
    }

    override suspend fun submitVisuallyChecked(window: DesktopWindow, element: WindowElement, text: String) {
        withPrismWindowsHidden { call<Boolean>("submit_text", window, element, text, visualApproval = true) }
    }

    private suspend inline fun <reified T> call(
        operation: String,
        window: DesktopWindow? = null,
        element: WindowElement? = null,
        text: String = "",
        visualApproval: Boolean = false,
    ): T {
        val request = buildJsonObject {
            put("operation", operation)
            put("clientPid", ProcessHandle.current().pid())
            window?.let { put("window", Json.encodeToString(it)) }
            element?.let { put("element", Json.encodeToString(it)) }
            put("text", text)
            put("visualApproval", visualApproval)
        }
        return try {
            Json.decodeFromString<T>(execute(request.toString()))
        } catch (e: WindowTitleChanged) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WorkspaceException("Windows UI Automation: " + (e.message ?: "window unavailable"), e)
        }
    }

    private suspend fun execute(request: String): String = withContext(Dispatchers.IO) {
        coroutineScope {
            val windows = System.getenv("SystemRoot") ?: throw WorkspaceException("Windows directory unavailable")
            val powershell = File(windows, "System32/WindowsPowerShell/v1.0/powershell.exe")
            val process = ProcessBuilder(powershell.absolutePath, "-NoLogo", "-NoProfile", "-NonInteractive", "-MTA",
                "-EncodedCommand", bootstrap).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                val output = async { process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
                process.outputStream.bufferedWriter(Charsets.US_ASCII).use {
                    it.write(bridge)
                    it.newLine()
                    it.write(Base64.getEncoder().encodeToString(request.toByteArray(Charsets.UTF_8)))
                    it.newLine()
                }
                val deadline = System.nanoTime() + 8_000_000_000L
                while (process.isAlive) {
                    if (System.nanoTime() >= deadline) throw WorkspaceException("Window did not respond in time")
                    delay(40)
                }
                val result = output.await().trim()
                if (process.exitValue() != 0) {
                    if (result == "PRISM_WINDOW_TITLE_CHANGED") throw WindowTitleChanged()
                    throw WorkspaceException(result.lineSequence().firstOrNull().orEmpty().take(400))
                }
                result
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private companion object {
        // The first input line is the bundled script; the second is data parsed by that script.
        private val bootstrap = Base64.getEncoder().encodeToString(
            "& ([ScriptBlock]::Create([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([Console]::ReadLine()))))"
                .toByteArray(Charsets.UTF_16LE),
        )
        val bridge: String by lazy {
            val script = checkNotNull(WindowsWindowControl::class.java.getResourceAsStream("/prism/windows-window-control.ps1"))
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        }
    }
}
