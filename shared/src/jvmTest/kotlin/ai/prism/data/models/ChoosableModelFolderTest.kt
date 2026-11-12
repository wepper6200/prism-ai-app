package ai.prism.data.models

import ai.prism.data.settings.MapKeyValueStore
import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelFolderError
import ai.prism.domain.model.ModelStatus
import ai.prism.testing.FakeModelRepository
import ai.prism.testing.testModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChoosableModelFolderTest {

    private val root = Files.createTempDirectory("prism-desktop-folder").toFile()
    private val defaultDir = File(root, "AppData/PrismAI/models")
    private val otherDisk = File(root, "D/Models")
    private val store = MapKeyValueStore()
    private val repository = FakeModelRepository()
    private var unloads = 0

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun folder(storage: FileModelStorage, unavailable: Boolean = false) = ChoosableModelFolder(
        storage = storage,
        defaultDir = defaultDir,
        store = store,
        repository = repository,
        scope = CoroutineScope(Dispatchers.Unconfined),
        unavailable = unavailable,
        beforeMove = { unloads++ },
        ioContext = Dispatchers.Unconfined,
    )

    private fun write(dir: File, name: String, size: Int): File =
        File(dir, name).apply { parentFile.mkdirs(); writeBytes(ByteArray(size) { (it % 5).toByte() }) }

    @Test
    fun modelsMoveToChosenFolderAndBack() {
        write(defaultDir, "gemma.gguf", 4000)
        write(defaultDir, "voice.tar.bz2.d/model.onnx", 300)
        otherDisk.mkdirs()
        File(otherDisk, "photo.jpg").writeText("чужой файл")
        val storage = FileModelStorage(defaultDir)
        val folder = folder(storage)

        folder.choose(otherDisk.absolutePath)

        val target = File(otherDisk, ChoosableModelFolder.FOLDER_NAME)
        assertEquals(target.absoluteFile, storage.directory)
        assertEquals(4000, File(target, "gemma.gguf").length())
        assertEquals(300, File(target, "voice.tar.bz2.d/model.onnx").length())
        assertFalse(File(defaultDir, "gemma.gguf").exists())
        assertEquals(target.absolutePath, store.getString(ChoosableModelFolder.KEY_FOLDER))
        assertFalse(folder.state.value.isDefault)
        assertEquals(target.absolutePath, folder.state.value.currentPath)
        assertEquals(1, unloads)
        assertEquals(1, repository.refreshes)

        folder.resetToDefault()

        assertEquals(defaultDir.absoluteFile, storage.directory)
        assertEquals(4000, File(defaultDir, "gemma.gguf").length())
        assertTrue(folder.state.value.isDefault)
        assertEquals("", store.getString(ChoosableModelFolder.KEY_FOLDER))
        // Возвращается только своя папка — чужие файлы в выбранной папке не трогаются.
        assertTrue(File(otherDisk, "photo.jpg").isFile)
    }

    @Test
    fun choosingTheModelsFolderItselfDoesNotNestIt() {
        val target = File(otherDisk, ChoosableModelFolder.FOLDER_NAME).apply { mkdirs() }
        val storage = FileModelStorage(defaultDir)
        folder(storage).choose(target.absolutePath)
        assertEquals(target.absoluteFile, storage.directory)
    }

    @Test
    fun missingFolderFallsBackToDefaultAndIsReported() {
        store.putString(ChoosableModelFolder.KEY_FOLDER, File(root, "E/PrismAI Models").absolutePath)
        val (dir, unavailable) = ChoosableModelFolder.initialDirectory(store, defaultDir)
        assertEquals(defaultDir, dir)
        assertTrue(unavailable)
        assertEquals(ModelFolderError.FOLDER_UNAVAILABLE, folder(FileModelStorage(dir), unavailable).state.value.error)

        val chosen = File(root, "E/PrismAI Models").apply { mkdirs() }
        assertEquals(chosen to false, ChoosableModelFolder.initialDirectory(store, defaultDir))
    }

    @Test
    fun activeDownloadBlocksTheMove() {
        repository.models.value = listOf(ModelEntry(testModel(), ModelStatus.Downloading(1, 10), fitsInMemory = true))
        val storage = FileModelStorage(defaultDir)
        val folder = folder(storage)
        folder.choose(otherDisk.absolutePath)
        assertEquals(ModelFolderError.DOWNLOADS_ACTIVE, folder.state.value.error)
        assertEquals(defaultDir, storage.directory)
        assertNull(store.getString(ChoosableModelFolder.KEY_FOLDER))
        assertEquals(0, unloads)
    }
}
