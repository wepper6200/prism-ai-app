package ai.prism.platform

import ai.prism.domain.inference.InferenceError
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.model.DeviceInfo
import ai.prism.inference.llama.NativeLibraryLoader
import java.io.File
import java.lang.management.ManagementFactory
import com.sun.management.OperatingSystemMXBean as SunOperatingSystemMXBean

class DesktopDeviceInfo : DeviceInfo {
    private val os = ManagementFactory.getOperatingSystemMXBean() as? SunOperatingSystemMXBean

    override val totalRamBytes: Long = os?.totalMemorySize ?: Runtime.getRuntime().maxMemory()

    override fun availableRamBytes(): Long = os?.freeMemorySize ?: Runtime.getRuntime().freeMemory()

    // Логические ядра обычно вдвое больше физических; инференсу выгоднее физические.
    override val inferenceThreads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 16)

    override val preferGpu: Boolean = true
}

/**
 * Ищет нативные библиотеки:
 * 1) в `-Dprism.native.dir` (тесты и разработка);
 * 2) в ресурсах приложения Compose Desktop (`compose.application.resources.dir`).
 * Зависимости загружаются явно по порядку: на Windows соседние DLL не находятся автоматически.
 */
class DesktopNativeLibraryLoader : NativeLibraryLoader {
    private val osName = System.getProperty("os.name").orEmpty().lowercase()
    private val isWindows = osName.contains("win")
    private val isMac = osName.contains("mac")

    override fun load(): String {
        val directory = sequenceOf("prism.native.dir", "compose.application.resources.dir")
            .mapNotNull { System.getProperty(it) }
            .map(::File)
            .firstOrNull { File(it, libraryFile("prism_llama")).isFile }
            ?: throw InferenceException(
                InferenceError.NATIVE_LIBRARY_MISSING,
                "Не найдены нативные библиотеки llama.cpp рядом с приложением",
            )
        val optional = if (isWindows) listOf("vcruntime140", "vcruntime140_1", "msvcp140") else emptyList()
        try {
            optional.map { File(directory, libraryFile(it)) }.filter { it.isFile }.forEach { System.load(it.absolutePath) }
            REQUIRED.forEach { name -> System.load(File(directory, libraryFile(name)).absolutePath) }
        } catch (e: UnsatisfiedLinkError) {
            throw InferenceException(InferenceError.NATIVE_LIBRARY_MISSING, "Не удалось загрузить llama.cpp: ${e.message}", e)
        }
        return directory.absolutePath
    }

    private fun libraryFile(name: String): String = when {
        isWindows -> "$name.dll"
        isMac -> "lib$name.dylib"
        else -> "lib$name.so"
    }

    companion object {
        private val REQUIRED = listOf("ggml-base", "ggml", "llama", "mtmd", "prism_llama")

        /**
         * Программа генерации изображений: в `-Dprism.imagegen.dir` (тесты), рядом с нативными библиотеками
         * или в ресурсах приложения. `null` — не собрана для этой платформы.
         */
        fun findImageGenerator(): File? {
            val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
            val name = if (windows) "prism_imagegen.exe" else "prism_imagegen"
            return sequenceOf("prism.imagegen.dir", "prism.native.dir", "compose.application.resources.dir")
                .mapNotNull { System.getProperty(it) }
                .map { File(it, name) }
                .firstOrNull { it.isFile }
        }

        fun findMusicGenerator(): File? {
            val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
            val name = if (windows) "prism_musicgen.exe" else "prism_musicgen"
            return sequenceOf("prism.musicgen.dir", "prism.native.dir", "compose.application.resources.dir")
                .mapNotNull { System.getProperty(it) }.map { File(it, name) }.firstOrNull { it.isFile }
        }
    }
}
