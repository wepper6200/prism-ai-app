package ai.prism.service.mcp

import ai.prism.domain.mcp.McpException
import ai.prism.domain.mcp.McpServerConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Серверы из того, что вставил пользователь: JSON из инструкции сервера (`{"mcpServers": {…}}`, `{"имя": {…}}`
 * или один сервер `{"command": …}` / `{"url": …}`), адрес `https://…` или командная строка (`npx -y пакет`).
 */
object McpConfigParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, name: String, newId: () -> String): List<McpServerConfig> {
        val input = text.trim()
        if (input.isEmpty()) throw McpException("empty")
        val servers = when {
            input.startsWith("{") -> fromJson(input, name, newId)
            input.startsWith("http://") || input.startsWith("https://") ->
                listOf(McpServerConfig(newId(), name.ifBlank { hostOf(input) }, url = input))
            else -> {
                val parts = splitCommandLine(input)
                listOf(McpServerConfig(newId(), name.ifBlank { nameFor(parts.first(), parts.drop(1)) }, command = parts.first(), args = parts.drop(1)))
            }
        }
        if (servers.isEmpty()) throw McpException("no servers found")
        return servers
    }

    private fun fromJson(input: String, name: String, newId: () -> String): List<McpServerConfig> {
        val root = try {
            json.parseToJsonElement(input) as? JsonObject
        } catch (e: SerializationException) {
            throw McpException("invalid JSON: ${e.message}", e)
        } ?: throw McpException("invalid JSON")
        val single = root["command"] != null || root["url"] != null || root["serverUrl"] != null
        if (single) return listOf(server(root, name, newId))
        val map = (root["mcpServers"] ?: root["servers"]) as? JsonObject ?: root
        return map.mapNotNull { (key, value) -> (value as? JsonObject)?.let { server(it, key, newId) } }
    }

    private fun server(entry: JsonObject, name: String, newId: () -> String): McpServerConfig {
        val url = entry.string("url") ?: entry.string("serverUrl")
        val command = entry.string("command")
        val args = (entry["args"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return when {
            !url.isNullOrBlank() -> McpServerConfig(newId(), name.ifBlank { hostOf(url) }, url = url, headers = entry.map("headers"))
            !command.isNullOrBlank() -> {
                // «command» иногда целиком с параметрами: "npx -y пакет".
                val parts = splitCommandLine(command)
                McpServerConfig(
                    id = newId(),
                    name = name.ifBlank { nameFor(parts.first(), parts.drop(1) + args) },
                    command = parts.first(),
                    args = parts.drop(1) + args,
                    env = entry.map("env"),
                )
            }
            else -> throw McpException("server \"$name\" has neither command nor url")
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()

    private fun JsonObject.map(key: String): Map<String, String> =
        (this[key] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()

    /** Части командной строки: пробелы разделяют, кавычки "…" и '…' объединяют. */
    fun splitCommandLine(line: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var inToken = false
        for (char in line.trim()) {
            when {
                quote != null && char == quote -> quote = null
                quote != null -> current.append(char)
                char == '"' || char == '\'' -> {
                    quote = char
                    inToken = true
                }
                char.isWhitespace() -> if (inToken) {
                    parts += current.toString()
                    current.clear()
                    inToken = false
                }
                else -> {
                    current.append(char)
                    inToken = true
                }
            }
        }
        if (inToken) parts += current.toString()
        if (parts.isEmpty()) throw McpException("empty command")
        return parts
    }

    /** Понятное имя: пакет для npx/uvx без области и приставок «server-», «mcp-», иначе имя программы. */
    private fun nameFor(command: String, args: List<String>): String {
        val program = command.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.').lowercase()
        val launcher = program in setOf("npx", "uvx", "bunx", "pnpx", "pipx", "docker", "node", "python", "python3", "uv")
        val target = if (launcher) args.firstOrNull { !it.startsWith("-") && it != "run" } else null
        val base = (target ?: program).replace('\\', '/').substringAfterLast('/').substringBefore('@').substringBeforeLast(".py")
        return base.removePrefix("mcp-server-").removePrefix("server-").removeSuffix("-mcp-server").removeSuffix("-mcp").removePrefix("mcp-")
            .ifBlank { program }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("www.").removePrefix("mcp.").ifBlank { "mcp" }
}
