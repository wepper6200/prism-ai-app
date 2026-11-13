package ai.prism.service

import ai.prism.domain.agent.AgentStepStatus
import ai.prism.domain.agent.AgentTool
import ai.prism.domain.mcp.McpException
import ai.prism.domain.mcp.McpServerConfig
import ai.prism.domain.mcp.McpServerStatus
import ai.prism.service.agent.ToolCallProtocol
import ai.prism.service.mcp.McpConfigParser
import ai.prism.testing.FakeInferenceEngine
import ai.prism.testing.FakeMcpServer
import ai.prism.testing.TestApp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MCP-серверы: добавление из того, что вставил пользователь, и вызов их инструментов ассистентом. */
class McpTest {

    private var ids = 0
    private fun id() = "s${++ids}"

    @Test
    fun serversAreParsedFromJsonCommandLineAndAddress() {
        val json = """
            {"mcpServers": {
              "filesystem": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "C:/Users/me/Documents"]},
              "remote": {"url": "https://mcp.example.com/mcp", "headers": {"Authorization": "Bearer x"}}
            }}
        """.trimIndent()
        val fromJson = McpConfigParser.parse(json, "", ::id)
        assertEquals(listOf("filesystem", "remote"), fromJson.map { it.name })
        assertEquals("npx", fromJson[0].command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-filesystem", "C:/Users/me/Documents"), fromJson[0].args)
        assertEquals("https://mcp.example.com/mcp", fromJson[1].url)
        assertEquals(mapOf("Authorization" to "Bearer x"), fromJson[1].headers)

        val command = McpConfigParser.parse("npx -y @modelcontextprotocol/server-filesystem \"C:/My Files\"", "", ::id).single()
        assertEquals("filesystem", command.name)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-filesystem", "C:/My Files"), command.args)

        val address = McpConfigParser.parse("https://mcp.github.com/sse", "Мой GitHub", ::id).single()
        assertEquals("Мой GitHub", address.name)
        assertTrue(address.isRemote)

        assertEquals("fetch", McpConfigParser.parse("uvx mcp-server-fetch", "", ::id).single().name)
        assertFailsWith<McpException> { McpConfigParser.parse("{не json", "", ::id) }
    }

    @Test
    fun toolNamesWithDigitsAndDashesAreParsed() {
        val call = ToolCallProtocol.parse("<tool name=\"github__search-repos2\">\n<query>prism</query>\n<per_page>5</per_page>\n</tool>")
        assertEquals("github__search-repos2", call?.name)
        assertEquals(mapOf("query" to "prism", "per_page" to "5"), call?.args)
    }

    private fun tool(name: String, readOnly: Boolean = false) = buildJsonObject {
        put("name", name)
        put("description", "Ищет репозитории\nподробности")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") { put("type", "string") }
                putJsonObject("per_page") { put("type", "integer") }
                putJsonObject("labels") { put("type", "array") }
            }
        }
        if (readOnly) putJsonObject("annotations") { put("readOnlyHint", true) }
    }

    private fun app(server: FakeMcpServer, vararg replies: String): TestApp {
        val engine = FakeInferenceEngine()
        replies.forEach { engine.queuedReplies += listOf(it) }
        return TestApp(engine = engine, mcpTransports = server)
    }

    @Test
    fun mcpToolIsCalledWithTypedArgumentsAfterApproval(): Unit = runBlocking {
        val server = FakeMcpServer(listOf(tool("search_repositories")), answer = "prism-ai-app: 12 звёзд")
        val app = app(
            server,
            "<tool name=\"github__search_repositories\">\n<query>prism</query>\n<per_page>5</per_page>\n<labels>[\"ai\"]</labels>\n</tool>",
            "Нашёл prism-ai-app.",
        )
        checkNotNull(app.mcp).add(listOf(McpServerConfig("g", "GitHub", command = "npx", args = listOf("-y", "github-mcp"))))
        val chat = app.chats.createChat()

        app.generation.send(chat.id, "Найди репозиторий prism")

        val prompt = app.engine.requests.first().systemPrompt
        assertTrue("- github__search_repositories (query, per_page, labels) — Ищет репозитории" in prompt, prompt)
        assertFalse("подробности" in prompt, "в промпт — только первая строка описания")
        val pending = assertNotNull(app.generation.active.value?.pending)
        assertEquals(AgentTool.MCP, pending.tool)
        assertEquals("GitHub: search_repositories", pending.title)
        assertTrue(server.calls.isEmpty(), "до разрешения инструмент не вызывается")
        app.generation.resolvePending(pending.id, approved = true)

        val arguments = server.calls.single()["arguments"] as JsonObject
        assertEquals(JsonPrimitive("prism"), arguments["query"])
        assertEquals(JsonPrimitive(5L), arguments["per_page"])
        assertEquals(JsonArray(listOf(JsonPrimitive("ai"))), arguments["labels"])
        assertTrue("12 звёзд" in app.engine.requests.last().messages.last().content)
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals("Нашёл prism-ai-app.", reply.content)
        assertEquals(AgentTool.MCP, reply.steps.single().tool)
        assertEquals(AgentStepStatus.DONE, reply.steps.single().status)
        assertEquals(McpServerStatus.READY, app.mcp.states.value.single().status)
        assertEquals(1, app.mcp.states.value.single().toolCount)
    }

    @Test
    fun readOnlyToolsRunWithoutAskingAndConnectionIsReused(): Unit = runBlocking {
        val server = FakeMcpServer(listOf(tool("list_issues", readOnly = true)), answer = "нет задач")
        val app = app(server, "<tool name=\"tracker__list_issues\">\n<query>open</query>\n</tool>", "Задач нет.", "Ещё раз: нет.")
        checkNotNull(app.mcp).add(listOf(McpServerConfig("t", "tracker", command = "tracker-mcp")))
        val chat = app.chats.createChat()

        app.generation.send(chat.id, "Есть открытые задачи?")
        assertNull(app.generation.active.value, "только читающий инструмент не спрашивает")
        assertEquals("Задач нет.", app.chats.messagesSnapshot(chat.id).last().content)

        app.generation.send(chat.id, "А сейчас?")
        assertEquals(1, server.opened, "подключение к серверу переиспользуется")
    }

    @Test
    fun brokenOrDisabledServersDoNotBreakTheChat(): Unit = runBlocking {
        val server = FakeMcpServer(listOf(tool("x")), failConnect = true)
        val app = app(server, "Обычный ответ.", "Снова ответ.")
        val manager = checkNotNull(app.mcp)
        manager.add(listOf(McpServerConfig("b", "broken", command = "missing-mcp")))
        val chat = app.chats.createChat()

        app.generation.send(chat.id, "Привет")
        assertEquals("Обычный ответ.", app.chats.messagesSnapshot(chat.id).last().content)
        val state = manager.states.value.single()
        assertEquals(McpServerStatus.FAILED, state.status)
        assertTrue("missing-mcp" in state.error.orEmpty())

        manager.setEnabled("b", enabled = false)
        assertEquals(McpServerStatus.OFF, manager.states.value.single().status)
        manager.add(listOf(McpServerConfig("r", "remote", url = "https://example.com/mcp")))
        assertEquals(McpServerStatus.NEEDS_INTERNET, manager.states.value.last().status)
        manager.remove("r")
        assertEquals(listOf("b"), manager.servers.value.map { it.id })
    }
}
