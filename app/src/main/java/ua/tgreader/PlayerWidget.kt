package ua.tgreader

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: channel, current post and ⏮ ▶ ⏭. */
class PlayerWidget : AppWidgetProvider() {

    data class Info(val title: String, val text: String, val playing: Boolean) {
        companion object {
            fun of(s: ReaderState) = Info(
                title = when {
                    s.channel == null -> "Озвучка каналу"
                    s.isFeed -> s.currentPost?.let { s.titleOf(it.channel) } ?: "Усі канали"
                    else -> s.title
                },
                text = when {
                    s.loading -> "Завантажую…"
                    s.waitingForNew -> "Чекаю на нові пости…"
                    s.currentPost != null -> s.currentPost!!.text.lineSequence().first().take(120)
                    s.channel == null -> "Додайте канал у застосунку"
                    else -> "Натисніть ▶, щоб слухати"
                },
                playing = s.playing,
            )
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Loads the saved channel so ▶ works straight away.
        Reader.init(context)
        update(context, Info.of(Reader.state.value))
    }

    companion object {
        fun update(context: Context, info: Info) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_player).apply {
                setTextViewText(R.id.widgetTitle, info.title)
                setTextViewText(R.id.widgetText, info.text)
                setImageViewResource(R.id.widgetPlay, if (info.playing) R.drawable.ic_pause_widget else R.drawable.ic_play_widget)
                setContentDescription(R.id.widgetPlay, if (info.playing) "Пауза" else "Слухати")
                setOnClickPendingIntent(R.id.widgetPlay, command(context, PlaybackService.ACTION_TOGGLE, 10))
                setOnClickPendingIntent(R.id.widgetNext, command(context, PlaybackService.ACTION_NEXT, 11))
                setOnClickPendingIntent(R.id.widgetPrev, command(context, PlaybackService.ACTION_PREV, 12))
                setOnClickPendingIntent(
                    R.id.widgetBody,
                    PendingIntent.getActivity(
                        context, 13, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            manager.updateAppWidget(ids, views)
        }

        private fun command(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getForegroundService(
                context, code, Intent(context, PlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
