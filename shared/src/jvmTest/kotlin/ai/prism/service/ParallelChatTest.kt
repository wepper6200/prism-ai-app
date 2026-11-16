package ai.prism.service

import ai.prism.data.agent.FileWorkspaceProvider
import ai.prism.domain.inference.*
import ai.prism.domain.model.MessageStatus
import ai.prism.testing.FakeInferenceEngine
import ai.prism.testing.TestApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Files
import kotlin.test.*

class ParallelChatTest {
    private class Session(val sessions: MutableList<Session>) : InferenceEngine by FakeInferenceEngine() {
        private val backing = FakeInferenceEngine()
        override val state get() = backing.state
        override fun modelInfo() = backing.modelInfo()
        override suspend fun load(model: ai.prism.domain.model.CatalogModel, filePath: String, projectorPath: String?) = backing.load(model, filePath, projectorPath)
        override val supportsParallelSessions = true
        override fun forkForParallelism(parallelism: Int) = Session(sessions).also { sessions += it }
        var gate = CompletableDeferred<Unit>()
        var cancelled = false
        var unloaded = 0
        var failure: InferenceException? = null
        var tool: String? = null
        var requests = mutableListOf<GenerationRequest>()
        override fun generate(request: GenerationRequest): Flow<String> = flow {
            requests += request
            cancelled = false
            tool?.let { tool = null; emit(it); return@flow }
            gate = CompletableDeferred()
            emit("session " + sessions.indexOf(this@Session))
            gate.await()
            failure?.let { throw it }
            if (!cancelled) emit(" finished")
        }
        override fun cancel() { cancelled = true; gate.complete(Unit) }
        override suspend fun unload() { unloaded++; backing.unload() }
    }
    private class Fixture {
        val app = TestApp()
        val sessions = mutableListOf<Session>()
        val engine = Session(sessions).also { sessions += it }
        val service = ChatGenerationService(
            engine, app.chats, app.models, { AssistantPersona("", emptyList()) },
            app.images, app.imageGenerator, app.settings, app.scope,
            workspaces = FileWorkspaceProvider(Dispatchers.Unconfined), changeJournal = app.changes,
        )
    }

    @Test fun simultaneousStreamsKeepOwnHistoryAndStopOnlySelectedChat() = runBlocking {
        val f = Fixture()
        f.app.settings.setMaxParallelReplies(2)
        val a = f.app.chats.createChat().id
        val b = f.app.chats.createChat().id
        val c = f.app.chats.createChat().id
        assertTrue(f.service.send(a, "first question"))
        assertTrue(f.service.send(b, "second question"))
        assertEquals(setOf(a, b), f.service.activeGenerations.value.values.map { it.chatId }.toSet())
        assertEquals(2, f.sessions.size)
        assertEquals("first question", f.sessions[0].requests.single().messages.last().content)
        assertEquals("second question", f.sessions[1].requests.single().messages.last().content)
        assertFalse(f.service.send(a, "duplicate"))
        assertFalse(f.service.send(c, "over capacity"))
        assertFalse(f.service.draw(c, "picture"))
        assertFalse(f.service.benchmark(c, "test-model"))
        f.service.stop(a)
        assertEquals(MessageStatus.STOPPED, f.app.chats.messagesSnapshot(a).last().status)
        assertFalse(f.sessions[1].cancelled)
        assertEquals(b, f.service.activeGenerations.value.values.single().chatId)
        f.sessions[1].gate.complete(Unit)
        assertEquals(MessageStatus.COMPLETE, f.app.chats.messagesSnapshot(b).last().status)
        assertTrue(f.service.activeGenerations.value.isEmpty())
        assertEquals(1, f.sessions[1].unloaded)
        assertEquals(0, f.engine.unloaded)
    }

    @Test fun defaultLimitAndChangedLimitDoNotCancelExistingReplies() = runBlocking {
        val f = Fixture()
        val a = f.app.chats.createChat().id
        val b = f.app.chats.createChat().id
        val c = f.app.chats.createChat().id
        assertTrue(f.service.send(a, "one"))
        assertFalse(f.service.send(b, "two"))
        f.app.settings.setMaxParallelReplies(3)
        assertTrue(f.service.send(b, "two"))
        f.app.settings.setMaxParallelReplies(1)
        assertFalse(f.service.canStartChat(c))
        assertFalse(f.service.send(c, "three"))
        assertEquals(2, f.service.activeGenerations.value.size)
        f.service.releaseAll()
        assertTrue(f.service.activeGenerations.value.isEmpty())
        assertTrue(f.sessions.all { it.state.value == EngineState.Idle })
        assertTrue(f.service.send(c, "after maintenance"))
        f.service.stop(c)
    }

    @Test fun oneSessionFailureLeavesOtherAnswerRunning() = runBlocking {
        val f = Fixture()
        f.app.settings.setMaxParallelReplies(2)
        val a = f.app.chats.createChat().id
        val b = f.app.chats.createChat().id
        f.service.send(a, "one")
        f.service.send(b, "two")
        f.sessions[1].failure = InferenceException(InferenceError.NOT_ENOUGH_MEMORY, "test")
        f.sessions[1].gate.complete(Unit)
        assertEquals(MessageStatus.FAILED, f.app.chats.messagesSnapshot(b).last().status)
        assertEquals(a, f.service.activeGenerations.value.values.single().chatId)
        assertFalse(f.engine.cancelled)
        f.service.stop(a)
    }

    @Test fun permissionIsResolvedOnlyForItsOwnChat() = runBlocking {
        val f = Fixture()
        val folder = Files.createTempDirectory("prism-parallel-").toFile()
        try {
            f.app.settings.setMaxParallelReplies(2)
            val a = f.app.chats.createChat().id
            val b = f.app.chats.createChat().id
            f.app.chats.setWorkspace(a, folder.absolutePath)
            f.app.chats.setWorkspace(b, folder.absolutePath)
            f.engine.tool = "<tool name=\"write_file\"><path>a.txt</path><content>alpha</content></tool>"
            f.service.send(a, "создай файл a.txt")
            // The second session must be created before configuring its first tool reply.
            f.service.send(b, "second question")
            f.service.stop(b)
            f.sessions[1].tool = "<tool name=\"write_file\"><path>b.txt</path><content>beta</content></tool>"
            f.service.send(b, "создай файл b.txt")
            val states = f.service.activeGenerations.value.values.associateBy { it.chatId }
            val first = assertNotNull(states[a]?.pending)
            val second = assertNotNull(states[b]?.pending)
            f.service.resolvePending(second.id, true)
            assertEquals("beta", folder.resolve("b.txt").readText())
            assertFalse(folder.resolve("a.txt").exists())
            assertEquals(first.id, f.service.activeGenerations.value.values.first { it.chatId == a }.pending?.id)
            f.service.resolvePending(first.id, true)
            assertEquals("alpha", folder.resolve("a.txt").readText())
            f.service.releaseAll()
        } finally { folder.deleteRecursively() }
    }
}
