package ai.prism.service.mcp

import ai.prism.domain.mcp.McpCallResult
import ai.prism.domain.mcp.McpException
import ai.prism.domain.mcp.McpServerConfig
import ai.prism.domain.mcp.McpServerRepository
import ai.prism.domain.mcp.McpServerState
import ai.prism.domain.mcp.McpServerStatus
import ai.prism.domain.mcp.McpTool
import ai.prism.domain.mcp.McpTransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Подключения к MCP-серверам пользователя. Сервер подключается, когда он нужен ответу ассистента (или после
 * добавления — проверить), и остаётся подключённым; изменение или удаление сервера закрывает подключение.
 * Удалённые серверы работают только с включённым «Интернетом», локальные — только на компьютере.
 */
class McpManager(
    private val repository: McpServerRepository,
    private val transports: McpTransportFactory,
    private val scope: CoroutineScope,
    private val clientVersion: String,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
) {
    private class Connection(val config: McpServerConfig, val session: McpSession, val tools: List<McpTool>)

    private val mutex = Mutex()
    private val connections = mutableMapOf<String, Connection>()
    private val failures = mutableMapOf<String, String>()
    private val connecting = mutableSetOf<String>()

    /** Последнее известное значение «Интернета» — для состояния удалённых серверов в настройках. */
    private var internet = false

    private val mutableStates = MutableStateFlow(repository.servers.value.map { state(it) })
    val states: StateFlow<List<McpServerState>> = mutableStates.asStateFlow()

    val servers: StateFlow<List<McpServerConfig>> get() = repository.servers

    /** Можно ли добавлять локальные серверы (команды) на этом устройстве. */
    val supportsLocal: Boolean get() = transports.supportsLocal

    fun add(configs: List<McpServerConfig>) {
        repository.save(repository.servers.value + configs)
        refreshStates()
    }

    fun remove(id: String) {
        repository.save(repository.servers.value.filterNot { it.id == id })
        scope.launch { disconnect(id) }
        refreshStates()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        repository.save(repository.servers.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
        if (!enabled) scope.launch { disconnect(id) }
        refreshStates()
    }

    /** Подключается к включённым серверам в фоне (проверка после добавления или по кнопке). */
    fun check(internetEnabled: Boolean) {
        scope.launch { tools(internetEnabled) }
    }

    /** Инструменты всех включённых и доступных серверов; сервер с ошибкой пропускается (ошибка — в [states]). */
    suspend fun tools(internetEnabled: Boolean): List<McpTool> {
        internet = internetEnabled
        val wanted = repository.servers.value.filter { usable(it) }
        return wanted.flatMap { config -> connection(config)?.tools.orEmpty() }
    }

    /** Вызывает инструмент; ошибка связи закрывает подключение — следующий ответ подключится заново. */
    suspend fun call(tool: McpTool, arguments: JsonObject): McpCallResult {
        val connection = mutex.withLock { connections[tool.serverId] } ?: return McpCallResult("error: server ${tool.serverName} is not connected", true)
        return try {
            connection.session.callTool(tool.name, arguments, callTimeoutMs)
        } catch (e: McpException) {
            disconnect(tool.serverId)
            McpCallResult("error: ${e.message}", true)
        }
    }

    fun close() {
        scope.launch { repository.servers.value.forEach { disconnect(it.id) } }
    }

    private suspend fun connection(config: McpServerConfig): Connection? {
        mutex.withLock {
            val existing = connections[config.id]
            if (existing != null && existing.config == config) return existing
            existing?.session?.close()
            connections.remove(config.id)
            connecting += config.id
            failures.remove(config.id)
        }
        refreshStates()
        val result = try {
            val transport = transports.open(config)
            val session = McpSession(transport, clientVersion)
            try {
                session.initialize(connectTimeoutMs)
                val tools = session.listTools(connectTimeoutMs).mapNotNull { toolOf(config, it) }
                Connection(config, session, tools)
            } catch (e: McpException) {
                session.close()
                throw e
            } catch (e: CancellationException) {
                session.close()
                throw e
            }
        } catch (e: McpException) {
            mutex.withLock { failures[config.id] = e.message ?: "error" }
            null
        } finally {
            mutex.withLock { connecting -= config.id }
        }
        if (result != null) mutex.withLock { connections[config.id] = result }
        refreshStates()
        return result
    }

    private suspend fun disconnect(id: String) {
        mutex.withLock { connections.remove(id) }?.session?.close()
        refreshStates()
    }

    private fun toolOf(config: McpServerConfig, item: JsonObject): McpTool? {
        val name = (item["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val annotations = item["annotations"] as? JsonObject
        return McpTool(
            serverId = config.id,
            serverName = config.name,
            name = name,
            description = (item["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            inputSchema = item["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
            readOnly = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private fun usable(config: McpServerConfig) = config.enabled && (if (config.isRemote) internet else transports.supportsLocal)

    private fun refreshStates() {
        mutableStates.update { repository.servers.value.map { state(it) } }
    }

    private fun state(config: McpServerConfig): McpServerState {
        val connection = connections[config.id]
        return when {
            !config.enabled -> McpServerState(config, McpServerStatus.OFF)
            !config.isRemote && !transports.supportsLocal -> McpServerState(config, McpServerStatus.DESKTOP_ONLY)
            config.id in connecting -> McpServerState(config, McpServerStatus.CONNECTING)
            connection != null && connection.config == config -> McpServerState(config, McpServerStatus.READY, connection.tools.size)
            failures[config.id] != null -> McpServerState(config, McpServerStatus.FAILED, error = failures[config.id])
            config.isRemote && !internet -> McpServerState(config, McpServerStatus.NEEDS_INTERNET)
            else -> McpServerState(config, McpServerStatus.IDLE)
        }
    }

    private companion object {
        /** Первый запуск через npx/uvx скачивает пакет — подключение может занять до минуты и больше. */
        const val CONNECT_TIMEOUT_MS = 120_000L
        const val CALL_TIMEOUT_MS = 300_000L
    }
}
