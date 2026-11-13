package ai.prism.update

import ai.prism.data.update.GitHubAppUpdater
import ai.prism.domain.update.UpdateError
import ai.prism.domain.update.UpdateException
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Обновление на Android: APK из релиза ставится через системный установщик (PackageInstaller) поверх текущей
 * версии — данные и модели остаются, потому что все сборки подписаны одним ключом.
 */
class AndroidAppUpdater(private val context: Context) :
    GitHubAppUpdater(File(context.cacheDir, "updates"), "-android-arm64.apk") {

    override val supported: Boolean = true

    override suspend fun install(path: String, version: String) {
        val packageManager = context.packageManager
        if (!packageManager.canRequestPackageInstalls()) {
            // Разрешение «Установка неизвестных приложений» даёт только сам пользователь.
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            throw UpdateException(UpdateError.PERMISSION, "Нужно разрешить установку обновлений")
        }
        if (withContext(Dispatchers.IO) { ApkSignatures.conflicts(context, path, context.packageName) }) {
            throw UpdateException(UpdateError.SIGNATURE, "Обновление подписано другим ключом, чем установленная версия")
        }
        val installer = packageManager.packageInstaller
        val sessionId = withContext(Dispatchers.IO) {
            val apk = File(path)
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setSize(apk.length())
            val id = installer.createSession(params)
            try {
                installer.openSession(id).use { session ->
                    apk.inputStream().use { input ->
                        session.openWrite("prism.apk", 0, apk.length()).use { output ->
                            input.copyTo(output, BUFFER_SIZE)
                            session.fsync(output)
                        }
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val callback = PendingIntent.getBroadcast(
                        context, id, Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName), flags,
                    )
                    session.commit(callback.intentSender)
                }
            } catch (e: IOException) {
                installer.abandonSession(id)
                throw UpdateException(UpdateError.STORAGE, "Не удалось передать APK установщику: ${e.message}", e)
            } catch (e: SecurityException) {
                installer.abandonSession(id)
                throw UpdateException(UpdateError.INSTALL, "Установка запрещена: ${e.message}", e)
            }
            id
        }
        // После успешной установки система перезапускает приложение, сюда приходят только отмена и ошибки.
        val result = UpdateInstallReceiver.results.first { it.sessionId == sessionId }
        when (result.status) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> throw UpdateException(UpdateError.CANCELLED, "Установка отменена")
            PackageInstaller.STATUS_FAILURE_STORAGE -> throw UpdateException(UpdateError.STORAGE, result.message)
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                throw UpdateException(UpdateError.SIGNATURE, result.message)
            else -> throw UpdateException(UpdateError.INSTALL, result.message)
        }
    }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
    }
}

/** Ответ системного установщика: просит подтверждения пользователя или сообщает итог. */
class UpdateInstallReceiver : BroadcastReceiver() {

    data class Result(val sessionId: Int, val status: Int, val message: String)

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            confirmation(intent)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        results.tryEmit(Result(sessionId, status, message))
    }

    private fun confirmation(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        }

    companion object {
        internal val results = MutableSharedFlow<Result>(replay = 8)
    }
}
