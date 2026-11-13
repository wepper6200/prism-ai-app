package ai.prism.service.agent

import ai.prism.domain.agent.AgentStep
import ai.prism.domain.agent.AgentStepStatus
import ai.prism.domain.agent.AgentTool
import ai.prism.domain.agent.CommandRunner
import ai.prism.domain.agent.PendingAction
import ai.prism.domain.agent.WebClient
import ai.prism.domain.agent.WebException
import ai.prism.domain.agent.Workspace
import ai.prism.domain.agent.WorkspaceEntry
import ai.prism.domain.agent.WorkspaceException
import ai.prism.domain.model.AppSettings

/** Итог вызова инструмента: текст для модели и шаг для показа пользователю (`null` — вызов не распознан). */
data class ToolOutcome(val result: String, val step: AgentStep?)

/**
 * Инструменты ассистента в одном диалоге: папка проекта [workspace] (если выбрана), интернет [web]
 * (если включён) и команды [commands]. Изменения файлов и скачивание требуют разрешения [approve],
 * если не включено автоприменение [autoApplyEdits]; команды — всегда.
 */
class AgentToolbox(
    private val workspace: Workspace?,
    private val web: WebClient?,
    private val commands: CommandRunner,
    private val autoApplyEdits: Boolean,
    /** На сколько уровней `list_files` раскрывает вложенные папки; 0 — без ограничения (до [MAX_ENTRIES] строк). */
    private val folderDepth: Int = AppSettings.DEFAULT_FOLDER_DEPTH,
    private val approve: suspend (PendingAction) -> Boolean,
    private val newId: () -> String,
) {
    /** Инструменты, доступные в этом диалоге. */
    val tools: List<AgentTool> = AgentTool.entries.filter { tool ->
        (!tool.needsInternet || web != null) &&
            (!tool.needsWorkspace || workspace != null) &&
            (tool != AgentTool.RUN_COMMAND || (commands.isSupported && workspace?.localPath != null))
    }

    suspend fun execute(call: ToolCall): ToolOutcome {
        val tool = AgentTool.byName(call.name)?.takeIf { it in tools }
            ?: return ToolOutcome("error: unknown tool \"${call.name}\". Available: ${tools.joinToString { it.toolName }}", null)
        return try {
            when (tool) {
                AgentTool.WEB_SEARCH -> webSearch(call.arg("query"))
                AgentTool.FETCH_PAGE -> fetchPage(call.arg("url"))
                AgentTool.DOWNLOAD_FILE -> download(call.arg("url"), call.arg("path"))
                AgentTool.LIST_FILES -> listFiles(call.args["path"]?.takeIf { it.isNotBlank() } ?: ".")
                AgentTool.READ_FILE -> readFile(call.arg("path"))
                AgentTool.SEARCH_FILES -> searchFiles(call.arg("query"))
                AgentTool.WRITE_FILE -> writeFile(call.arg("path"), call.args["content"].orEmpty())
                AgentTool.EDIT_FILE -> editFile(call.arg("path"), call.arg("old"), call.args["new"].orEmpty())
                AgentTool.RUN_COMMAND -> runCommand(call.arg("command"))
            }
        } catch (e: MissingArgument) {
            ToolOutcome("error: missing argument <${e.name}>", AgentStep(tool, call.args.values.firstOrNull().orEmpty(), e.message.orEmpty(), AgentStepStatus.FAILED))
        } catch (e: WorkspaceException) {
            failed(tool, call.args["path"] ?: call.args.values.firstOrNull().orEmpty(), e.message.orEmpty())
        } catch (e: WebException) {
            failed(tool, call.args["url"] ?: call.args["query"].orEmpty(), e.message.orEmpty())
        }
    }

    private suspend fun webSearch(query: String): ToolOutcome {
        val results = checkNotNull(web).search(query)
        val text = if (results.isEmpty()) {
            "no results"
        } else {
            results.withIndex().joinToString("\n\n") { (index, result) -> "${index + 1}. ${result.title}\n${result.url}\n${result.snippet}" }
        }
        return done(AgentTool.WEB_SEARCH, query, text, text)
    }

    private suspend fun fetchPage(url: String): ToolOutcome {
        val text = checkNotNull(web).fetchText(url, PAGE_CHARS)
        return done(AgentTool.FETCH_PAGE, url, text.ifBlank { "the page has no text" }, text.take(DETAIL_CHARS))
    }

    private suspend fun download(url: String, path: String): ToolOutcome {
        val folder = checkNotNull(workspace)
        if (!confirm(AgentTool.DOWNLOAD_FILE, path, url)) return rejected(AgentTool.DOWNLOAD_FILE, path)
        val bytes = checkNotNull(web).download(url, MAX_DOWNLOAD_BYTES)
        folder.write(path, bytes)
        return done(AgentTool.DOWNLOAD_FILE, path, "saved $path (${bytes.size} bytes)", url)
    }

    /**
     * Файлы и папки вместе с вложенными (до [folderDepth] уровней): модели не нужно открывать каждую папку
     * отдельным шагом. Служебные папки (.git, build…) показываются, но не раскрываются.
     */
    private suspend fun listFiles(path: String): ToolOutcome {
        val folder = checkNotNull(workspace)
        val entries = mutableListOf<WorkspaceEntry>()
        var level = folder.list(path)
        var depth = 1
        var truncated = false
        while (level.isNotEmpty()) {
            val room = MAX_ENTRIES - entries.size
            if (level.size > room) {
                entries += level.take(room)
                truncated = true
                break
            }
            entries += level
            if (folderDepth > 0 && depth >= folderDepth) {
                truncated = level.any { it.isDirectory && it.path.substringAfterLast('/') !in SKIPPED_FOLDERS }
                break
            }
            level = level
                .filter { it.isDirectory && it.path.substringAfterLast('/') !in SKIPPED_FOLDERS }
                .flatMap { folder.list(it.path) }
            depth++
        }
        val text = if (entries.isEmpty()) {
            "empty folder"
        } else {
            entries.sortedBy { it.path }
                .joinToString("\n") { if (it.isDirectory) it.path + "/" else "${it.path} (${it.sizeBytes} bytes)" } +
                if (truncated) "\n… (more inside; list a subfolder to see it)" else ""
        }
        return done(AgentTool.LIST_FILES, path, text, text.take(DETAIL_CHARS))
    }

    private suspend fun readFile(path: String): ToolOutcome {
        val text = textOf(checkNotNull(workspace).read(path))
            ?: return failed(AgentTool.READ_FILE, path, "binary file, cannot be read as text")
        val shown = if (text.length <= FILE_CHARS) text else text.take(FILE_CHARS) + "\n… (file truncated, ${text.length} characters total)"
        return done(AgentTool.READ_FILE, path, shown, shown.take(DETAIL_CHARS))
    }

    private suspend fun searchFiles(query: String): ToolOutcome {
        val hits = checkNotNull(workspace).search(query, MAX_HITS)
        val text = if (hits.isEmpty()) "no matches" else hits.joinToString("\n") { "${it.path}:${it.line}: ${it.text.trim().take(LINE_CHARS)}" }
        return done(AgentTool.SEARCH_FILES, query, text, text.take(DETAIL_CHARS))
    }

    private suspend fun writeFile(path: String, content: String): ToolOutcome {
        val folder = checkNotNull(workspace)
        val old = if (folder.exists(path)) textOf(folder.read(path)) else null
        val diff = if (old == null) LineDiff.added(content) else LineDiff.of(old, content)
        if (!confirm(AgentTool.WRITE_FILE, path, diff)) return rejected(AgentTool.WRITE_FILE, path)
        folder.write(path, content.encodeToByteArray())
        return done(AgentTool.WRITE_FILE, path, "written $path (${content.lines().size} lines)", diff)
    }

    private suspend fun editFile(path: String, old: String, new: String): ToolOutcome {
        val folder = checkNotNull(workspace)
        val text = textOf(folder.read(path)) ?: return failed(AgentTool.EDIT_FILE, path, "binary file, cannot be edited")
        val count = text.windowedCount(old)
        if (count != 1) {
            val reason = if (count == 0) "<old> text was not found in $path" else "<old> text occurs $count times in $path, add more context"
            return failed(AgentTool.EDIT_FILE, path, reason)
        }
        val updated = text.replaceFirst(old, new)
        val diff = LineDiff.of(old, new)
        if (!confirm(AgentTool.EDIT_FILE, path, diff)) return rejected(AgentTool.EDIT_FILE, path)
        folder.write(path, updated.encodeToByteArray())
        return done(AgentTool.EDIT_FILE, path, "edited $path", diff)
    }

    private suspend fun runCommand(command: String): ToolOutcome {
        val directory = checkNotNull(workspace?.localPath)
        if (!ask(AgentTool.RUN_COMMAND, command, command)) return rejected(AgentTool.RUN_COMMAND, command)
        val result = commands.run(directory, command, COMMAND_TIMEOUT_MS)
        val output = result.output.let { if (it.length <= OUTPUT_CHARS) it else "…" + it.takeLast(OUTPUT_CHARS) }
        val status = when {
            result.timedOut -> "timed out after ${COMMAND_TIMEOUT_MS / 1000} s"
            else -> "exit code ${result.exitCode}"
        }
        val text = "$status\n$output".trim()
        val ok = !result.timedOut && result.exitCode == 0
        return ToolOutcome(text, AgentStep(AgentTool.RUN_COMMAND, command, text.take(DETAIL_CHARS), if (ok) AgentStepStatus.DONE else AgentStepStatus.FAILED))
    }

    /** Изменение файлов: без вопроса, если включено автоприменение. */
    private suspend fun confirm(tool: AgentTool, title: String, detail: String): Boolean =
        autoApplyEdits || ask(tool, title, detail)

    private suspend fun ask(tool: AgentTool, title: String, detail: String): Boolean =
        approve(PendingAction(newId(), tool, title, detail.take(APPROVAL_CHARS)))

    private fun done(tool: AgentTool, title: String, result: String, detail: String) =
        ToolOutcome(result, AgentStep(tool, title, detail, AgentStepStatus.DONE))

    private fun failed(tool: AgentTool, title: String, reason: String) =
        ToolOutcome("error: $reason", AgentStep(tool, title, reason, AgentStepStatus.FAILED))

    private fun rejected(tool: AgentTool, title: String) =
        ToolOutcome("the user rejected this action", AgentStep(tool, title, "", AgentStepStatus.REJECTED))

    private class MissingArgument(val name: String) : Exception("missing <$name>")

    private fun ToolCall.arg(name: String): String = args[name]?.takeIf { it.isNotBlank() } ?: throw MissingArgument(name)

    private fun String.windowedCount(part: String): Int {
        if (part.isEmpty()) return 0
        var count = 0
        var index = indexOf(part)
        while (index >= 0) {
            count++
            index = indexOf(part, index + part.length)
        }
        return count
    }

    private companion object {
        const val PAGE_CHARS = 6_000
        const val FILE_CHARS = 12_000
        const val OUTPUT_CHARS = 6_000
        const val DETAIL_CHARS = 2_000
        const val APPROVAL_CHARS = 8_000
        const val LINE_CHARS = 200
        const val MAX_ENTRIES = 300
        val SKIPPED_FOLDERS = setOf(".git", ".gradle", ".idea", "build", "node_modules", ".venv", "venv", "__pycache__", ".kotlin")
        const val MAX_HITS = 50
        const val MAX_DOWNLOAD_BYTES = 50L * 1024 * 1024
        const val COMMAND_TIMEOUT_MS = 120_000L

        /** Текст файла в UTF-8 или `null`, если файл двоичный. */
        fun textOf(bytes: ByteArray): String? =
            if (bytes.take(8_000).any { it == 0.toByte() }) null else bytes.decodeToString()
    }
}

/** Простая построчная разница «было/стало» для подтверждения правок: общие строки по краям не показываются. */
object LineDiff {
    fun added(content: String): String = content.lines().joinToString("\n") { "+ $it" }

    fun of(old: String, new: String): String {
        val a = old.lines()
        val b = new.lines()
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val removed = a.subList(prefix, a.size - suffix).map { "- $it" }
        val added = b.subList(prefix, b.size - suffix).map { "+ $it" }
        val header = if (prefix > 0) listOf("@@ line ${prefix + 1}") else emptyList()
        return (header + removed + added).joinToString("\n").ifEmpty { "(no changes)" }
    }
}
