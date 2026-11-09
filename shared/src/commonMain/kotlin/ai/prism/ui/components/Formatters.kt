package ai.prism.ui.components

import ai.prism.domain.model.DownloadError
import ai.prism.domain.model.GenerationError
import ai.prism.resources.Res
import ai.prism.resources.decimal_separator
import ai.prism.resources.download_error_checksum
import ai.prism.resources.download_error_network
import ai.prism.resources.download_error_server
import ai.prism.resources.download_error_space
import ai.prism.resources.download_error_storage
import ai.prism.resources.error_blocked_by_safe_mode
import ai.prism.resources.error_context_full
import ai.prism.resources.error_gpu_driver_missing
import ai.prism.resources.error_image_generation_failed
import ai.prism.resources.error_image_failed
import ai.prism.resources.error_images_not_supported
import ai.prism.resources.error_interrupted
import ai.prism.resources.error_model_corrupted
import ai.prism.resources.error_model_not_downloaded
import ai.prism.resources.error_native_missing
import ai.prism.resources.error_no_image_model
import ai.prism.resources.error_no_model
import ai.prism.resources.error_not_enough_memory
import ai.prism.resources.error_unknown
import ai.prism.resources.error_vision_module_corrupted
import ai.prism.resources.size_gb
import ai.prism.resources.size_mb
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong

private const val BYTES_IN_MB = 1024L * 1024L
private const val BYTES_IN_GB = 1024L * BYTES_IN_MB

/** «2,8 ГБ», «806 МБ». */
@Composable
fun formatBytes(bytes: Long): String {
    if (bytes >= BYTES_IN_GB) {
        val tenths = (bytes.toDouble() / BYTES_IN_GB * 10).roundToLong()
        val number = "${tenths / 10}${stringResource(Res.string.decimal_separator)}${tenths % 10}"
        return stringResource(Res.string.size_gb, number)
    }
    return stringResource(Res.string.size_mb, (bytes / BYTES_IN_MB).toString())
}

/** «8K», «16K» — размер контекста. */
fun formatContext(tokens: Int): String = if (tokens >= 1024) "${tokens / 1024}K" else tokens.toString()

/** «45%». */
fun formatPercent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"

fun GenerationError.message(): StringResource = when (this) {
    GenerationError.NO_MODEL -> Res.string.error_no_model
    GenerationError.MODEL_NOT_DOWNLOADED -> Res.string.error_model_not_downloaded
    GenerationError.NOT_ENOUGH_MEMORY -> Res.string.error_not_enough_memory
    GenerationError.MODEL_CORRUPTED -> Res.string.error_model_corrupted
    GenerationError.NATIVE_LIBRARY_MISSING -> Res.string.error_native_missing
    GenerationError.CONTEXT_FULL -> Res.string.error_context_full
    GenerationError.INTERRUPTED -> Res.string.error_interrupted
    GenerationError.IMAGES_NOT_SUPPORTED -> Res.string.error_images_not_supported
    GenerationError.IMAGE_FAILED -> Res.string.error_image_failed
    GenerationError.VISION_MODULE_CORRUPTED -> Res.string.error_vision_module_corrupted
    GenerationError.NO_IMAGE_MODEL -> Res.string.error_no_image_model
    GenerationError.IMAGE_GENERATION_FAILED -> Res.string.error_image_generation_failed
    GenerationError.GPU_DRIVER_MISSING -> Res.string.error_gpu_driver_missing
    GenerationError.BLOCKED_BY_SAFE_MODE -> Res.string.error_blocked_by_safe_mode
    GenerationError.UNKNOWN -> Res.string.error_unknown
}

fun DownloadError.message(): StringResource = when (this) {
    DownloadError.NETWORK -> Res.string.download_error_network
    DownloadError.NOT_ENOUGH_SPACE -> Res.string.download_error_space
    DownloadError.CHECKSUM_MISMATCH -> Res.string.download_error_checksum
    DownloadError.STORAGE -> Res.string.download_error_storage
    DownloadError.SERVER -> Res.string.download_error_server
}
