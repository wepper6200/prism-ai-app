package ai.prism.data.chat

import ai.prism.data.db.PrismDatabase
import ai.prism.domain.model.ImageAttachment
import ai.prism.domain.model.MessageRole
import ai.prism.domain.model.MessageStatus
import ai.prism.testing.MemoryImageStore
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Старые базы обновляются до текущей версии без потери диалогов. */
class DatabaseMigrationTest {

    @Test
    fun version1DatabaseGetsImageTable() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        listOf(
            "CREATE TABLE chat (id TEXT NOT NULL PRIMARY KEY, title TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
            "CREATE TABLE message (id TEXT NOT NULL PRIMARY KEY, chat_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, " +
                "status TEXT NOT NULL, error TEXT, model_id TEXT, created_at INTEGER NOT NULL)",
            "CREATE INDEX message_by_chat ON message(chat_id, created_at)",
            "INSERT INTO chat VALUES ('c1', 'Старый чат', 1, 1)",
            "INSERT INTO message VALUES ('m1', 'c1', 'USER', 'Привет', 'COMPLETE', NULL, NULL, 1)",
        ).forEach { driver.execute(null, it, 0) }

        PrismDatabase.Schema.migrate(driver, 1, PrismDatabase.Schema.version)
        assertEquals(4L, PrismDatabase.Schema.version)

        val repository = SqlChatRepository(PrismDatabase(driver), MemoryImageStore(), Dispatchers.Unconfined)
        assertEquals(listOf("Привет"), repository.messagesSnapshot("c1").map { it.content })

        val photo = ImageAttachment("i1", "i1.jpg", 640, 480)
        repository.addMessage("c1", MessageRole.USER, "Что на фото?", MessageStatus.COMPLETE, null, listOf(photo))
        assertEquals(listOf(photo), repository.messagesSnapshot("c1").last().images)
    }

    @Test
    fun olderDatabaseGetsDocumentAndAgentTables() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        listOf(
            "CREATE TABLE chat (id TEXT NOT NULL PRIMARY KEY, title TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
            "CREATE TABLE message (id TEXT NOT NULL PRIMARY KEY, chat_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, " +
                "status TEXT NOT NULL, error TEXT, model_id TEXT, created_at INTEGER NOT NULL)",
            "INSERT INTO chat VALUES ('c1', 'Чат с фото', 1, 1)",
            "INSERT INTO message VALUES ('m1', 'c1', 'USER', 'Что на фото?', 'COMPLETE', NULL, NULL, 1)",
        ).forEach { driver.execute(null, it, 0) }
        PrismDatabase.Schema.migrate(driver, 1, 2)

        PrismDatabase.Schema.migrate(driver, 2, PrismDatabase.Schema.version)

        val database = PrismDatabase(driver)
        val repository = SqlChatRepository(database, MemoryImageStore(), Dispatchers.Unconfined)
        assertEquals(listOf("Что на фото?"), repository.messagesSnapshot("c1").map { it.content })
        assertEquals(0, database.documentQueries.selectDocumentsOfChat("c1").executeAsList().size)
        // Версия 4: папка проекта диалога и шаги ассистента.
        assertEquals(null, repository.workspaceOf("c1"))
        repository.setWorkspace("c1", "/projects/app")
        assertEquals("/projects/app", repository.workspaceOf("c1"))
        assertEquals(0, database.agentStepQueries.selectStepsOfChat("c1").executeAsList().size)
    }
}
