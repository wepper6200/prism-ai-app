package ai.prism.domain.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * MCP-сервер, добавленный пользователем: локальный ([command] и [args], запускается на компьютере, только Desktop)
 * или удалённый ([url], Streamable HTTP; работает, только если включён «Интернет»).
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String = "",
    /** Заголовки запросов к удалённому серверу (например, Authorization). */
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
) {
    val isRemote: Boolean get() = url.isNotBlank()
}

/** Инструмент MCP-сервера. [callName] — имя для модели: `сервер__инструмент` (только латиница, цифры, `_`). */
data class McpTool(
    val serverId: String,
    val serverName: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    /** Сервер пометил инструмент как только читающий (`readOnlyHint`): вызывается без вопроса. */
    val readOnly: Boolean,
) {
    val callName: String = safeName(serverName) + "__" + safeName(name)

    /** Параметры инструмента по схеме: имя и тип JSON (`string`, `number`, `object`…). */
    val parameters: List<Pair<String, String>>
        get() = (inputSchema["properties"] as? JsonObject).orEmpty().map { (key, value) ->
            key to ((value as? JsonObject)?.get("type")?.toString()?.trim('"') ?: "string")
        }

    companion object {
        fun safeName(text: String): String =
            text.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it == '_') it else '_' }.joinToString("").trim('_').ifEmpty { "mcp" }
    }
}

/** Результат вызова инструмента MCP: текст для модели и признак ошибки. */
data class McpCallResult(val text: String, val isError: Boolean)

enum class McpServerStatus {
    /** Выключен пользователем. */
    OFF,

    /** Подключится при первом ответе ассистента. */
    IDLE,

    /** Удалённый сервер: нужен включённый «Интернет». */
    NEEDS_INTERNET,

    /** Локальный сервер запускается только на компьютере. */
    DESKTOP_ONLY,
    CONNECTING,
    READY,
    FAILED,
}

/** Состояние сервера для настроек: сколько инструментов или текст ошибки. */
data class McpServerState(
    val config: McpServerConfig,
    val status: McpServerStatus,
    val toolCount: Int = 0,
    val error: String? = null,
)

class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Соединение с MCP-сервером: отправка сообщений JSON-RPC. */
interface McpTransport {
    /**
     * Отправляет сообщение. Для запроса (есть `id`) возвращает ответ с тем же `id`, для уведомления — `null`.
     * Ошибки связи — [McpException].
     */
    suspend fun send(message: JsonObject, timeoutMs: Long): JsonObject?

    fun close()
}

/** Открывает соединения: локальные серверы — только на Desktop, удалённые — везде. */
interface McpTransportFactory {
    val supportsLocal: Boolean

    fun open(config: McpServerConfig): McpTransport

    companion object {
        val None = object : McpTransportFactory {
            override val supportsLocal: Boolean = false

            override fun open(config: McpServerConfig): McpTransport = throw McpException("MCP is not supported on this device")
        }
    }
}

/** Список MCP-серверов пользователя. */
interface McpServerRepository {
    val servers: StateFlow<List<McpServerConfig>>

    fun save(servers: List<McpServerConfig>)

    companion object {
        val None = object : McpServerRepository {
            override val servers: StateFlow<List<McpServerConfig>> = MutableStateFlow(emptyList())

            override fun save(servers: List<McpServerConfig>) = Unit
        }
    }
}
