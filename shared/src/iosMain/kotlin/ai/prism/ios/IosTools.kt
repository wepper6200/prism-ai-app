@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package ai.prism.ios

import ai.prism.service.agent.HtmlText
import ai.prism.domain.agent.*
import ai.prism.domain.documents.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class IosDocumentExtractor(private val host: IosHost) : DocumentTextExtractor {
    override suspend fun extract(bytes: ByteArray, format: DocumentFormat): List<TextSection> = withContext(Dispatchers.Default) {
        val result = host.extractDocument(bytes.toNativeData(), format.name)
        result.error?.let { throw DocumentException(DocumentError.UNREADABLE, it) }
        result.pages.filter { it.text.isNotBlank() }.map { TextSection(it.text, it.page.takeIf { page -> page > 0 }) }
            .takeIf { it.isNotEmpty() } ?: throw DocumentException(DocumentError.NO_TEXT, "Document has no readable text")
    }
}

internal class IosWebClient(private val host: IosHost) : WebClient {
    private suspend fun request(url: String, method: String = "GET", body: ByteArray? = null, maxBytes: Long = 5L * 1024 * 1024): ByteArray =
        suspendCancellableCoroutine { continuation ->
            if (!Regex("https?://[^/\\s]+", RegexOption.IGNORE_CASE).containsMatchIn(url) || !url.startsWith("http", true)) {
                continuation.resumeWithException(WebException("Only HTTP(S) addresses are allowed"))
                return@suspendCancellableCoroutine
            }
            val task = host.request(url, method, mapOf("User-Agent" to "Mozilla/5.0 (compatible; PrismAI)", "Content-Type" to "application/x-www-form-urlencoded"), body?.toNativeData(), maxBytes) { response, error ->
                if (continuation.isActive) {
                    if (error != null || response == null || response.status !in 200..299) continuation.resumeWithException(WebException(error ?: "HTTP ${response?.status}"))
                    else {
                        val data = response.data.toBytes()
                        if (data.size > maxBytes) continuation.resumeWithException(WebException("Response is too large")) else continuation.resume(data)
                    }
                }
            }
            continuation.invokeOnCancellation { task.cancel() }
        }
    override suspend fun deleteFile(path: String) {
        val full = resolve(path)
        if (full == root || host.isDirectory(full) || !host.remove(full)) throw WorkspaceException("Cannot delete file")
    }
    override suspend fun search(query: String): List<WebResult> {
        val encoded = buildString {
            query.encodeToByteArray().forEach { byte ->
                val value = byte.toInt() and 255
                if (value in 65..90 || value in 97..122 || value in 48..57 || value in listOf(45, 46, 95, 126)) append(value.toChar())
                else { append('%'); append(value.toString(16).padStart(2, '0')) }
            }
        }
        return HtmlText.duckDuckGoResults(request("https://html.duckduckgo.com/html/", "POST", "q=$encoded&kl=wt-wt".encodeToByteArray()).decodeToString(), 8)
    }
    override suspend fun fetchText(url: String, maxChars: Int): String = HtmlText.toText(request(url).decodeToString(), maxChars)
    override suspend fun download(url: String, maxBytes: Long): ByteArray = request(url, maxBytes = maxBytes)
}

internal class IosWorkspaceProvider(private val host: IosHost) : WorkspaceProvider {
    override suspend fun open(location: String): Workspace? = host.resolveFolder(location)?.let { root ->
        if (!host.isDirectory(root)) null else AppleWorkspace(host, host.canonicalPath(root))
    }
}

private class AppleWorkspace(private val host: IosHost, private val root: String) : Workspace {
    override val name: String get() = root.substringAfterLast('/')
    override val localPath: String get() = root
    private fun resolve(path: String): String {
        if (path.startsWith('/') || path.split('/', '\\').any { it == ".." }) throw WorkspaceException("Path is outside the selected folder")
        val resolved = host.canonicalPath("$root/$path")
        if (resolved != root && !resolved.startsWith("$root/")) throw WorkspaceException("Path is outside the selected folder")
        return resolved
    }
    override suspend fun list(path: String): List<WorkspaceEntry> {
        val directory = resolve(path)
        return host.children(directory).sorted().map { name ->
            val relative = listOf(path.trim('/'), name).filter { it.isNotEmpty() }.joinToString("/")
            val full = resolve(relative)
            WorkspaceEntry(relative, host.isDirectory(full), host.size(full).coerceAtLeast(0))
        }
    }
    override suspend fun exists(path: String): Boolean = host.exists(resolve(path))
    override suspend fun read(path: String): ByteArray {
        val full = resolve(path)
        if (host.size(full) > 32L * 1024 * 1024) throw WorkspaceException("File is too large")
        return host.read(full)?.toBytes() ?: throw WorkspaceException("Cannot read file")
    }
    override suspend fun write(path: String, bytes: ByteArray) {
        val full = resolve(path)
        if (!host.makeDirectory(full.substringBeforeLast('/')) || !host.write(full, bytes.toNativeData())) throw WorkspaceException("Cannot write file")
    }
    override suspend fun search(query: String, limit: Int): List<WorkspaceHit> {
        val found = mutableListOf<WorkspaceHit>()
        val visited = mutableSetOf<String>()
        suspend fun walk(path: String, depth: Int) {
            if (depth > 30 || found.size >= limit || !visited.add(resolve(path))) return
            for (entry in list(path)) {
                if (found.size >= limit) return
                if (entry.isDirectory) {
                    if (entry.path.substringAfterLast('/') !in setOf(".git", "build", "node_modules", ".gradle", "Pods")) walk(entry.path, depth + 1)
                } else if (entry.sizeBytes <= 1024 * 1024) {
                    val text = try { read(entry.path).decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) { continue }
                    if ('\u0000' in text) continue
                    text.lineSequence().forEachIndexed { index, line ->
                        if (found.size < limit && line.contains(query, true)) found += WorkspaceHit(entry.path, index + 1, line.take(500))
                    }
                }
            }
        }
        walk("", 0)
        return found
    }
}
