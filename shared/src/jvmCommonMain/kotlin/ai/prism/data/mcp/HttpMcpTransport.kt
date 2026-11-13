package ai.prism.data.mcp

import ai.prism.domain.mcp.McpException
import ai.prism.domain.mcp.McpServerConfig
import ai.prism.domain.mcp.McpTransport
import ai.prism.domain.mcp.McpTransportFactory
import ai.prism.service.mcp.McpSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException

/**
 * Удалённый MCP-сервер (Streamable HTTP): каждое сообщение — POST, ответ — JSON или поток событий (SSE).
 * Сервер получает только вызовы инструментов, которые разрешил пользователь, и заголовки из настроек сервера.
 */
class HttpMcpTransport(private val config: McpServerConfig) : McpTransport {
    @Volatile
    private var sessionId: String? = null

    override suspend fun send(message: JsonObject, timeoutMs: Long): JsonObject? = withContext(Dispatchers.IO) {
        val connection = try {
            URI(config.url.trim()).toURL().openConnection() as HttpURLConnection
        } catch (e: URISyntaxException) {
            throw McpException("invalid address: ${config.url}", e)
        } catch (e: IllegalArgumentException) {
            throw McpException("invalid address: ${config.url}", e)
        } catch (e: IOException) {
            throw McpException("cannot connect: ${e.message}", e)
        }
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = timeoutMs.coerceIn(1_000, Int.MAX_VALUE.toLong()).toInt()
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            connection.setRequestProperty("MCP-Protocol-Version", McpSession.PROTOCOL_VERSION)
            sessionId?.let { connection.setRequestProperty("Mcp-Session-Id", it) }
            config.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.outputStream.use { it.write(message.toString().encodeToByteArray()) }
            val code = connection.responseCode
            connection.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
            if (code !in 200..299) {
                val text = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                throw McpException("server answered HTTP $code${if (text.isBlank()) "" else ": " + text.take(300)}")
            }
            val id = message["id"]
            if (id == null || code == 202 || code == 204) return@withContext null
            val reader = connection.inputStream.bufferedReader(Charsets.UTF_8)
            reader.use {
                if (connection.contentType.orEmpty().startsWith("text/event-stream")) fromEvents(it, id) else fromBody(it.readText(), id)
            }
        } catch (e: IOException) {
            throw McpException("connection error: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /** Ответ с нужным `id` из потока событий: строки `data:` до пустой строки — одно сообщение. */
    private fun fromEvents(reader: BufferedReader, id: JsonElement): JsonObject {
        val data = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
                line.isEmpty() && data.isNotEmpty() -> {
                    val message = parse(data.toString())
                    data.clear()
                    if (message != null && message["id"] == id && message["method"] == null) return message
                }
            }
        }
        if (data.isNotEmpty()) parse(data.toString())?.takeIf { it["id"] == id }?.let { return it }
        throw McpException("the server closed the stream without an answer")
    }

    private fun fromBody(text: String, id: JsonElement): JsonObject {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw McpException("invalid answer: ${text.take(200)}", e)
        }
        val messages = when (element) {
            is JsonObject -> listOf(element)
            is JsonArray -> element.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
        return messages.firstOrNull { it["id"] == id } ?: throw McpException("no answer in: ${text.take(200)}")
    }

    private fun parse(text: String): JsonObject? = try {
        json.parseToJsonElement(text.trim()) as? JsonObject
    } catch (e: SerializationException) {
        null
    }

    override fun close() {
        sessionId = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Только удалённые серверы (Android: запускать программы-серверы на телефоне нельзя). */
class HttpMcpTransportFactory : McpTransportFactory {
    override val supportsLocal: Boolean = false

    override fun open(config: McpServerConfig): McpTransport {
        if (!config.isRemote) throw McpException("local servers run only on a computer")
        return HttpMcpTransport(config)
    }
}
