package ai.prism.domain.model

/** Сведения об устройстве, важные для запуска моделей. */
interface DeviceInfo {
    val totalRamBytes: Long

    fun availableRamBytes(): Long

    /** Сколько потоков отдавать инференсу. */
    val inferenceThreads: Int

    /** Пытаться ли выгружать модель на GPU (Desktop с Vulkan). */
    val preferGpu: Boolean
}
