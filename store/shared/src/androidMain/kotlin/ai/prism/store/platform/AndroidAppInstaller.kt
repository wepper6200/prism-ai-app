package ai.prism.store.platform

import ai.prism.store.model.AppInstaller
import ai.prism.store.model.StoreApp
import ai.prism.store.model.StoreError
import ai.prism.store.model.StoreException
import ai.prism.store.model.StoreFile
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Android: установка через системный PackageInstaller (подтверждает пользователь), версия — из PackageManager. */
class AndroidAppInstaller(private val context: Context) : AppInstaller {

    override fun fileFor(app: StoreApp): StoreFile? = app.android?.file

    override fun installedVersion(app: StoreApp): String? {
        val packageName = app.android?.packageName ?: return null
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    override suspend fun install(app: StoreApp, path: String) {
        val packageManager = context.packageManager
        if (!packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            throw StoreException(StoreError.PERMISSION, "Нужно разрешить установку приложений")
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
                        session.openWrite("app.apk", 0, apk.length()).use { output ->
                            input.copyTo(output, BUFFER_SIZE)
                            session.fsync(output)
                        }
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val callback = PendingIntent.getBroadcast(
                        context, id, Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName), flags,
                    )
                    session.commit(callback.intentSender)
                }
            } catch (e: IOException) {
                installer.abandonSession(id)
                throw StoreException(StoreError.STORAGE, "Не удалось передать APK установщику: ${e.message}", e)
            } catch (e: SecurityException) {
                installer.abandonSession(id)
                throw StoreException(StoreError.INSTALL, "Установка запрещена: ${e.message}", e)
            }
            id
        }
        val result = InstallResultReceiver.results.first { it.sessionId == sessionId }
        when (result.status) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> throw StoreException(StoreError.CANCELLED, "Установка отменена")
            PackageInstaller.STATUS_FAILURE_STORAGE -> throw StoreException(StoreError.STORAGE, result.message)
            else -> throw StoreException(StoreError.INSTALL, result.message)
        }
    }

    override fun open(app: StoreApp) {
        val packageName = app.android?.packageName ?: return
        context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let(context::startActivity)
    }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
    }
}

/** Ответ системного установщика: просит подтверждения пользователя или сообщает итог. */
class InstallResultReceiver : BroadcastReceiver() {

    data class Result(val sessionId: Int, val status: Int, val message: String)

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            confirmation(intent)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        results.tryEmit(Result(sessionId, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()))
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
