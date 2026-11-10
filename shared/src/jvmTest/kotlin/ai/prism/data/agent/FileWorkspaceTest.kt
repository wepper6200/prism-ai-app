package ai.prism.data.agent

import ai.prism.domain.agent.WorkspaceException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileWorkspaceTest {

    private val root = Files.createTempDirectory("prism-workspace").toFile()
    private val workspace = FileWorkspace(root)

    @Test
    fun filesAreWrittenReadListedAndSearched(): Unit = runBlocking {
        workspace.write("src/app/main.kt", "fun main() {\n    println(\"Привет\")\n}".encodeToByteArray())
        workspace.write("README.md", "# Проект".encodeToByteArray())
        root.resolve(".git").mkdirs()

        assertEquals(listOf("src", "README.md"), workspace.list(".").map { it.path })
        assertTrue(workspace.list("src").single().isDirectory)
        assertEquals("fun main() {\n    println(\"Привет\")\n}", workspace.read("src/app/main.kt").decodeToString())
        assertTrue(workspace.exists("src\\app\\main.kt"))
        val hit = workspace.search("привет", limit = 10).single()
        assertEquals("src/app/main.kt" to 2, hit.path to hit.line)
    }

    @Test
    fun pathsOutsideTheFolderAreRejected(): Unit = runBlocking {
        val outside = root.parentFile.resolve("prism-outside-${System.nanoTime()}.txt")
        assertFailsWith<WorkspaceException> { workspace.write("../${outside.name}", byteArrayOf(1)) }
        assertFailsWith<WorkspaceException> { workspace.read("../../etc/passwd") }
        assertFailsWith<WorkspaceException> { workspace.write(".", byteArrayOf(1)) }
        assertFalse(outside.exists())
        // Абсолютный путь считается путём внутри папки проекта.
        workspace.write("/inside.txt", "ok".encodeToByteArray())
        assertTrue(root.resolve("inside.txt").isFile)
    }
}
