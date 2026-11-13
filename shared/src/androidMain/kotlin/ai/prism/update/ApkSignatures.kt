package ai.prism.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Подпись APK и установленного приложения. Android ставит новую версию поверх старой, только если ключи совпадают;
 * иначе системный установщик сообщает ошибку, а на некоторых телефонах просто висит. Поэтому проверяем заранее.
 */
internal object ApkSignatures {

    /**
     * `true` — приложение [packageName] установлено и подписано другим ключом, чем [apkPath].
     * Если подпись прочитать не удалось, считаем, что конфликта нет: решит системный установщик.
     */
    fun conflicts(context: Context, apkPath: String, packageName: String): Boolean {
        val installed = installedInfo(context.packageManager, packageName)?.let(::digests) ?: return false
        val apk = archiveInfo(context.packageManager, apkPath)?.let(::digests) ?: return false
        if (installed.isEmpty() || apk.isEmpty()) return false
        return installed.intersect(apk).isEmpty()
    }

    private fun installedInfo(manager: PackageManager, packageName: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun archiveInfo(manager: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    /** SHA-256 сертификатов подписи (вместе с прежними ключами, если ключ меняли через ротацию). */
    private fun digests(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val signatures = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        val sha = MessageDigest.getInstance("SHA-256")
        return signatures.orEmpty().mapTo(mutableSetOf()) { signature ->
            sha.digest(signature.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
