@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package ai.prism.ios
import ai.prism.apple.native.*
import kotlinx.cinterop.*
import platform.Foundation.NSData
import platform.posix.size_tVar

object AppleArchives {
    fun unpack(path: String, destination: String): Boolean = prism_unpack_voice(path, destination) != 0
    fun documentXml(data: NSData): NSData? = memScoped {
        val size = alloc<size_tVar>()
        val bytes = prism_docx_xml(data.bytes, data.length, size.ptr) ?: return@memScoped null
        try { if (size.value > Int.MAX_VALUE.toULong()) null else bytes.reinterpret<ByteVar>().readBytes(size.value.toInt()).toNativeData() }
        finally { prism_free(bytes) }
    }
}
