package ai.prism.service

import ai.prism.data.agent.FileWorkspaceProvider
import ai.prism.data.settings.MapKeyValueStore
import ai.prism.data.settings.DefaultSettingsRepository
import ai.prism.domain.agent.AgentStepStatus
import ai.prism.domain.agent.WorkspaceException
import ai.prism.domain.model.GenerationProfile
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.music.MusicStudioDraft
import ai.prism.domain.repository.ChatDraft
import ai.prism.service.agent.WorkspaceChangeJournal
import ai.prism.testing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class WorkbenchServiceTest {
    @Test
    fun preparedButUnappliedChangesCanBeClearedAfterAnInterruptedWrite(): Unit = runBlocking {
        val app = TestApp()
        val folder = Files.createTempDirectory("prism-prepared").toFile()
        folder.resolve("a.txt").writeText("old")
        app.changes.record("chat", "reply", folder.path, "a.txt", "old".encodeToByteArray(), "new".encodeToByteArray())
        app.changes.record("chat", "reply", folder.path, "new.txt", null, "new".encodeToByteArray())
        app.changes.undo("chat", "reply", FileWorkspaceProvider(Dispatchers.Unconfined))
        assertEquals("old", folder.resolve("a.txt").readText())
        assertFalse(folder.resolve("new.txt").exists())
        assertTrue(app.changes.replies.value.isEmpty())
    }

    @Test
    fun failedVerificationCannotBecomeAFalsePassOrAnotherApprovalLoop(): Unit = runBlocking {
        val engine = FakeInferenceEngine()
        val call = "<tool name=\"run_command\"><command>gradlew test</command></tool>"
        engine.queuedReplies += listOf(call)
        engine.queuedReplies += listOf("Все тесты прошли.")
        val app = TestApp(engine = engine)
        app.commands.exitCode = 1
        val chat = app.chats.createChat()
        app.chats.setWorkspace(chat.id, Files.createTempDirectory("prism-check-failed").toString())
        app.generation.verifyProject(chat.id)
        app.generation.resolvePending(assertNotNull(app.generation.active.value?.pending).id, true)
        assertEquals(1, app.commands.commands.size)
        assertEquals(AgentStepStatus.FAILED, app.chats.messagesSnapshot(chat.id).last().steps.single().status)
        assertFalse(app.chats.messagesSnapshot(chat.id).last().content.contains("Все тесты прошли"))
        assertNull(app.generation.active.value)
    }

    @Test
    fun undoSurvivesReopeningAndRestoresRepeatedWritesAndNewFiles(): Unit = runBlocking {
        val app = TestApp()
        val folder = Files.createTempDirectory("prism-undo").toFile()
        val file = folder.resolve("a.txt").apply { writeText("original") }
        val chat = app.chats.createChat()
        val reply = app.chats.addMessage(chat.id, MessageRole.ASSISTANT, "", MessageStatus.COMPLETE, null)
        val journal = app.changes
        journal.record(chat.id, reply.id, folder.path, "a.txt", file.readBytes(), "first".encodeToByteArray())
        file.writeText("first")
        journal.record(chat.id, reply.id, folder.path, "a.txt", file.readBytes(), "second".encodeToByteArray())
        file.writeText("second")
        journal.record(chat.id, reply.id, folder.path, "new.txt", null, "new".encodeToByteArray())
        folder.resolve("new.txt").writeText("new")
        val reopened = WorkspaceChangeJournal(app.database, Dispatchers.Unconfined)
        assertEquals(setOf(reply.id), reopened.replies.value)
        reopened.undo(chat.id, reply.id, FileWorkspaceProvider(Dispatchers.Unconfined))
        assertEquals("original", file.readText())
        assertFalse(folder.resolve("new.txt").exists())
        assertTrue(WorkspaceChangeJournal(app.database, Dispatchers.Unconfined).replies.value.isEmpty())
    }

    @Test
    fun conflictIsCheckedAcrossAllFilesBeforeRestoration(): Unit = runBlocking {
        val app = TestApp()
        val folder = Files.createTempDirectory("prism-conflict").toFile()
        for (name in listOf("a.txt", "b.txt")) {
            app.changes.record("chat", "reply", folder.path, name, "before".encodeToByteArray(), "agent".encodeToByteArray())
            folder.resolve(name).writeText("agent")
        }
        folder.resolve("a.txt").writeText("user changed this")
        assertFailsWith<WorkspaceException> { app.changes.undo("chat", "reply", FileWorkspaceProvider(Dispatchers.Unconfined)) }
        assertEquals("agent", folder.resolve("b.txt").readText(), "no partial restore on a known conflict")
        assertEquals("user changed this", folder.resolve("a.txt").readText())
        assertEquals(setOf("reply"), app.changes.replies.value)
    }

    @Test
    fun realEditIsVerifiedAndUndoUpdatesPersistedExecutionHistory(): Unit = runBlocking {
        val engine = FakeInferenceEngine()
        engine.queuedReplies += listOf("<tool name=\"edit_file\"><path>a.txt</path><old>old</old><new>new</new></tool>")
        engine.queuedReplies += listOf("Готово")
        val app = TestApp(engine = engine)
        app.settings.setAutoApplyEdits(true)
        val folder = Files.createTempDirectory("prism-verified").toFile()
        folder.resolve("a.txt").writeText("old")
        val chat = app.chats.createChat()
        app.chats.setWorkspace(chat.id, folder.path)
        app.generation.send(chat.id, "Измени a.txt")
        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertTrue(reply.steps.single().verified)
        assertTrue(reply.id in app.generation.workbench.value.undoReplies)
        assertTrue(app.generation.undoChanges(chat.id, reply.id))
        assertEquals("old", folder.resolve("a.txt").readText())
        assertEquals(AgentStepStatus.UNDONE, app.chats.messagesSnapshot(chat.id).last().steps.single().status)
        assertEquals(WorkbenchNotice.UNDONE, app.generation.workbench.value.notice)
    }

    @Test
    fun approvalCannotOverwriteAnInterveningUserEdit(): Unit = runBlocking {
        val engine = FakeInferenceEngine()
        engine.queuedReplies += listOf("<tool name=\"edit_file\"><path>a.txt</path><old>old</old><new>agent</new></tool>")
        val app = TestApp(engine = engine)
        val folder = Files.createTempDirectory("prism-approval-race").toFile()
        folder.resolve("a.txt").writeText("old")
        val chat = app.chats.createChat()
        app.chats.setWorkspace(chat.id, folder.path)
        app.generation.send(chat.id, "Измени a.txt")
        val pending = assertNotNull(app.generation.active.value?.pending)
        folder.resolve("a.txt").writeText("user edit")
        app.generation.resolvePending(pending.id, true)
        assertEquals("user edit", folder.resolve("a.txt").readText())
        assertEquals(AgentStepStatus.FAILED, app.chats.messagesSnapshot(chat.id).last().steps.single().status)
        assertTrue(app.generation.workbench.value.undoReplies.isEmpty())
    }

    @Test
    fun verificationRequiresAnActualApprovedCommand(): Unit = runBlocking {
        val engine = FakeInferenceEngine()
        engine.queuedReplies += listOf("Тесты уже прошли.")
        engine.queuedReplies += listOf("<tool name=\"run_command\"><command>gradlew test</command></tool>")
        engine.queuedReplies += listOf("Проверка выполнена.")
        val app = TestApp(engine = engine)
        val folder = Files.createTempDirectory("prism-check").toFile()
        val chat = app.chats.createChat()
        app.chats.setWorkspace(chat.id, folder.path)
        app.generation.verifyProject(chat.id)
        assertTrue(app.commands.commands.isEmpty())
        val pending = assertNotNull(app.generation.active.value?.pending)
        app.generation.resolvePending(pending.id, true)
        assertEquals(listOf(folder.canonicalPath to "gradlew test"), app.commands.commands)
        assertEquals("Проверка выполнена.", app.chats.messagesSnapshot(chat.id).last().content)
        assertEquals(3, engine.requests.size)
    }

    @Test
    fun benchmarkDoesNotCreateChatHistoryOrChangeActiveModelAndReleasesEngine(): Unit = runBlocking {
        val app = TestApp()
        val chat = app.chats.createChat()
        val model = app.models.models.value.first().model
        assertTrue(app.generation.benchmark(chat.id, model.id))
        val result = app.generation.workbench.value.benchmarks.single()
        assertEquals(model.id, result.modelId)
        assertTrue(result.characters > 0)
        assertTrue(app.chats.messagesSnapshot(chat.id).isEmpty())
        assertEquals(model.id, app.models.activeModelId.value)
        assertEquals(64, app.engine.lastRequest!!.maxTokens)
        assertEquals(0, app.engine.lastRequest!!.thinkingBudget)
        assertNull(app.engine.modelInfo())
        assertNull(app.generation.active.value)
    }

    @Test
    fun selectedProfilePersistsAndChangesSamplingWithoutOverridingChosenContext(): Unit = runBlocking {
        val store = MapKeyValueStore()
        DefaultSettingsRepository(store).setGenerationProfile(GenerationProfile.CODING)
        assertEquals(GenerationProfile.CODING, DefaultSettingsRepository(store).settings.value.generationProfile)
        val app = TestApp()
        app.settings.setGenerationProfile(GenerationProfile.CODING)
        app.settings.setContextLength(16384)
        val chat = app.chats.createChat()
        app.generation.send(chat.id, "Привет")
        assertEquals(GenerationProfile.CODING.sampling, app.engine.lastRequest!!.sampling)
        assertEquals(4096, app.engine.lastRequest!!.maxTokens)
        assertEquals(16384, app.settings.settings.value.contextLength)
    }

    @Test
    fun musicFieldsPersistAndLyricsStayVerbatimWithExplicitLanguage(): Unit = runBlocking {
        val draft = MusicStudioDraft(style = "piano", language = "ru", lyrics = "[Verse]\nМой неизменный текст", structure = "Verse, chorus")
        val prompt = draft.prompt("A song in English style")
        assertEquals("ru", MusicPromptPolicy.language(prompt))
        assertEquals(draft.lyrics, MusicPromptPolicy.suppliedLyrics(prompt))
        val app = TestApp()
        app.drafts.save("chat", ChatDraft(music = draft))
        assertEquals(draft, ai.prism.data.drafts.KeyValueDraftRepository(app.draftStore).draft("chat").music)
        assertFalse(app.drafts.draft("chat").isEmpty)
        assertTrue(draft.copy(instrumental = true).prompt("track").contains("no vocals"))
        assertNull(MusicPromptPolicy.suppliedLyrics(draft.copy(instrumental = true).prompt("track")))
    }
}
