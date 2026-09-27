package ua.tgreader.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * Запуск системного інсталятора для завантаженого APK.
 *
 * З Android 8 дозволу в маніфесті мало — його підтверджують у налаштуваннях,
 * тож перед запуском перевіряємо стан і за потреби ведемо туди.
 */
object ApkInstaller {

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openPermissionSettings(activity: Activity) {
        val own = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
        val general = Intent(Settings.ACTION_SECURITY_SETTINGS)
        runCatching { activity.startActivity(own) }
            .recoverCatching { activity.startActivity(general) }
    }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            // Без цього прапорця інсталятор отримає URI, який не має права прочитати.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
