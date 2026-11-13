package ai.prism.service.mcp

import ai.prism.domain.mcp.McpCallResult
import ai.prism.domain.mcp.McpException
import ai.prism.domain.mcp.McpTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Сеанс MCP поверх соединения: `initialize`, `tools/list`, `tools/call` (JSON-RPC 2.0). */
class McpSession(private val transport: McpTransport, private val clientVersion: String) {
    private val mutex = Mutex()
    private var nextId = 1L

    suspend fun initialize(timeoutMs: Long) {
        request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", JsonObject(emptyMap()))
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "Prism AI")
                        put("version", clientVersion)
                    },
                )
            },
            timeoutMs,
        )
        transport.send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }, timeoutMs)
    }

    /** Описания инструментов (все страницы списка). */
    suspend fun listTools(timeoutMs: Long): List<JsonObject> {
        val tools = mutableListOf<JsonObject>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = request("tools/list", params, timeoutMs) as? JsonObject ?: return tools
            (result["tools"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { tools += it }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull ?: return tools
        }
        return tools
    }

    suspend fun callTool(name: String, arguments: JsonObject, timeoutMs: Long): McpCallResult {
        val result = request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
            timeoutMs,
        ) as? JsonObject ?: return McpCallResult("(no result)", isError = false)
        val text = (result["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().joinToString("\n") { item ->
            when ((item["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (item["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                "image" -> "[image]"
                "audio" -> "[audio]"
                "resource" -> ((item["resource"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull ?: "[resource]"
                "resource_link" -> "[link] " + ((item["uri"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                else -> item.toString()
            }
        }.ifBlank { result["structuredContent"]?.toString().orEmpty() }
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
        return McpCallResult(text.ifBlank { "(empty result)" }, isError)
    }

    fun close() = transport.close()

    private suspend fun request(method: String, params: JsonObject, timeoutMs: Long): JsonElement = mutex.withLock {
        val id = nextId++
        val response = transport.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            },
            timeoutMs,
        ) ?: throw McpException("no answer to $method")
        val error = response["error"] as? JsonObject
        if (error != null) {
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString()
            throw McpException("$method: $message")
        }
        response["result"] ?: JsonNull
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        private const val MAX_PAGES = 20
    }
}
