package ai.prism.domain.agent

/** Инструменты ассистента. Имена — то, что модель пишет в вызове `<tool name="…">`. */
enum class AgentTool(val toolName: String, val needsInternet: Boolean = false, val needsWorkspace: Boolean = false) {
    WEB_SEARCH("web_search", needsInternet = true),
    FETCH_PAGE("fetch_page", needsInternet = true),
    DOWNLOAD_FILE("download_file", needsInternet = true, needsWorkspace = true),
    LIST_FILES("list_files", needsWorkspace = true),
    READ_FILE("read_file", needsWorkspace = true),
    SEARCH_FILES("search_files", needsWorkspace = true),
    WRITE_FILE("write_file", needsWorkspace = true),
    EDIT_FILE("edit_file", needsWorkspace = true),
    RUN_COMMAND("run_command", needsWorkspace = true),
    ;

    companion object {
        fun byName(name: String): AgentTool? = entries.firstOrNull { it.toolName == name }
    }
}

enum class AgentStepStatus {
    DONE,

    /** Пользователь не разрешил действие. */
    REJECTED,
    FAILED,
}

/** Шаг работы ассистента: вызов инструмента и его итог (для показа под ответом). */
data class AgentStep(
    val tool: AgentTool,
    /** Кратко: запрос, адрес, путь или команда. */
    val title: String,
    /** Подробности: результат, разница «было/стало», вывод команды (сокращённые). */
    val detail: String,
    val status: AgentStepStatus,
)

/** Действие, которое ждёт разрешения пользователя: изменение файлов, скачивание или команда. */
data class PendingAction(
    val id: String,
    val tool: AgentTool,
    val title: String,
    val detail: String,
)

/** Файл или папка в папке проекта; [path] — относительно её корня, через `/`. */
data class WorkspaceEntry(val path: String, val isDirectory: Boolean, val sizeBytes: Long)

/** Найденная строка: файл, номер строки (с 1) и текст. */
data class WorkspaceHit(val path: String, val line: Int, val text: String)

class WorkspaceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Папка проекта, выбранная пользователем для диалога. Ассистент видит только её содержимое:
 * пути вне неё отклоняются.
 */
interface Workspace {
    /** Имя папки для интерфейса. */
    val name: String

    /** Путь в файловой системе для запуска команд или `null` (Android: доступ через Storage Access Framework). */
    val localPath: String?

    suspend fun list(path: String): List<WorkspaceEntry>

    suspend fun exists(path: String): Boolean

    suspend fun read(path: String): ByteArray

    /** Создаёт или перезаписывает файл (вместе с недостающими папками). */
    suspend fun write(path: String, bytes: ByteArray)

    /** Строки текстовых файлов, содержащие [query] (без учёта регистра). */
    suspend fun search(query: String, limit: Int): List<WorkspaceHit>
}

/** Открывает папку проекта по сохранённому адресу (путь на Desktop, URI дерева документов на Android). */
interface WorkspaceProvider {
    suspend fun open(location: String): Workspace?
}

/** Результат поиска в интернете. */
data class WebResult(val title: String, val url: String, val snippet: String)

class WebException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Доступ ассистента в интернет. Используется, только если пользователь включил «Интернет». */
interface WebClient {
    suspend fun search(query: String): List<WebResult>

    /** Текст страницы (без разметки), не длиннее [maxChars]. */
    suspend fun fetchText(url: String, maxChars: Int): String

    suspend fun download(url: String, maxBytes: Long): ByteArray
}

data class CommandResult(val exitCode: Int?, val output: String, val timedOut: Boolean)

/** Запуск команд в папке проекта (только Windows и другие настольные системы). */
interface CommandRunner {
    val isSupported: Boolean

    suspend fun run(directory: String, command: String, timeoutMs: Long): CommandResult

    companion object {
        val None = object : CommandRunner {
            override val isSupported: Boolean = false

            override suspend fun run(directory: String, command: String, timeoutMs: Long): CommandResult =
                CommandResult(exitCode = null, output = "", timedOut = false)
        }
    }
}
