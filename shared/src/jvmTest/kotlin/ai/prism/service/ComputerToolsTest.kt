package ai.prism.service

import ai.prism.domain.agent.AgentStepStatus
import ai.prism.domain.agent.AgentTool
import ai.prism.domain.agent.Shell
import ai.prism.domain.model.MessageStatus
import ai.prism.testing.FakeComputer
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

/** «Работа с компьютером»: файлы вне папки проекта, команды без проекта, сведения о системе и программы. */
class ComputerToolsTest {

    private val home: File = Files.createTempDirectory("prism-home").toFile().canonicalFile

    private fun app(vararg replies: String): TestApp {
        val engine = FakeInferenceEngine()
        replies.forEach { engine.queuedReplies += listOf(it) }
        return TestApp(engine = engine, computer = FakeComputer(home)).also { it.settings.setComputerEnabled(true) }
    }

    private fun call(name: String, vararg args: Pair<String, String>) =
        "<tool name=\"$name\">\n" + args.joinToString("") { (key, value) -> "<$key>$value</$key>\n" } + "</tool>"

    @Test
    fun computerToolsAppearOnlyWhenEnabled(): Unit = runBlocking {
        val app = app("Ответ.", "Ответ.")
        val chat = app.chats.createChat()
        app.generation.send(chat.id, "Что у меня за компьютер?")
        val prompt = app.engine.lastRequest!!.systemPrompt
        assertTrue("system_info" in prompt && "open_program" in prompt && "run_command" in prompt, prompt)
        assertTrue("Компьютер: ${home.path}" in prompt)

        app.settings.setComputerEnabled(false)
        app.generation.send(chat.id, "А сейчас?")
        assertFalse("system_info" in app.engine.lastRequest!!.systemPrompt, "без «Компьютер» и проекта инструментов нет")
    }

    @Test
    fun readingOutsideTheProjectAsksOncePerFolder(): Unit = runBlocking {
        val docs = home.resolve("Documents").apply { mkdirs() }
        docs.resolve("a.txt").writeText("первый")
        docs.resolve("b.txt").writeText("второй")
        val app = app(
            call("read_file", "path" to docs.resolve("a.txt").path),
            call("read_file", "path" to docs.resolve("b.txt").path),
            "Прочитал оба.",
        )
        val chat = app.chats.createChat()

        app.generation.send(chat.id, "Прочитай мои заметки")

        val pending = assertNotNull(app.generation.active.value?.pending)
        assertEquals(AgentTool.READ_FILE, pending.tool)
        assertTrue(pending.outsideProject)
        assertEquals(docs.resolve("a.txt").path, pending.title)
        app.generation.resolvePending(pending.id, approved = true)

        assertNull(app.generation.active.value, "вторая папка уже разрешена — второй вопрос не задаётся")
        val results = app.engine.requests.map { it.messages.last().content }
        assertTrue(results.any { "первый" in it } && results.any { "второй" in it })
        assertEquals("Прочитал оба.", app.chats.messagesSnapshot(chat.id).last().content)
    }

    @Test
    fun writingOutsideTheProjectAlwaysAsksEvenWithAutoApply(): Unit = runBlocking {
        val target = home.resolve("Desktop/заметка.txt")
        val app = app(call("write_file", "path" to target.path, "content" to "Купить хлеб"), "Готово.")
        app.settings.setAutoApplyEdits(true)
        val chat = app.chats.createChat()

        app.generation.send(chat.id, "Создай заметку на рабочем столе")

        val pending = assertNotNull(app.generation.active.value?.pending)
        assertEquals(AgentTool.WRITE_FILE, pending.tool)
        assertTrue(pending.outsideProject)
        assertFalse(target.exists())
        app.generation.resolvePending(pending.id, approved = true)
        assertEquals("Купить хлеб", target.readText())
    }

    @Test
    fun absolutePathInsideTheProjectNeedsNoReadApproval(): Unit = runBlocking {
        val project = home.resolve("project").apply { mkdirs() }
        project.resolve("main.py").writeText("print(1)")
        val app = app(call("read_file", "path" to project.resolve("main.py").path), "Это скрипт.")
        val chat = app.chats.createChat()
        app.chats.setWorkspace(chat.id, project.path)

        app.generation.send(chat.id, "Что в main.py?")

        assertNull(app.generation.active.value)
        assertTrue("print(1)" in app.engine.requests.last().messages.last().content)
    }

    @Test
    fun commandWithoutProjectRunsInHomeWithPowerShellAndShowsLiveOutput(): Unit = runBlocking {
        val app = app(call("run_command", "command" to "Get-Date", "shell" to "powershell", "timeout" to "600"), "Сегодня пятница.")
        app.commands.output = "строка 1\nстрока 2"
        var seen: String? = null
        app.commands.whileRunning = { seen = app.generation.active.value?.running?.output }
        val chat = app.chats.createChat()

        app.generation.send(chat.id, "Какое сегодня число?")
        val pending = assertNotNull(app.generation.active.value?.pending)
        assertEquals(AgentTool.RUN_COMMAND, pending.tool)
        assertTrue("PowerShell" in pending.detail && home.path in pending.detail, pending.detail)
        app.generation.resolvePending(pending.id, approved = true)

        assertEquals(listOf(home.path to "Get-Date"), app.commands.commands)
        assertEquals(listOf(Shell.POWERSHELL), app.commands.shells)
        assertEquals(listOf(600_000L), app.commands.timeouts)
        assertEquals("строка 1", seen, "пока команда работает, пользователь видит её вывод")
        assertNull(app.generation.active.value)
        assertEquals("Сегодня пятница.", app.chats.messagesSnapshot(chat.id).last().content)
    }

    @Test
    fun stopEndsALongCommand(): Unit = runBlocking {
        val app = app(call("run_command", "command" to "gradlew build"), "не должно появиться")
        app.commands.hang = true
        val chat = app.chats.createChat()

        app.generation.send(chat.id, "Собери проект")
        app.generation.resolvePending(checkNotNull(app.generation.active.value?.pending).id, approved = true)
        assertNotNull(app.generation.active.value?.running, "команда выполняется")

        app.generation.stop()

        assertTrue(app.commands.cancelled, "команда завершена")
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.STOPPED, reply.status)
        assertNull(app.generation.active.value)
    }

    @Test
    fun systemInfoAndOpeningPrograms(): Unit = runBlocking {
        val computer = FakeComputer(home)
        val engine = FakeInferenceEngine()
        listOf(call("system_info"), call("open_program", "target" to "notepad", "arguments" to "todo.txt"), "Открыл блокнот.")
            .forEach { engine.queuedReplies += listOf(it) }
        val app = TestApp(engine = engine, computer = computer).also { it.settings.setComputerEnabled(true) }
        val chat = app.chats.createChat()

        app.generation.send(chat.id, "Сколько у меня памяти? Открой блокнот")

        assertTrue("RAM: 16.0 GB" in engine.requests[1].messages.last().content, "сведения о системе без вопроса")
        val pending = assertNotNull(app.generation.active.value?.pending)
        assertEquals(AgentTool.OPEN_PROGRAM, pending.tool)
        assertTrue(computer.opened.isEmpty())
        app.generation.resolvePending(pending.id, approved = true)

        assertEquals(listOf("notepad todo.txt"), computer.opened)
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(listOf(AgentTool.SYSTEM_INFO, AgentTool.OPEN_PROGRAM), reply.steps.map { it.tool })
        assertTrue(reply.steps.all { it.status == AgentStepStatus.DONE })
    }
}
