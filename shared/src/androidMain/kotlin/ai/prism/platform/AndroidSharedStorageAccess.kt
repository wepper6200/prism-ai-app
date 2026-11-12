package ai.prism.platform

import ai.prism.data.models.SharedStorageAccess
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * «Доступ ко всем файлам» (Android 11+): модели — обычные файлы в `Download/PrismAI/models`, их открывают по пути
 * llama.cpp, stable-diffusion.cpp и sherpa-onnx, а папка остаётся после удаления приложения.
 */
class AndroidSharedStorageAccess(private val context: Context) : SharedStorageAccess {

    override val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun granted(): Boolean = supported && Environment.isExternalStorageManager()

    override fun request() {
        if (!supported) return
        val forApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(forApp)
        } catch (e: ActivityNotFoundException) {
            // Некоторые прошивки не открывают экран для одного приложения — тогда общий список.
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        /** Папка моделей, которая остаётся после удаления приложения. */
        @Suppress("DEPRECATION")
        fun sharedModelsDir(): File =
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PrismAI/models")

        const val SHARED_LABEL = "Download/PrismAI"
    }
}
