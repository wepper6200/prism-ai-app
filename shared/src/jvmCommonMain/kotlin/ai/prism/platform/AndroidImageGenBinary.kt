package ai.prism.platform

import java.io.File
import java.io.IOException

/**
 * Выбор сборки программы генерации изображений и видео: с i8mm (матричные инструкции int8 — у процессоров
 * последних лет, заметно быстрее) или базовая ARMv8.2. Наличие i8mm видно в строке Features файла /proc/cpuinfo.
 */
object AndroidImageGenBinary {

    private const val BASE = "libprism_imagegen.so"
    private const val I8MM = "libprism_imagegen_i8mm.so"

    fun pick(nativeLibraryDir: File, cpuInfo: () -> String = ::readCpuInfo): File {
        val fast = File(nativeLibraryDir, I8MM)
        return if (fast.isFile && hasI8mm(cpuInfo())) fast else File(nativeLibraryDir, BASE)
    }

    /** На телефонах с разными ядрами i8mm должен быть у всех: программа может оказаться на любом из них. */
    internal fun hasI8mm(cpuInfo: String): Boolean {
        val features = cpuInfo.lineSequence()
            .filter { it.trimStart().startsWith("Features") }
            .map { line -> line.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
            .toList()
        return features.isNotEmpty() && features.all { "i8mm" in it }
    }

    private fun readCpuInfo(): String = try {
        File("/proc/cpuinfo").readText()
    } catch (e: IOException) {
        ""
    }
}
