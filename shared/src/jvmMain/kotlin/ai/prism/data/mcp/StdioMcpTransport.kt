package ai.prism.data.mcp

import ai.prism.data.agent.ProcessCommandRunner
import ai.prism.domain.mcp.McpException
import ai.prism.domain.mcp.McpServerConfig
import ai.prism.domain.mcp.McpTransport
import ai.prism.domain.mcp.McpTransportFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Локальный MCP-сервер: программа на компьютере (npx, uvx, python…), сообщения JSON-RPC — строки через stdin/stdout.
 * На Windows команда запускается через cmd (npx и подобные — это .cmd-файлы).
 */
class StdioMcpTransport(config: McpServerConfig, windows: Boolean = ProcessCommandRunner.isWindows()) : McpTransport {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val errors = StringBuilder()
    private val process: Process

    init {
        val direct = !windows || config.command.lowercase().endsWith(".exe")
        val commandLine = if (direct) listOf(config.command) + config.args else listOf("cmd.exe", "/d", "/c", config.command) + config.args
        process = try {
            ProcessBuilder(commandLine).apply { environment().putAll(config.env) }.start()
        } catch (e: IOException) {
            throw McpException("cannot start ${config.command}: ${e.message}", e)
        }
        Thread({ readMessages() }, "prism-mcp-${config.name}").apply { isDaemon = true; start() }
        Thread({ readErrors() }, "prism-mcp-err-${config.name}").apply { isDaemon = true; start() }
    }

    override suspend fun send(message: JsonObject, timeoutMs: Long): JsonObject? {
        val id = message["id"]?.let(::key)
        val answer = id?.let { CompletableDeferred<JsonObject>().also { deferred -> pending[it] = deferred } }
        withContext(Dispatchers.IO) { write(message) }
        if (id == null || answer == null) return null
        return try {
            withTimeout(timeoutMs) { answer.await() }
        } catch (e: TimeoutCancellationException) {
            throw McpException("the server did not answer in ${timeoutMs / 1000} s${errorTail()}", e)
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        runCatching { process.outputStream.close() }
        ProcessCommandRunner.killTree(process)
        failAll("server closed")
    }

    private fun write(message: JsonObject) {
        try {
            synchronized(process) {
                val out = process.outputStream
                out.write((message.toString() + "\n").encodeToByteArray())
                out.flush()
            }
        } catch (e: IOException) {
            throw McpException("the server is not running${errorTail()}", e)
        }
    }

    private fun readMessages() {
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                // Некоторые серверы пишут в stdout и журнал — строки не JSON пропускаются.
                val message = try {
                    json.parseToJsonElement(line.trim()) as? JsonObject
                } catch (e: SerializationException) {
                    null
                } ?: return@forEachLine
                val id = message["id"]
                val method = (message["method"] as? JsonPrimitive)?.content
                when {
                    method != null && id != null -> answerServer(id, method)
                    id != null -> pending.remove(key(id))?.complete(message)
                }
            }
        } catch (e: IOException) {
            // Процесс завершён.
        }
        failAll("the server exited${errorTail()}")
    }

    /** Запросы сервера к клиенту: на ping отвечаем, остальное приложение не поддерживает. */
    private fun answerServer(id: JsonElement, method: String) {
        val reply = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            if (method == "ping") {
                put("result", JsonObject(emptyMap()))
            } else {
                putJsonObject("error") {
                    put("code", METHOD_NOT_FOUND)
                    put("message", "not supported: $method")
                }
            }
        }
        runCatching { write(reply) }
    }

    private fun readErrors() {
        try {
            process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                synchronized(errors) {
                    errors.append(line).append('\n')
                    if (errors.length > MAX_ERRORS) errors.delete(0, errors.length - MAX_ERRORS)
                }
            }
        } catch (e: IOException) {
            // Процесс завершён.
        }
    }

    private fun errorTail(): String {
        val text = synchronized(errors) { errors.toString().trim() }
        return if (text.isEmpty()) "" else ": " + text.takeLast(ERROR_TAIL)
    }

    private fun failAll(reason: String) {
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(McpException(reason)) }
    }

    private fun key(id: JsonElement): String = (id as? JsonPrimitive)?.content ?: id.toString()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val METHOD_NOT_FOUND = -32601
        const val MAX_ERRORS = 4_000
        const val ERROR_TAIL = 500
    }
}

/** Desktop: локальные серверы (команды) и удалённые (адрес). */
class DesktopMcpTransportFactory : McpTransportFactory {
    override val supportsLocal: Boolean = true

    override fun open(config: McpServerConfig): McpTransport =
        if (config.isRemote) HttpMcpTransport(config) else StdioMcpTransport(config)
}
