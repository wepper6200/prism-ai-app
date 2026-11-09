package ai.prism.platform

import ai.prism.domain.inference.InferenceError
import ai.prism.domain.inference.InferenceException
import ai.prism.domain.model.DeviceInfo
import ai.prism.inference.llama.NativeLibraryLoader
import android.app.ActivityManager
import android.content.Context

class AndroidDeviceInfo(context: Context) : DeviceInfo {
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    private fun memoryInfo() = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }

    override val totalRamBytes: Long = memoryInfo().totalMem

    override fun availableRamBytes(): Long = memoryInfo().availMem

    // На телефонах быстрее всего работают «большие» ядра — обычно их половина.
    override val inferenceThreads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    // GPU-бэкенды на Android пока нестабильны — считаем на процессоре.
    override val preferGpu: Boolean = false
}

class AndroidNativeLibraryLoader(private val context: Context) : NativeLibraryLoader {
    override fun load(): String {
        try {
            System.loadLibrary("prism_llama")
        } catch (e: UnsatisfiedLinkError) {
            throw InferenceException(InferenceError.NATIVE_LIBRARY_MISSING, "Нет библиотеки prism_llama: ${e.message}", e)
        }
        return context.applicationInfo.nativeLibraryDir
    }
}
