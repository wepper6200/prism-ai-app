package ai.prism.service

import ai.prism.domain.documents.DocumentError
import ai.prism.domain.documents.DocumentException
import ai.prism.domain.documents.DocumentStatus
import ai.prism.domain.documents.MessageSource
import ai.prism.domain.inference.InferenceError
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.model.GenerationError
import ai.prism.domain.model.MessageStatus
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelStatus
import ai.prism.testing.FakeInferenceEngine
import ai.prism.testing.FakeModelRepository
import ai.prism.testing.FakeTextEmbedder
import ai.prism.testing.TestApp
import ai.prism.testing.testEmbeddingModel
import ai.prism.testing.testModel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentServiceTest {

    private fun app(embeddingDownloaded: Boolean = true, embedder: FakeTextEmbedder = FakeTextEmbedder()) = TestApp(
        engine = FakeInferenceEngine(reply = listOf("Ответ по файлу.")),
        models = FakeModelRepository(
            listOf(
                ModelEntry(testModel().copy(contextLength = 2048), ModelStatus.Downloaded, fitsInMemory = true),
                ModelEntry(
                    testEmbeddingModel(),
                    if (embeddingDownloaded) ModelStatus.Downloaded else ModelStatus.NotDownloaded,
                    fitsInMemory = true,
                ),
            ),
        ),
        embedder = embedder,
    )

    @Test
    fun textFileIsSplitEmbeddedAndStored(): Unit = runBlocking {
        val app = app()
        val chat = app.chats.createChat()

        val added = app.documents.add(chat.id, "правила.txt", "Про отпуск: 28 дней.\n---\nПро зарплату: два раза в месяц.".encodeToByteArray())

        val document = app.documentRepository.documentsSnapshot(chat.id).single()
        assertEquals(added.id, document.id)
        assertEquals(DocumentStatus.READY, document.status)
        assertEquals(1, document.chunkCount)
        // Фрагмент считается по шаблону модели с названием файла; модель выгружается, когда очередь пуста.
        assertEquals("title: правила | text: Про отпуск: 28 дней.\nПро зарплату: два раза в месяц.", app.embedder.requests.single())
        assertEquals(1, app.embedder.releases)
        assertTrue(app.documents.progress.value.isEmpty())
    }

    @Test
    fun problemsAreShownOnTheDocument(): Unit = runBlocking {
        val app = app()
        val chat = app.chats.createChat()

        app.documents.add(chat.id, "фото.jpg", byteArrayOf(1))
        app.extractor.error = DocumentException(DocumentError.UNREADABLE, "пароль")
        app.documents.add(chat.id, "секрет.pdf", byteArrayOf(1))
        app.extractor.error = null
        app.embedder.error = InferenceException(InferenceError.NOT_ENOUGH_MEMORY, "мало памяти")
        app.documents.add(chat.id, "большой.md", "кот".encodeToByteArray())

        assertEquals(
            listOf(DocumentError.UNSUPPORTED, DocumentError.UNREADABLE, DocumentError.NOT_ENOUGH_MEMORY),
            app.documentRepository.documentsSnapshot(chat.id).map { it.error },
        )
        assertTrue(app.documentRepository.documentsSnapshot(chat.id).all { it.status == DocumentStatus.FAILED })
    }

    @Test
    fun withoutEmbeddingModelFileIsNotIndexed(): Unit = runBlocking {
        val app = app(embeddingDownloaded = false)
        val chat = app.chats.createChat()

        app.documents.add(chat.id, "notes.txt", "кот".encodeToByteArray())

        assertEquals(DocumentError.NO_EMBEDDING_MODEL, app.documentRepository.documentsSnapshot(chat.id).single().error)
        assertTrue(app.embedder.requests.isEmpty())
    }

    @Test
    fun smallFilesGoToThePromptWholeWithSources(): Unit = runBlocking {
        val app = app()
        val chat = app.chats.createChat()
        app.documents.add(chat.id, "отчёт.pdf", "Кот Барсик весит 5 кг.\n---\nСобака Шарик весит 20 кг.".encodeToByteArray())
        val embeddedBefore = app.embedder.requests.size

        app.generation.send(chat.id, "Сколько весит кот?")

        // Файлы целиком помещаются в промпт — запрос не нужно переводить в эмбеддинг.
        assertEquals(embeddedBefore, app.embedder.requests.size)
        val prompt = checkNotNull(app.engine.lastRequest).messages.last().content
        assertEquals(
            "Фрагменты:\n[1] отчёт.pdf, с. 1\nКот Барсик весит 5 кг.\n\n[2] отчёт.pdf, с. 2\nСобака Шарик весит 20 кг.\n\nВопрос: Сколько весит кот?",
            prompt,
        )
        val messages = app.chats.messagesSnapshot(chat.id)
        // В историю попадает само сообщение, без фрагментов.
        assertEquals("Сколько весит кот?", messages.first().content)
        val reply = messages.last()
        assertEquals(MessageStatus.COMPLETE, reply.status)
        assertEquals(listOf(MessageSource("отчёт.pdf", 1), MessageSource("отчёт.pdf", 2)), reply.sources)
    }

    @Test
    fun largeFilesAreSearchedByMeaning(): Unit = runBlocking {
        val app = app()
        val chat = app.chats.createChat()
        val filler = "Общие сведения о компании. ".repeat(60)
        val text = listOf("$filler Про кота: корм дважды в день.", "$filler Про отпуск: 28 дней.", "$filler Про договор: срок год.")
            .joinToString("\n---\n")
        app.documents.add(chat.id, "handbook.pdf", text.encodeToByteArray())

        app.generation.send(chat.id, "Сколько дней отпуска?")

        assertEquals("query: Сколько дней отпуска?", app.embedder.requests.last())
        val prompt = checkNotNull(app.engine.lastRequest).messages.last().content
        assertTrue("Про отпуск" in prompt, prompt)
        assertFalse("Про кота" in prompt, prompt)
        assertTrue(MessageSource("handbook.pdf", 2) in app.chats.messagesSnapshot(chat.id).last().sources)
    }

    @Test
    fun deletedEmbeddingModelIsReported(): Unit = runBlocking {
        val app = app()
        val chat = app.chats.createChat()
        app.documents.add(chat.id, "notes.txt", "кот".encodeToByteArray())
        app.models.delete("embed-test")

        app.generation.send(chat.id, "Что в файле?")

        val reply = app.chats.messagesSnapshot(chat.id).last()
        assertEquals(MessageStatus.FAILED, reply.status)
        assertEquals(GenerationError.NO_EMBEDDING_MODEL, reply.error)
    }

    @Test
    fun removingDocumentsAndChatsCleansUp(): Unit = runBlocking {
        val app = app()
        val chat = app.chats.createChat()
        val first = app.documents.add(chat.id, "a.txt", "кот".encodeToByteArray())
        app.documents.add(chat.id, "b.txt", "собака".encodeToByteArray())

        app.documents.remove(first.id)
        assertEquals(listOf("b.txt"), app.documentRepository.documentsSnapshot(chat.id).map { it.name })

        app.chats.deleteChat(chat.id)
        assertTrue(app.documentRepository.documentsSnapshot(chat.id).isEmpty())
        assertTrue(app.documentRepository.chunks(chat.id, "embed-test").isEmpty())
    }
}
