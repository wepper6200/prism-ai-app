package ai.prism.data.agent

import ai.prism.domain.agent.WebClient
import ai.prism.domain.agent.WebException
import ai.prism.domain.agent.WebResult
import ai.prism.service.agent.HtmlText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/**
 * Интернет для ассистента: поиск DuckDuckGo (HTML-версия, без ключей и учётных записей),
 * чтение страниц и скачивание файлов. Запрос получает только сайт, к которому обращается ассистент;
 * приложение не добавляет к нему сведений о пользователе.
 */
class HttpWebClient(private val searchEndpoint: String = SEARCH_ENDPOINT) : WebClient {

    override suspend fun search(query: String): List<WebResult> = withContext(Dispatchers.IO) {
        val form = "q=" + URLEncoder.encode(query, Charsets.UTF_8.name()) + "&kl=wt-wt"
        val html = request(searchEndpoint, method = "POST", body = form.encodeToByteArray(), maxBytes = MAX_PAGE_BYTES)
            .decodeToString()
        HtmlText.duckDuckGoResults(html, MAX_RESULTS)
    }

    override suspend fun fetchText(url: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        val bytes = request(checkedUrl(url), method = "GET", body = null, maxBytes = MAX_PAGE_BYTES)
        val text = bytes.decodeToString()
        val looksHtml = text.trimStart().startsWith("<") || "<html" in text.take(2_000).lowercase()
        if (looksHtml) {
            val title = HtmlText.title(text)
            val body = HtmlText.toText(text, maxChars)
            if (title != null && !body.startsWith(title)) "$title\n\n$body" else body
        } else {
            if (text.length <= maxChars) text else text.take(maxChars) + "…"
        }
    }

    override suspend fun download(url: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        request(checkedUrl(url), method = "GET", body = null, maxBytes = maxBytes)
    }

    private fun checkedUrl(url: String): String {
        val uri = try {
            URI(url.trim())
        } catch (e: URISyntaxException) {
            throw WebException("invalid address: $url", e)
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) throw WebException("only http(s) addresses are allowed: $url")
        return uri.toString()
    }

    private fun request(url: String, method: String, body: ByteArray?, maxBytes: Long): ByteArray {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = try {
                URI(current).toURL().openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw WebException("cannot open $current: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw WebException("invalid address: $current", e)
            }
            try {
                connection.requestMethod = method
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept-Language", "ru,en;q=0.8")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw WebException("redirect without address ($code)")
                    current = URI(current).resolve(location).toString()
                    return@repeat
                }
                if (code !in 200..299) throw WebException("server answered $code for $current")
                val declared = connection.contentLengthLong
                if (declared > maxBytes) throw WebException("file is too large: $declared bytes")
                return connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw WebException("file is larger than ${maxBytes / (1024 * 1024)} MB")
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
            } catch (e: IOException) {
                throw WebException("network error: ${e.message}", e)
            } finally {
                connection.disconnect()
            }
        }
        throw WebException("too many redirects: $url")
    }

    private companion object {
        const val SEARCH_ENDPOINT = "https://html.duckduckgo.com/html/"
        const val USER_AGENT = "Mozilla/5.0 (compatible; PrismAI)"
        const val TIMEOUT_MS = 20_000
        const val MAX_REDIRECTS = 5
        const val MAX_RESULTS = 8
        const val MAX_PAGE_BYTES = 5L * 1024 * 1024
        const val BUFFER_SIZE = 64 * 1024
    }
}
