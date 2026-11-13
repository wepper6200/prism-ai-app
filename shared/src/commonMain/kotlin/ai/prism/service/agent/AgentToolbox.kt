package ai.prism.service.agent

import ai.prism.domain.agent.AgentStep
import ai.prism.domain.agent.AgentStepStatus
import ai.prism.domain.agent.AgentTool
import ai.prism.core.currentTimeMillis
import ai.prism.domain.agent.CommandRunner
import ai.prism.domain.agent.Computer
import ai.prism.domain.agent.PendingAction
import ai.prism.domain.agent.RunningTool
import ai.prism.domain.agent.Shell
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
 * (если включён), команды [commands] и компьютер [computer] (Desktop, если включён «Компьютер»: файлы
 * по полному пути, сведения о системе, запуск программ). Изменения файлов в папке проекта и скачивание требуют
 * разрешения [approve], если не включено автоприменение [autoApplyEdits]; команды, программы и любые действия
 * с файлами вне папки проекта — всегда (разрешённое чтение папки действует до конца ответа).
 */
class AgentToolbox(
    private val workspace: Workspace?,
    private val web: WebClient?,
    private val commands: CommandRunner,
    private val autoApplyEdits: Boolean,
    /** На сколько уровней `list_files` раскрывает вложенные папки; 0 — без ограничения (до [MAX_ENTRIES] строк). */
    private val folderDepth: Int = AppSettings.DEFAULT_FOLDER_DEPTH,
    private val computer: Computer? = null,
    /** Ход долгой команды: последние строки вывода; `null` — команда закончилась. */
    private val onRunning: (RunningTool?) -> Unit = {},
    private val approve: suspend (PendingAction) -> Boolean,
    private val newId: () -> String,
) {
    /** Инструменты, доступные в этом диалоге. */
    val tools: List<AgentTool> = AgentTool.entries.filter { tool ->
        (!tool.needsInternet || web != null) &&
            (!tool.needsWorkspace || workspace != null || computer != null) &&
            (!tool.needsComputer || computer != null) &&
            (tool != AgentTool.RUN_COMMAND || (commands.isSupported && (workspace?.localPath != null || computer != null)))
    }

    /** Папки вне проекта, которые пользователь разрешил читать в этом ответе (полные пути). */
    private val readableFolders = mutableSetOf<String>()

    suspend fun execute(call: ToolCall): ToolOutcome {
        val tool = AgentTool.byName(call.name)?.takeIf { it in tools }
            ?: return ToolOutcome("error: unknown tool \"${call.name}\". Available: ${tools.joinToString { it.toolName }}", null)
        return try {
            when (tool) {
                AgentTool.WEB_SEARCH -> webSearch(call.arg("query"))
                AgentTool.FETCH_PAGE -> fetchPage(call.arg("url"))
                AgentTool.DOWNLOAD_FILE -> download(call.arg("url"), call.arg("path"))
                AgentTool.LIST_FILES -> listFiles(call.optional("path") ?: ".")
                AgentTool.READ_FILE -> readFile(call.arg("path"))
                AgentTool.SEARCH_FILES -> searchFiles(call.arg("query"), call.optional("path"))
                AgentTool.WRITE_FILE -> writeFile(call.arg("path"), call.args["content"].orEmpty())
                AgentTool.EDIT_FILE -> editFile(call.arg("path"), call.arg("old"), call.args["new"].orEmpty())
                AgentTool.RUN_COMMAND -> runCommand(
                    command = call.arg("command"),
                    shell = if (call.optional("shell")?.lowercase()?.startsWith("power") == true) Shell.POWERSHELL else Shell.DEFAULT,
                    folder = call.optional("folder"),
                    timeoutSeconds = call.optional("timeout")?.filter { it.isDigit() }?.toLongOrNull(),
                )
                AgentTool.SYSTEM_INFO -> systemInfo()
                AgentTool.OPEN_PROGRAM -> openProgram(call.arg("target"), call.optional("arguments").orEmpty())
            }
        } catch (e: MissingArgument) {
            ToolOutcome("error: missing argument <${e.name}>", AgentStep(tool, call.args.values.firstOrNull().orEmpty(), e.message.orEmpty(), AgentStepStatus.FAILED))
        } catch (e: WorkspaceException) {
            failed(tool, call.args["path"] ?: call.args["target"] ?: call.args.values.firstOrNull().orEmpty(), e.message.orEmpty())
        } catch (e: WebException) {
            failed(tool, call.args["url"] ?: call.args["query"].orEmpty(), e.message.orEmpty())
        }
    }

    /**
     * Где лежит [path]: в папке проекта (относительный путь или полный путь внутри неё) или на компьютере
     * (полный путь, если включён «Компьютер»).
     */
    private suspend fun target(path: String): FileTarget {
        val pc = computer
        if (pc != null && pc.isAbsolute(path)) {
            val inside = workspace?.localPath?.let { pc.relativeTo(it, path) }
            if (inside != null) return FileTarget(checkNotNull(workspace), inside, inside, outside = false)
            val located = pc.locate(path) ?: throw WorkspaceException("path not found: $path")
            return FileTarget(located.folder, located.relative, located.display, outside = true)
        }
        val folder = workspace ?: throw WorkspaceException("no project folder is open: use a full path, for example ${pc?.homePath.orEmpty()}")
        return FileTarget(folder, path, path, outside = false)
    }

    private class FileTarget(val folder: Workspace, val path: String, val display: String, val outside: Boolean)

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
        val target = target(path)
        if (!confirm(AgentTool.DOWNLOAD_FILE, target, url)) return rejected(AgentTool.DOWNLOAD_FILE, target.display)
        val bytes = checkNotNull(web).download(url, MAX_DOWNLOAD_BYTES)
        target.folder.write(target.path, bytes)
        return done(AgentTool.DOWNLOAD_FILE, target.display, "saved ${target.display} (${bytes.size} bytes)", url)
    }

    /**
     * Файлы и папки вместе с вложенными (до [folderDepth] уровней): модели не нужно открывать каждую папку
     * отдельным шагом. Служебные папки (.git, build…) показываются, но не раскрываются.
     */
    private suspend fun listFiles(path: String): ToolOutcome {
        val target = target(path)
        if (target.outside && !mayRead(target.display, folder = true)) return rejected(AgentTool.LIST_FILES, target.display)
        val folder = target.folder
        val entries = mutableListOf<WorkspaceEntry>()
        var level = folder.list(target.path)
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
                .joinToString("\n") { entry ->
                    val shown = if (target.outside) target.folderPath(entry.path) else entry.path
                    if (entry.isDirectory) "$shown/" else "$shown (${entry.sizeBytes} bytes)"
                } +
                if (truncated) "\n… (more inside; list a subfolder to see it)" else ""
        }
        return done(AgentTool.LIST_FILES, target.display, text, text.take(DETAIL_CHARS))
    }

    private suspend fun readFile(path: String): ToolOutcome {
        val target = target(path)
        if (target.outside && !mayRead(target.display, folder = false)) return rejected(AgentTool.READ_FILE, target.display)
        val text = textOf(target.folder.read(target.path))
            ?: return failed(AgentTool.READ_FILE, target.display, "binary file, cannot be read as text")
        val shown = if (text.length <= FILE_CHARS) text else text.take(FILE_CHARS) + "\n… (file truncated, ${text.length} characters total)"
        return done(AgentTool.READ_FILE, target.display, shown, shown.take(DETAIL_CHARS))
    }

    private suspend fun searchFiles(query: String, path: String?): ToolOutcome {
        val target = path?.let { target(it) }
        if (target != null && target.outside && !mayRead(target.display, folder = true)) return rejected(AgentTool.SEARCH_FILES, query)
        val folder = target?.folder ?: workspace ?: throw WorkspaceException("no project folder is open: add <path> with a full folder path")
        val hits = folder.search(query, MAX_HITS)
        val text = if (hits.isEmpty()) {
            "no matches"
        } else {
            hits.joinToString("\n") { hit ->
                val shown = if (target != null && target.outside) target.folderPath(hit.path) else hit.path
                "$shown:${hit.line}: ${hit.text.trim().take(LINE_CHARS)}"
            }
        }
        return done(AgentTool.SEARCH_FILES, query, text, text.take(DETAIL_CHARS))
    }

    private suspend fun writeFile(path: String, content: String): ToolOutcome {
        val target = target(path)
        val old = if (target.folder.exists(target.path)) textOf(target.folder.read(target.path)) else null
        val diff = if (old == null) LineDiff.added(content) else LineDiff.of(old, content)
        if (!confirm(AgentTool.WRITE_FILE, target, diff)) return rejected(AgentTool.WRITE_FILE, target.display)
        target.folder.write(target.path, content.encodeToByteArray())
        return done(AgentTool.WRITE_FILE, target.display, "written ${target.display} (${content.lines().size} lines)", diff)
    }

    private suspend fun editFile(path: String, old: String, new: String): ToolOutcome {
        val target = target(path)
        val text = textOf(target.folder.read(target.path)) ?: return failed(AgentTool.EDIT_FILE, target.display, "binary file, cannot be edited")
        val count = text.windowedCount(old)
        if (count != 1) {
            val reason = if (count == 0) "<old> text was not found in ${target.display}" else "<old> text occurs $count times in ${target.display}, add more context"
            return failed(AgentTool.EDIT_FILE, target.display, reason)
        }
        val updated = text.replaceFirst(old, new)
        val diff = LineDiff.of(old, new)
        if (!confirm(AgentTool.EDIT_FILE, target, diff)) return rejected(AgentTool.EDIT_FILE, target.display)
        target.folder.write(target.path, updated.encodeToByteArray())
        return done(AgentTool.EDIT_FILE, target.display, "edited ${target.display}", diff)
    }

    private suspend fun runCommand(command: String, shell: Shell, folder: String?, timeoutSeconds: Long?): ToolOutcome {
        val root = workspace?.localPath
        val pc = computer
        val directory = when {
            folder == null -> root ?: checkNotNull(pc).homePath
            pc != null && pc.isAbsolute(folder) -> folder
            root != null -> "$root/$folder"
            else -> throw WorkspaceException("no project folder is open: <folder> must be a full path")
        }
        val timeoutMs = (timeoutSeconds ?: DEFAULT_COMMAND_SECONDS).coerceIn(MIN_COMMAND_SECONDS, MAX_COMMAND_SECONDS) * 1000
        val shellName = if (shell == Shell.POWERSHELL) "PowerShell" else "cmd"
        val where = if (folder == null && root != null) "" else "\n$directory"
        if (!ask(AgentTool.RUN_COMMAND, command, "$shellName$where\n\n$command")) return rejected(AgentTool.RUN_COMMAND, command)
        val tail = ArrayDeque<String>()
        var shownAt = 0L
        onRunning(RunningTool(AgentTool.RUN_COMMAND, command, ""))
        val result = try {
            // Строки приходят по очереди из одного потока чтения вывода.
            commands.run(directory, command, timeoutMs, shell) { line ->
                tail.addLast(line.take(LINE_CHARS * 2))
                while (tail.size > LIVE_LINES) tail.removeFirst()
                val text = tail.joinToString("\n")
                val now = currentTimeMillis()
                if (now - shownAt >= LIVE_INTERVAL_MS) {
                    shownAt = now
                    onRunning(RunningTool(AgentTool.RUN_COMMAND, command, text))
                }
            }
        } finally {
            onRunning(null)
        }
        val output = result.output.let { if (it.length <= OUTPUT_CHARS) it else "…" + it.takeLast(OUTPUT_CHARS) }
        val status = when {
            result.timedOut -> "timed out after ${timeoutMs / 1000} s (add <timeout> in seconds for longer commands)"
            else -> "exit code ${result.exitCode}"
        }
        val text = "$status\n$output".trim()
        val ok = !result.timedOut && result.exitCode == 0
        return ToolOutcome(text, AgentStep(AgentTool.RUN_COMMAND, command, text.take(DETAIL_CHARS), if (ok) AgentStepStatus.DONE else AgentStepStatus.FAILED))
    }

    private suspend fun systemInfo(): ToolOutcome {
        val text = checkNotNull(computer).systemInfo()
        return done(AgentTool.SYSTEM_INFO, "", text, text.take(DETAIL_CHARS))
    }

    private suspend fun openProgram(target: String, arguments: String): ToolOutcome {
        val title = listOf(target, arguments).filter { it.isNotBlank() }.joinToString(" ")
        if (!ask(AgentTool.OPEN_PROGRAM, title, title)) return rejected(AgentTool.OPEN_PROGRAM, title)
        checkNotNull(computer).open(target, arguments)
        return done(AgentTool.OPEN_PROGRAM, title, "opened $title", "")
    }

    /**
     * Чтение вне папки проекта: спрашивает один раз на папку — разрешённая папка (а для файла — папка, где он лежит)
     * читается без вопросов до конца ответа.
     */
    private suspend fun mayRead(display: String, folder: Boolean): Boolean {
        if (readableFolders.any { display == it || display.startsWith("$it/") || display.startsWith("$it\\") }) return true
        val tool = if (folder) AgentTool.LIST_FILES else AgentTool.READ_FILE
        if (!approve(PendingAction(newId(), tool, display, "", outsideProject = true))) return false
        readableFolders += if (folder) display.trimEnd('/', '\\') else parentOf(display)
        return true
    }

    /** Изменение файлов: в папке проекта без вопроса, если включено автоприменение; вне её — всегда с вопросом. */
    private suspend fun confirm(tool: AgentTool, target: FileTarget, detail: String): Boolean =
        (autoApplyEdits && !target.outside) ||
            approve(PendingAction(newId(), tool, target.display, detail.take(APPROVAL_CHARS), outsideProject = target.outside))

    private suspend fun ask(tool: AgentTool, title: String, detail: String): Boolean =
        approve(PendingAction(newId(), tool, title, detail.take(APPROVAL_CHARS)))

    private fun done(tool: AgentTool, title: String, result: String, detail: String) =
        ToolOutcome(result, AgentStep(tool, title, detail, AgentStepStatus.DONE))

    private fun failed(tool: AgentTool, title: String, reason: String) =
        ToolOutcome("error: $reason", AgentStep(tool, title, reason, AgentStepStatus.FAILED))

    private fun rejected(tool: AgentTool, title: String) =
        ToolOutcome("the user rejected this action", AgentStep(tool, title, "", AgentStepStatus.REJECTED))

    private class MissingArgument(val name: String) : Exception("missing <$name>")

    private fun ToolCall.arg(name: String): String = optional(name) ?: throw MissingArgument(name)

    private fun ToolCall.optional(name: String): String? = args[name]?.trim()?.takeIf { it.isNotEmpty() }

    /** Полный путь к [relative] внутри папки цели (для файлов вне проекта модель видит полные пути). */
    private fun FileTarget.folderPath(relative: String): String {
        val separator = if ('\\' in display) '\\' else '/'
        val base = if (path == "." || path.isEmpty()) display else parentOf(display)
        return base.trimEnd('/', '\\') + separator + relative.replace('/', separator)
    }

    private fun parentOf(path: String): String {
        val cut = path.trimEnd('/', '\\').let { maxOf(it.lastIndexOf('/'), it.lastIndexOf('\\')) }
        return if (cut > 0) path.substring(0, cut) else path
    }

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
        const val DEFAULT_COMMAND_SECONDS = 120L
        const val MIN_COMMAND_SECONDS = 5L

        /** Долгие команды (сборка, установка) — до часа, если модель укажет <timeout>. */
        const val MAX_COMMAND_SECONDS = 3_600L

        /** Сколько последних строк вывода видно, пока команда работает, и как часто они обновляются. */
        const val LIVE_LINES = 12
        const val LIVE_INTERVAL_MS = 250L

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
