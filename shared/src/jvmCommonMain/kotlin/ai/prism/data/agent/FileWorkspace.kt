package ai.prism.data.agent

import ai.prism.domain.agent.Workspace
import ai.prism.domain.agent.WorkspaceEntry
import ai.prism.domain.agent.WorkspaceException
import ai.prism.domain.agent.WorkspaceHit
import ai.prism.domain.agent.WorkspaceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** Папка проекта на диске (Desktop). Пути за её пределами отклоняются. */
class FileWorkspace(root: File, private val ioContext: CoroutineContext = Dispatchers.IO) : Workspace {
    private val root: File = root.canonicalFile

    override val name: String = this.root.name.ifEmpty { this.root.path }
    override val localPath: String = this.root.path

    override suspend fun list(path: String): List<WorkspaceEntry> = io {
        val dir = resolve(path)
        if (!dir.isDirectory) throw WorkspaceException("not a folder: $path")
        dir.listFiles().orEmpty()
            .filter { it.name !in IGNORED }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { WorkspaceEntry(relative(it), it.isDirectory, if (it.isFile) it.length() else 0) }
    }

    override suspend fun exists(path: String): Boolean = io { resolve(path).exists() }

    override suspend fun read(path: String): ByteArray = io {
        val file = resolve(path)
        if (!file.isFile) throw WorkspaceException("file not found: $path")
        if (file.length() > MAX_READ_BYTES) throw WorkspaceException("file is too large to read: $path")
        file.readBytes()
    }

    override suspend fun write(path: String, bytes: ByteArray) = io {
        val file = resolve(path)
        if (file == root || file.isDirectory) throw WorkspaceException("cannot write to a folder: $path")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override suspend fun search(query: String, limit: Int): List<WorkspaceHit> = io {
        val hits = mutableListOf<WorkspaceHit>()
        root.walkTopDown()
            .onEnter { it == root || it.name !in IGNORED }
            .filter { it.isFile && it.length() <= MAX_SEARCH_BYTES }
            .forEach { file ->
                if (hits.size >= limit) return@forEach
                val bytes = file.readBytes()
                if (bytes.take(8_000).any { it == 0.toByte() }) return@forEach
                bytes.decodeToString().lineSequence().forEachIndexed { index, line ->
                    if (hits.size < limit && line.contains(query, ignoreCase = true)) {
                        hits += WorkspaceHit(relative(file), index + 1, line)
                    }
                }
            }
        hits
    }

    /** Путь внутри папки проекта; `..` и абсолютные пути за её пределы не выводят. */
    private fun resolve(path: String): File {
        val clean = path.trim().replace('\\', '/').trimStart('/').ifEmpty { "." }
        val file = File(root, clean).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) {
            throw WorkspaceException("path is outside the project folder: $path")
        }
        return file
    }

    private fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    private suspend fun <T> io(block: () -> T): T = withContext(ioContext) {
        try {
            block()
        } catch (e: IOException) {
            throw WorkspaceException(e.message ?: "file error", e)
        } catch (e: SecurityException) {
            throw WorkspaceException("no access: ${e.message}", e)
        }
    }

    companion object {
        /** Служебные папки, которые ассистент не обходит при поиске и не показывает. */
        val IGNORED = setOf(".git", ".gradle", ".idea", "node_modules", "build", ".venv", "__pycache__")
        const val MAX_READ_BYTES = 10L * 1024 * 1024
        const val MAX_SEARCH_BYTES = 1L * 1024 * 1024
    }
}

/** Папки проектов на Desktop: адрес — абсолютный путь. */
class FileWorkspaceProvider(private val ioContext: CoroutineContext = Dispatchers.IO) : WorkspaceProvider {
    override suspend fun open(location: String): FileWorkspace? =
        File(location).takeIf { it.isDirectory }?.let { FileWorkspace(it, ioContext) }
}
