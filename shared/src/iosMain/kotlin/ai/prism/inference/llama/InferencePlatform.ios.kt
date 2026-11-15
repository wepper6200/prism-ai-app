@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package ai.prism.inference.llama

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.rewind

internal actual fun inferenceDispatcher(name: String): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

internal actual fun isGguf(path: String, expectedSize: Long): Boolean {
    val file = fopen(path, "rb") ?: return false
    return try {
        fseek(file, 0, SEEK_END)
        if (ftell(file) != expectedSize) return false
        rewind(file)
        memScoped {
            val bytes = allocArray<UByteVar>(4)
            fread(bytes, 1u, 4u, file) == 4uL && bytes[0] == 0x47u.toUByte() && bytes[1] == 0x47u.toUByte() &&
                bytes[2] == 0x55u.toUByte() && bytes[3] == 0x46u.toUByte()
        }
    } finally { fclose(file) }
}
