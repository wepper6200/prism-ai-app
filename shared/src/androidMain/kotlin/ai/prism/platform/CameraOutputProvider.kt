package ai.prism.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Куда приложение камеры записывает снимок: временный файл во внутреннем кэше Prism AI.
 * Снимок не попадает в общую галерею; после обработки файл удаляется.
 * Доступ к адресу камера получает только на время съёмки (разрешение на запись выдаёт система).
 */
class CameraOutputProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        file.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = fileFor(uri) ?: return null
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            ?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else file.name })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (fileFor(uri)?.delete() == true) 1 else 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun fileFor(uri: Uri): File? {
        val context = context ?: return null
        val name = uri.lastPathSegment?.takeIf { SAFE_NAME.matches(it) } ?: return null
        return File(File(context.cacheDir, DIRECTORY), name)
    }

    companion object {
        private const val DIRECTORY = "camera"
        private const val MIME_TYPE = "image/jpeg"
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

        fun authority(context: Context): String = "${context.packageName}.camera"

        fun uriFor(context: Context, name: String): Uri =
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(name).build()
    }
}
