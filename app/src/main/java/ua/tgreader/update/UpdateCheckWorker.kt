package ua.tgreader.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ua.tgreader.MainActivity
import ua.tgreader.R
import java.util.concurrent.TimeUnit

/**
 * Фонова перевірка оновлень: раз на 12 годин, коли є інтернет.
 * Про кожну нову версію сповіщає один раз, навіть якщо застосунок не відкривали.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val update = Updater.fetchNewer() ?: return Result.success()
        val prefs = applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val key = "${update.versionCode}/${update.versionName}"
        if (prefs.getString("notified", null) == key) return Result.success()
        if (notify(applicationContext, update)) prefs.edit().putString("notified", key).apply()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "update-check"
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 2

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** false — сповіщення показати не можна (немає дозволу), спробуємо наступного разу. */
        fun notify(context: Context, update: UpdateManifest): Boolean {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_SHOW_UPDATE)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val title = context.getString(R.string.update_available_version, update.versionName)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(update.notes.ifBlank { context.getString(R.string.update_tap_to_install) })
                .setStyle(NotificationCompat.BigTextStyle().bigText(update.notes.ifBlank { context.getString(R.string.update_tap_to_install) }))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            return runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }.isSuccess
        }
    }
}
