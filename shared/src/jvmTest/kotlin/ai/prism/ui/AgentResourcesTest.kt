package ai.prism.ui

import ai.prism.domain.agent.AgentTool
import ai.prism.resources.Res
import ai.prism.resources.agent_instructions
import ai.prism.service.agent.ToolCallProtocol
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Инструкция для модели из ресурсов сохраняет разметку протокола как есть (кавычки, угловые скобки). */
class AgentResourcesTest {

    @Test
    fun instructionsKeepProtocolMarkup(): Unit = runBlocking {
        val text = getString(Res.string.agent_instructions)
        val example = text.substring(text.indexOf("<tool name=\"read_file\">"))
        assertEquals("read_file", ToolCallProtocol.parse(example)?.name, text)
        assertEquals("src/main.py", ToolCallProtocol.parse(example)?.args?.get("path"))
        assertTrue("{context}" in text && "{tools}" in text)
        val persona = ResourcePersonaProvider().persona()
        assertEquals(AgentTool.entries.toSet(), persona.toolDescriptions.keys)
        assertTrue(persona.toolDescriptions.values.all { it.isNotBlank() })
    }
}
