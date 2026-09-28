package ua.tgreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps reading while the screen is off and shows play/pause/next controls
 * in the notification shade, on the lock screen and on headphones.
 */
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSessionCompat
    private var inForeground = false

    override fun onCreate() {
        super.onCreate()
        Reader.init(this)
        createChannel()
        session = MediaSessionCompat(this, "TgReader").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = Reader.play()
                override fun onPause() = Reader.pause()
                override fun onStop() = Reader.pause()
                override fun onSkipToNext() = Reader.next()
                override fun onSkipToPrevious() = Reader.previous()
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        // Must go foreground right away: the service is started with startForegroundService().
        goForeground(buildNotification(Reader.state.value))
        scope.launch { Reader.state.collect(::render) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Intent.ACTION_MEDIA_BUTTON -> MediaButtonReceiver.handleIntent(session, intent)
            ACTION_TOGGLE -> Reader.toggle()
            ACTION_NEXT -> Reader.next()
            ACTION_PREV -> Reader.previous()
            ACTION_DISMISS -> { Reader.pause(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun render(state: ReaderState) {
        val post = state.currentPost
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (state.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, state.rate,
                )
                .build()
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, subtitle(state))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, post?.text?.take(200))
                .build()
        )

        val notification = buildNotification(state)
        if (state.playing) {
            goForeground(notification)
        } else {
            if (inForeground) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                inForeground = false
            }
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                try {
                    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
                } catch (_: SecurityException) {
                }
            }
        }
    }

    private fun subtitle(state: ReaderState): String = when {
        state.waitingForNew -> "Чекаю на нові пости…"
        state.currentPost != null -> state.currentPost!!.text.lineSequence().first().take(80)
        else -> "Готово до читання"
    }

    private fun goForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        inForeground = true
    }

    private fun buildNotification(state: ReaderState): Notification {
        fun action(icon: Int, title: String, action: Long) = NotificationCompat.Action(
            icon, title, MediaButtonReceiver.buildMediaButtonPendingIntent(this, action)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.title.ifEmpty { getString(R.string.app_name) })
            .setContentText(subtitle(state))
            .setContentIntent(openAppIntent())
            .setDeleteIntent(
                PendingIntent.getService(
                    this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_DISMISS),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(state.playing)
            .addAction(action(R.drawable.ic_prev, "Попередній", PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(
                if (state.playing) action(R.drawable.ic_pause, "Пауза", PlaybackStateCompat.ACTION_PAUSE)
                else action(R.drawable.ic_play, "Слухати", PlaybackStateCompat.ACTION_PLAY)
            )
            .addAction(action(R.drawable.ic_next, "Наступний", PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Озвучка", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Керування читанням каналу"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!Reader.state.value.playing) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISMISS = "ua.tgreader.DISMISS"
        const val ACTION_TOGGLE = "ua.tgreader.TOGGLE"
        const val ACTION_NEXT = "ua.tgreader.NEXT"
        const val ACTION_PREV = "ua.tgreader.PREV"
    }
}
