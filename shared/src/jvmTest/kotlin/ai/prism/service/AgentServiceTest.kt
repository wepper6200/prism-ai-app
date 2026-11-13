package ai.prism.service

import ai.prism.domain.agent.AgentStepStatus
import ai.prism.domain.agent.AgentTool
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.testing.FakeInferenceEngine
import ai.prism.testing.TestApp
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentServiceTest {

    private fun app(vararg replies: String): TestApp {
        val engine = FakeInferenceEngine()
        replies.forEach { engine.queuedReplies += listOf(it) }
        return TestApp(engine = engine)
    }

    private fun projectFolder(): File = Files.createTempDirectory("prism-project").toFile()

    @Test
    fun withoutToolsTheAnswerIsPlain(): Unit = runBlocking {
        val app = app("Просто ответ.")
        val chat = app.chats.createChat()

        app.generation.send(chat.id, "Привет")

        val request = checkNotNull(app.engine.lastRequest)
        assertFalse("Инструменты" in request.systemPrompt)
        assertEquals("Просто ответ.", app.chats.messagesSnapshot(chat.id).last().content)
    }

    @Test
    fun listFilesShowsNestedFoldersInOneStep(): Unit = runBlocking {
        val folder = projectFolder()
        folder.resolve("app/src/main/java/com/prism").mkdirs()
        folder.resolve("app/src/main/java/com/prism/App.kt").writeText("class App")
        folder.resolve("build/tmp").mkdirs()
        folder.resolve("build/tmp/junk.bin").writeText("x")
        val app = app("<tool name=\"list_files\">\n<path>.</path>\n</tool>", "Нашёл App.kt.")
        val chat = app.chats.createChat()
        app.chats.setWorkspace(chat.id, folder.path)

        app.generation.send(chat.id, "Что в проекте?")

        val result = app.engine.requests.last().messages.last().content
        assertTrue("app/src/main/java/com/prism/App.kt" in result, result)
        assertTrue("junk.bin" !in result, "служебные папки не раскрываются: $result")
        assertEquals(1, app.chats.messagesSnapshot(chat.id).last().steps.size)
    }

    @Test
    fun afterStepLimitTheModelSummarizesAndUserCanContinue(): Unit = runBlocking {
        val folder = projectFolder()
        folder.resolve("main.py").writeText("print(1)\n")
        val call = "<tool name=\"read_file\">\n<path>main.py</path>\n</tool>"
        val engine = FakeInferenceEngine(reply = listOf(call))
        val app = TestApp(engine = engine)
        val chat = app.chats.createChat()
        app.chats.setWorkspace(chat.id, folder.path)

        app.generation.send(chat.id, "Прочитай всё")

        val limit = ChatGenerationService.MAX_AGENT_STEPS
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(limit, reply.steps.size)
        // Последний результат просит подвести итог; модель снова вызвала инструмент — пользователь видит подсказку.
        assertTrue(engine.requests.last().messages.last().content.endsWith("Шаги закончились."))
        assertEquals("Сделано $limit шагов.", reply.content)
        assertEquals(MessageStatus.COMPLETE, reply.status)

        // Модель, которая подводит итог, подсказки не получает.
        repeat(limit) { engine.queuedReplies += listOf(call) }
        engine.queuedReplies += listOf("Прочитал main.py, осталось написать тесты.")
        app.generation.send(chat.id, "Ещё раз")
        assertEquals("Прочитал main.py, осталось написать тесты.", app.chats.messagesSnapshot(chat.id).last().content)
    }

    @Test
    fun internetSearchIsUsedAndShownAsStep(): Unit = runBlocking {
        val app = app(
            "Сейчас поищу.\n<tool name=\"web_search\">\n<query>погода Москва</query>\n</tool>\nлишний текст",
            "В Москве +5, облачно.",
        )
        app.settings.setInternetEnabled(true)
        val chat = app.chats.createChat()

        app.generation.send(chat.id, "Какая погода в Москве?")

        assertEquals(listOf("погода Москва"), app.web.queries)
        val second = app.engine.requests.last()
        assertTrue(second.systemPrompt.contains("Интернет включён") && second.systemPrompt.contains("web_search"))
        // Вызов (без текста после него) и результат идут в промпт следующего шага.
        assertEquals(MessageRole.ASSISTANT, second.messages[second.messages.size - 2].role)
        assertTrue(second.messages[second.messages.size - 2].content.endsWith("</tool>"))
        val result = second.messages.last().content
        assertTrue(result.startsWith("<result name=\"web_search\">") && "https://weather.example/msk" in result, result)
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.COMPLETE, reply.status)
        assertEquals("Сейчас поищу.\n\nВ Москве +5, облачно.", reply.content)
        assertEquals(listOf(AgentTool.WEB_SEARCH), reply.steps.map { it.tool })
        assertEquals("погода Москва", reply.steps.single().title)
    }

    @Test
    fun fileEditWaitsForApproval(): Unit = runBlocking {
        val folder = projectFolder()
        folder.resolve("main.py").writeText("print('old')\n")
        val app = app(
            "<tool name=\"edit_file\">\n<path>main.py</path>\n<old>print('old')</old>\n<new>print('new')</new>\n</tool>",
            "Готово.",
        )
        val chat = app.chats.createChat()
        app.chats.setWorkspace(chat.id, folder.path)

        app.generation.send(chat.id, "Поменяй вывод")

        val pending = assertNotNull(app.generation.active.value?.pending)
        assertEquals(AgentTool.EDIT_FILE, pending.tool)
        assertEquals("- print('old')\n+ print('new')", pending.detail)
        assertEquals("print('old')\n", folder.resolve("main.py").readText(), "до разрешения файл не меняется")

        app.generation.resolvePending(pending.id, approved = true)

        assertEquals("print('new')\n", folder.resolve("main.py").readText())
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals("Готово.", reply.content)
        assertEquals(AgentStepStatus.DONE, reply.steps.single().status)
        assertNull(app.generation.active.value)
    }

    @Test
    fun rejectedWriteIsReportedToTheModel(): Unit = runBlocking {
        val folder = projectFolder()
        val app = app("<tool name=\"write_file\">\n<path>notes.txt</path>\n<content>текст</content>\n</tool>", "Хорошо, не буду.")
        val chat = app.chats.createChat()
        app.chats.setWorkspace(chat.id, folder.path)

        app.generation.send(chat.id, "Создай заметку")
        app.generation.resolvePending(checkNotNull(app.generation.active.value?.pending).id, approved = false)

        assertFalse(folder.resolve("notes.txt").exists())
        assertTrue("rejected" in app.engine.requests.last().messages.last().content)
        assertEquals(AgentStepStatus.REJECTED, app.chats.messagesSnapshot(chat.id).last().steps.single().status)
    }

    @Test
    fun autoApplyWritesWithoutAskingButCommandsAlwaysAsk(): Unit = runBlocking {
        val folder = projectFolder()
        val app = app(
            "<tool name=\"write_file\">\n<path>src/App.kt</path>\n<content>\nfun main() = println(1)\n</content>\n</tool>",
            "<tool name=\"run_command\">\n<command>gradlew test</command>\n</tool>",
            "Тесты прошли.",
        )
        app.settings.setAutoApplyEdits(true)
        val chat = app.chats.createChat()
        app.chats.setWorkspace(chat.id, folder.path)

        app.generation.send(chat.id, "Напиши программу и проверь")

        assertEquals("fun main() = println(1)", folder.resolve("src/App.kt").readText())
        val pending = assertNotNull(app.generation.active.value?.pending)
        assertEquals(AgentTool.RUN_COMMAND, pending.tool)
        app.generation.resolvePending(pending.id, approved = true)

        assertEquals(listOf(folder.canonicalPath to "gradlew test"), app.commands.commands)
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(listOf(AgentTool.WRITE_FILE, AgentTool.RUN_COMMAND), reply.steps.map { it.tool })
        assertEquals("Тесты прошли.", reply.content)
    }

    @Test
    fun stopWhileWaitingRejectsAndStops(): Unit = runBlocking {
        val folder = projectFolder()
        val app = app("<tool name=\"write_file\">\n<path>a.txt</path>\n<content>x</content>\n</tool>", "не должно появиться")
        val chat = app.chats.createChat()
        app.chats.setWorkspace(chat.id, folder.path)

        app.generation.send(chat.id, "Создай файл")
        app.generation.stop()

        assertFalse(folder.resolve("a.txt").exists())
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.STOPPED, reply.status)
        assertEquals(AgentStepStatus.REJECTED, reply.steps.single().status)
    }

    @Test
    fun pathOutsideProjectFails(): Unit = runBlocking {
        val folder = projectFolder()
        val app = app("<tool name=\"read_file\">\n<path>../secret.txt</path>\n</tool>", "Не получилось.")
        val chat = app.chats.createChat()
        app.chats.setWorkspace(chat.id, folder.path)

        app.generation.send(chat.id, "Прочитай")

        val step = app.chats.messagesSnapshot(chat.id).last().steps.single()
        assertEquals(AgentStepStatus.FAILED, step.status)
        assertTrue("outside" in app.engine.requests.last().messages.last().content)
    }
}
