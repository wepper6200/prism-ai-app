@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package ai.prism.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** Native Apple operations supplied by the Swift application; the domain remains in shared Kotlin. */
interface IosCancellation { fun cancel() }
class IosEncodedImage(val jpeg: NSData, val rgb: NSData, val width: Int, val height: Int)
class IosPickedFile(val name: String, val data: NSData)
class IosTextPage(val page: Int, val text: String)
class IosDocumentResult(val pages: List<IosTextPage>, val error: String?)
class IosHttpResponse(val status: Int, val data: NSData, val headers: Map<String, String>)

interface IosHost {
    fun dataDirectory(): String
    fun totalMemory(): Long
    fun availableMemory(): Long
    fun processorCount(): Int
    fun freeSpace(): Long
    fun preference(key: String): String?
    fun setPreference(key: String, value: String)
    fun exists(path: String): Boolean
    fun size(path: String): Long
    fun modifiedMillis(path: String): Long
    fun children(path: String): List<String>
    fun isDirectory(path: String): Boolean
    fun canonicalPath(path: String): String
    fun resolveFolder(location: String): String?
    fun read(path: String): NSData?
    fun write(path: String, data: NSData): Boolean
    fun makeDirectory(path: String): Boolean
    fun remove(path: String): Boolean
    fun move(from: String, to: String): Boolean
    fun sha256(path: String): String?
    fun unpackArchive(path: String, destination: String): Boolean
    fun download(url: String, destination: String, resumePath: String, progress: (Long, Long) -> Unit, completion: (String?) -> Unit): IosCancellation
    fun request(url: String, method: String, headers: Map<String, String>, body: NSData?, maxBytes: Long, completion: (IosHttpResponse?, String?) -> Unit): IosCancellation
    fun encodeImage(data: NSData, maxSide: Int): IosEncodedImage?
    fun extractDocument(data: NSData, format: String): IosDocumentResult
    fun pickImages(completion: (List<NSData>) -> Unit, failure: () -> Unit)
    fun cameraAvailable(): Boolean
    fun takePhoto(completion: (NSData?) -> Unit, failure: () -> Unit)
    fun clipboardImageAvailable(): Boolean
    fun clipboardImage(): NSData?
    fun pickDocuments(completion: (List<IosPickedFile>) -> Unit, failure: () -> Unit)
    fun pickFolder(completion: (String) -> Unit)
    fun exportFile(data: NSData, name: String, completion: (Boolean) -> Unit)
    fun microphonePermission(completion: (Boolean) -> Unit)
    fun recordPcm(sampleRate: Int, samples: (NSData) -> Unit, failure: (String) -> Unit): IosCancellation
    fun playPcm(data: NSData, sampleRate: Int, completion: (String?) -> Unit): IosCancellation
}

internal lateinit var appleHost: IosHost

internal fun ByteArray.toNativeData(): NSData = usePinned {
    NSData.create(bytes = if (isEmpty()) null else it.addressOf(0), length = size.toULong())
}
internal fun NSData.toBytes(): ByteArray {
    require(length <= Int.MAX_VALUE.toULong())
    return ByteArray(length.toInt()).also { result ->
        if (result.isNotEmpty()) result.usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
}
