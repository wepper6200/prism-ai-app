package ai.prism.inference.llama

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

internal actual fun inferenceDispatcher(name: String): CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, name).apply { isDaemon = true }
}.asCoroutineDispatcher()

internal actual fun isGguf(path: String, expectedSize: Long): Boolean {
    val file = File(path)
    if (!file.isFile || file.length() != expectedSize) return false
    val magic = ByteArray(4)
    return file.inputStream().use { it.read(magic) } == 4 && magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
}
