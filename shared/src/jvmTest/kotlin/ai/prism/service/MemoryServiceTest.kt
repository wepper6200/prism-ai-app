package ai.prism.service

import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.testing.FakeInferenceEngine
import ai.prism.testing.TestApp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryServiceTest {

    private fun app(vararg replies: String): TestApp {
        val engine = FakeInferenceEngine()
        replies.forEach { engine.queuedReplies += listOf(it) }
        return TestApp(engine = engine)
    }

    @Test
    fun factsAboutTheUserAreRememberedAndUsedInOtherChats(): Unit = runBlocking {
        val app = app("Приятно познакомиться, Иван!", "- Пользователя зовут Иван\n- Пользователь пишет на Kotlin", "Привет, Иван!")
        val first = app.chats.createChat()

        app.generation.send(first.id, "Меня зовут Иван, я пишу на Kotlin")

        assertEquals(listOf("Пользователя зовут Иван", "Пользователь пишет на Kotlin"), app.memories.snapshot().map { it.text })
        val second = app.chats.createChat()
        app.generation.send(second.id, "Привет")
        val prompt = app.engine.lastRequest!!.systemPrompt
        assertTrue("О пользователе:\n- Пользователя зовут Иван" in prompt, prompt)
        assertEquals("Привет, Иван!", app.chats.messagesSnapshot(second.id).last().content)
    }

    @Test
    fun ordinaryQuestionsAndDisabledMemoryDoNotCostAnExtraGeneration(): Unit = runBlocking {
        val app = app("4", "Хорошо.")
        val chat = app.chats.createChat()
        app.generation.send(chat.id, "Сколько будет 2+2?")
        assertEquals(1, app.engine.requests.size, "без сведений о себе факты не извлекаются")

        app.settings.setMemoryEnabled(false)
        app.generation.send(chat.id, "Меня зовут Пётр")
        assertEquals(2, app.engine.requests.size)
        assertTrue(app.memories.snapshot().isEmpty())
    }

    @Test
    fun longChatStartIsSummarizedInsteadOfDropped(): Unit = runBlocking {
        val app = app("Итог: обсуждали проект Prism, выбрали Kotlin.", "Продолжаем.")
        app.settings.setContextLength(4_096)
        val chat = app.chats.createChat()
        val long = "подробности ".repeat(100)
        repeat(8) { index ->
            app.chats.addMessage(chat.id, MessageRole.USER, "Вопрос $index: $long", MessageStatus.COMPLETE, null)
            app.chats.addMessage(chat.id, MessageRole.ASSISTANT, "Ответ $index: $long", MessageStatus.COMPLETE, null)
        }

        app.generation.send(chat.id, "Что мы решили?")

        val digest = assertNotNull(app.chats.summaryOf(chat.id))
        assertEquals("Итог: обсуждали проект Prism, выбрали Kotlin.", digest.text)
        val request = app.engine.lastRequest!!
        assertTrue("Начало диалога:\nИтог: обсуждали проект Prism" in request.systemPrompt)
        assertEquals(6, request.messages.size, "в промпте только последние сообщения")
        assertEquals("Продолжаем.", app.chats.messagesSnapshot(chat.id).last().content)
    }

    @Test
    fun shortChatHasNoSummary(): Unit = runBlocking {
        val app = app("Привет!")
        val chat = app.chats.createChat()
        app.generation.send(chat.id, "Привет")
        assertNull(app.chats.summaryOf(chat.id))
    }

    @Test
    fun factsAreParsedFromTheModelAnswer() {
        assertEquals(listOf("Пользователя зовут Анна", "Живёт в Казани"), MemoryCues.parseFacts("- Пользователя зовут Анна\n• Живёт в Казани\n"))
        assertEquals(emptyList(), MemoryCues.parseFacts("нет"))
        assertTrue(MemoryCues.mayContainFacts("Запомни: я не ем мясо"))
        assertTrue(!MemoryCues.mayContainFacts("Какая погода завтра?"))
    }
}
