package ai.prism.domain.agent

/**
 * Инструменты ассистента. Имена — то, что модель пишет в вызове `<tool name="…">`.
 * [needsWorkspace] — работает с файлами: в папке проекта или (если включён «Компьютер») по полному пути.
 * [needsComputer] — только с включённым «Компьютер» (Desktop).
 */
enum class AgentTool(
    val toolName: String,
    val needsInternet: Boolean = false,
    val needsWorkspace: Boolean = false,
    val needsComputer: Boolean = false,
) {
    WEB_SEARCH("web_search", needsInternet = true),
    FETCH_PAGE("fetch_page", needsInternet = true),
    DOWNLOAD_FILE("download_file", needsInternet = true, needsWorkspace = true),
    LIST_FILES("list_files", needsWorkspace = true),
    READ_FILE("read_file", needsWorkspace = true),
    SEARCH_FILES("search_files", needsWorkspace = true),
    WRITE_FILE("write_file", needsWorkspace = true),
    EDIT_FILE("edit_file", needsWorkspace = true),
    RUN_COMMAND("run_command", needsWorkspace = true),
    SYSTEM_INFO("system_info", needsComputer = true),
    OPEN_PROGRAM("open_program", needsComputer = true),
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
    /** Действие с файлами вне папки проекта (на компьютере пользователя). */
    val outsideProject: Boolean = false,
)

/** Инструмент, который выполняется прямо сейчас (долгая команда): последние строки вывода. */
data class RunningTool(val tool: AgentTool, val title: String, val output: String)

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

/** Оболочка команды: [DEFAULT] — cmd на Windows (sh на других системах), [POWERSHELL] — PowerShell. */
enum class Shell { DEFAULT, POWERSHELL }

/**
 * Запуск команд (только Windows и другие настольные системы). Отмена корутины завершает процесс
 * вместе с дочерними; [onOutput] получает каждую строку вывода, пока команда работает.
 */
interface CommandRunner {
    val isSupported: Boolean

    suspend fun run(
        directory: String,
        command: String,
        timeoutMs: Long,
        shell: Shell = Shell.DEFAULT,
        onOutput: (String) -> Unit = {},
    ): CommandResult

    companion object {
        val None = object : CommandRunner {
            override val isSupported: Boolean = false

            override suspend fun run(
                directory: String,
                command: String,
                timeoutMs: Long,
                shell: Shell,
                onOutput: (String) -> Unit,
            ): CommandResult = CommandResult(exitCode = null, output = "", timedOut = false)
        }
    }
}

/**
 * Файл или папка на компьютере по полному пути: папка [folder], в которой он лежит (ближайшая существующая),
 * путь внутри неё [relative] и полный путь для показа [display].
 */
class ComputerPath(val folder: Workspace, val relative: String, val display: String)

/**
 * Компьютер пользователя за пределами папки проекта (Desktop, если включён «Компьютер»): файлы по полному пути,
 * сведения о системе и запуск программ. Чтение и изменение файлов вне папки проекта ассистент делает
 * только с разрешения пользователя.
 */
interface Computer {
    /** Домашняя папка: в ней выполняются команды, если папка проекта не выбрана. */
    val homePath: String

    /** Полный ли это путь на этом компьютере (`C:\…`, `~/…`; на Windows `/src/…` — путь в проекте). */
    fun isAbsolute(path: String): Boolean

    /** Находит файл или папку по полному пути; `null`, если путь не полный или такого диска нет. */
    suspend fun locate(path: String): ComputerPath?

    /** Лежит ли полный [path] внутри папки [root]; если да — путь относительно неё. */
    fun relativeTo(root: String, path: String): String?

    /** Система, процессор, память, диски, видеокарта, экран, основные папки пользователя. */
    suspend fun systemInfo(): String

    /** Открывает программу, файл, папку или адрес как двойной щелчок; ошибки — [WorkspaceException]. */
    suspend fun open(target: String, arguments: String)
}
