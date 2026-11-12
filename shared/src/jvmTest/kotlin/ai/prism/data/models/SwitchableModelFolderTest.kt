package ai.prism.data.models

import ai.prism.domain.model.ModelEntry
import ai.prism.domain.model.ModelFolderError
import ai.prism.domain.model.ModelStatus
import ai.prism.testing.FakeModelRepository
import ai.prism.data.settings.MapKeyValueStore
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

class SwitchableModelFolderTest {

    private val root = Files.createTempDirectory("prism-folder").toFile()
    private val internal = File(root, "internal/models")
    private val shared = File(root, "Download/PrismAI/models")
    private val store = MapKeyValueStore()
    private val repository = FakeModelRepository()

    private class Access(var allowed: Boolean) : SharedStorageAccess {
        var requests = 0
        override val supported = true
        override fun granted() = allowed
        override fun request() {
            requests++
        }
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun folder(access: SharedStorageAccess, storage: FileModelStorage = FileModelStorage(internal)) =
        SwitchableModelFolder(
            storage = storage,
            internalDir = internal,
            sharedDir = shared,
            sharedLabel = "Download/PrismAI",
            access = access,
            store = store,
            repository = repository,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioContext = Dispatchers.Unconfined,
        )

    private fun write(dir: File, name: String, size: Int): File =
        File(dir, name).apply { parentFile.mkdirs(); writeBytes(ByteArray(size) { (it % 7).toByte() }) }

    @Test
    fun modelsMoveToSharedFolderAndBack() {
        write(internal, "gemma.gguf", 3000)
        write(internal, "voice.tar.bz2.d/model.onnx", 500)
        val storage = FileModelStorage(internal)
        val folder = folder(Access(allowed = true), storage)

        folder.setShared(true)

        assertTrue(folder.state.value.shared)
        assertEquals(shared, storage.directory)
        assertEquals(3000, File(shared, "gemma.gguf").length())
        assertEquals(500, File(shared, "voice.tar.bz2.d/model.onnx").length())
        assertFalse(File(internal, "gemma.gguf").exists())
        assertEquals("true", store.getString(SwitchableModelFolder.KEY_SHARED))
        assertEquals(3500, folder.state.value.movedBytes)
        assertEquals(1, repository.refreshes)

        folder.setShared(false)
        assertFalse(folder.state.value.shared)
        assertEquals(3000, File(internal, "gemma.gguf").length())
        assertFalse(File(shared, "gemma.gguf").exists())
    }

    @Test
    fun afterReinstallModelsInSharedFolderAreFound() {
        write(shared, "gemma.gguf", 3000)
        val access = Access(allowed = true)
        // Настройки после переустановки пусты: стартуем с внутренней папки.
        val storage = FileModelStorage(SwitchableModelFolder.initialDirectory(store, access, internal, shared))
        assertEquals(internal, storage.directory)

        folder(access, storage).setShared(true)

        assertEquals(shared, storage.directory)
        assertEquals(3000, File(shared, "gemma.gguf").length())
        assertEquals(shared, SwitchableModelFolder.initialDirectory(store, access, internal, shared))
    }

    @Test
    fun withoutAccessTheSettingsScreenIsOpened() {
        val access = Access(allowed = false)
        val folder = folder(access)

        folder.setShared(true)

        assertEquals(1, access.requests)
        assertFalse(folder.state.value.shared)
        assertEquals(ModelFolderError.NEED_ACCESS, folder.state.value.error)
        // Доступ отобрали — при запуске модели ищутся во внутренней папке.
        store.putString(SwitchableModelFolder.KEY_SHARED, "true")
        assertEquals(internal, SwitchableModelFolder.initialDirectory(store, access, internal, shared))
    }

    @Test
    fun activeDownloadBlocksTheMove() {
        repository.models.value = listOf(ModelEntry(testModel(), ModelStatus.Downloading(10, 100), fitsInMemory = true))
        val folder = folder(Access(allowed = true))

        folder.setShared(true)

        assertEquals(ModelFolderError.DOWNLOADS_ACTIVE, folder.state.value.error)
        assertFalse(folder.state.value.shared)
        assertNull(store.getString(SwitchableModelFolder.KEY_SHARED))
    }
}
