package ai.prism.data.agent

import ai.prism.domain.agent.Workspace
import ai.prism.domain.agent.WorkspaceEntry
import ai.prism.domain.agent.WorkspaceException
import ai.prism.domain.agent.WorkspaceHit
import ai.prism.domain.agent.WorkspaceProvider
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Папка проекта на Android: дерево документов, выбранное пользователем в системном диалоге
 * (Storage Access Framework). Приложению не нужно разрешение на доступ ко всему хранилищу.
 */
class SafWorkspace(private val context: Context, private val tree: Uri) : Workspace {
    private val resolver get() = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    override val name: String = query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId))?.name
        ?: rootId.substringAfterLast(':').substringAfterLast('/').ifEmpty { rootId }
    override val localPath: String? = null

    private data class Node(val id: String, val name: String, val isDirectory: Boolean, val size: Long)

    override suspend fun list(path: String): List<WorkspaceEntry> = io {
        val node = find(path) ?: throw WorkspaceException("not found: $path")
        if (!node.isDirectory) throw WorkspaceException("not a folder: $path")
        val prefix = segments(path).joinToString("/") { it }.let { if (it.isEmpty()) "" else "$it/" }
        children(node.id)
            .filter { it.name !in FileWorkspace.IGNORED }
            .sortedWith(compareBy<Node>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { WorkspaceEntry(prefix + it.name, it.isDirectory, it.size) }
    }

    override suspend fun exists(path: String): Boolean = io { find(path) != null }

    override suspend fun read(path: String): ByteArray = io {
        val node = find(path)?.takeIf { !it.isDirectory } ?: throw WorkspaceException("file not found: $path")
        if (node.size > FileWorkspace.MAX_READ_BYTES) throw WorkspaceException("file is too large to read: $path")
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, node.id))?.use { it.readBytes() }
            ?: throw WorkspaceException("cannot open: $path")
    }

    override suspend fun write(path: String, bytes: ByteArray) = io {
        val parts = segments(path)
        if (parts.isEmpty()) throw WorkspaceException("cannot write to a folder: $path")
        var parent = rootId
        for (folder in parts.dropLast(1)) {
            val existing = children(parent).firstOrNull { it.name == folder }
            parent = when {
                existing == null -> create(parent, Document.MIME_TYPE_DIR, folder)
                existing.isDirectory -> existing.id
                else -> throw WorkspaceException("$folder is a file, not a folder")
            }
        }
        val existing = children(parent).firstOrNull { it.name == parts.last() }
        if (existing?.isDirectory == true) throw WorkspaceException("cannot write to a folder: $path")
        // Тип «application/octet-stream»: иначе система может дописать к имени своё расширение.
        val id = existing?.id ?: create(parent, "application/octet-stream", parts.last())
        resolver.openOutputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id), "wt")?.use { it.write(bytes) }
            ?: throw WorkspaceException("cannot write: $path")
        Unit
    }

    override suspend fun search(query: String, limit: Int): List<WorkspaceHit> = io {
        val hits = mutableListOf<WorkspaceHit>()
        fun walk(id: String, prefix: String) {
            for (node in children(id)) {
                if (hits.size >= limit) return
                val path = prefix + node.name
                if (node.isDirectory) {
                    if (node.name !in FileWorkspace.IGNORED) walk(node.id, "$path/")
                } else if (node.size <= FileWorkspace.MAX_SEARCH_BYTES) {
                    val bytes = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, node.id))?.use { it.readBytes() }
                        ?: continue
                    if (bytes.take(8_000).any { it == 0.toByte() }) continue
                    bytes.decodeToString().lineSequence().forEachIndexed { index, line ->
                        if (hits.size < limit && line.contains(query, ignoreCase = true)) hits += WorkspaceHit(path, index + 1, line)
                    }
                }
            }
        }
        walk(rootId, "")
        hits
    }

    private fun segments(path: String): List<String> {
        val parts = path.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw WorkspaceException("path is outside the project folder: $path")
        return parts
    }

    private fun find(path: String): Node? {
        var node = Node(rootId, name, isDirectory = true, size = 0)
        for (part in segments(path)) {
            if (!node.isDirectory) return null
            node = children(node.id).firstOrNull { it.name == part } ?: return null
        }
        return node
    }

    private fun children(id: String): List<Node> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        return resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Node(
                            id = cursor.getString(0),
                            name = cursor.getString(1).orEmpty(),
                            isDirectory = cursor.getString(2) == Document.MIME_TYPE_DIR,
                            size = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun query(uri: Uri): Node? = try {
        resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) Node(cursor.getString(0), cursor.getString(1).orEmpty(), true, 0) else null
        }
    } catch (e: SecurityException) {
        null
    }

    private fun create(parent: String, mime: String, name: String): String {
        val uri = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent), mime, name)
            ?: throw WorkspaceException("cannot create $name")
        return DocumentsContract.getDocumentId(uri)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: FileNotFoundException) {
            throw WorkspaceException("not found: ${e.message}", e)
        } catch (e: IOException) {
            throw WorkspaceException(e.message ?: "file error", e)
        } catch (e: SecurityException) {
            throw WorkspaceException("no access to the folder, choose it again", e)
        } catch (e: IllegalArgumentException) {
            throw WorkspaceException("folder is unavailable: ${e.message}", e)
        }
    }

    private companion object {
        val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
    }
}

/** Папки проектов на Android: адрес — URI дерева документов с постоянным разрешением доступа. */
class SafWorkspaceProvider(private val context: Context) : WorkspaceProvider {
    override suspend fun open(location: String): Workspace? = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(location) }.getOrNull() ?: return@withContext null
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!granted) return@withContext null
        runCatching { SafWorkspace(context, uri) }.getOrNull()
    }
}
