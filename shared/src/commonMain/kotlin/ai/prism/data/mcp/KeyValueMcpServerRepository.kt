package ai.prism.data.mcp

import ai.prism.data.settings.KeyValueStore
import ai.prism.domain.mcp.McpServerConfig
import ai.prism.domain.mcp.McpServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** MCP-серверы пользователя — одна строка JSON в [KeyValueStore]. Повреждённое значение — серверов нет. */
class KeyValueMcpServerRepository(private val store: KeyValueStore) : McpServerRepository {
    private val state = MutableStateFlow(load())
    override val servers: StateFlow<List<McpServerConfig>> = state.asStateFlow()

    override fun save(servers: List<McpServerConfig>) {
        state.value = servers
        store.putString(KEY, json.encodeToString(serializer, servers))
    }

    private fun load(): List<McpServerConfig> {
        val raw = store.getString(KEY)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    private companion object {
        const val KEY = "mcp_servers"
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(McpServerConfig.serializer())
    }
}
