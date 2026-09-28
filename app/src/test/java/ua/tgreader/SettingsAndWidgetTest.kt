package ua.tgreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsAndWidgetTest {
    @Before fun initWorkManager() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun settingsScreenShowsAllOptions() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                shadowOf(Looper.getMainLooper()).idle()
                val list = a.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
                list.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(6000, View.MeasureSpec.EXACTLY),
                )
                list.layout(0, 0, 1080, 6000)
                val shown = texts(list)
                for (t in listOf("Голос", "Швидкість мовлення", "Оголошувати канал і час", "Сигнал між постами",
                    "Читати нові пости", "Пропускати рекламу", "Пропускати пости зі словами", "Пропускати короткі пости",
                    "Тема", "Розмір шрифту", "Компактна панель", "Показувати фото з постів")) {
                    assertTrue("missing $t in $shown", t in shown)
                }
                val root = a.window.decorView
                val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bmp))
                File("build/screenshot-settings.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    @Test fun widgetLayoutInflates() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val views = RemoteViews(ctx.packageName, R.layout.widget_player).apply {
            setTextViewText(R.id.widgetTitle, "Північний Сич 🦉")
            setTextViewText(R.id.widgetText, "Група ударних БпЛА курсом на Київ")
            setImageViewResource(R.id.widgetPlay, R.drawable.ic_pause_widget)
        }
        val parent = FrameLayout(ctx)
        val v = views.apply(ctx, parent)
        assertEquals("Північний Сич 🦉", v.findViewById<TextView>(R.id.widgetTitle).text.toString())
        v.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, 1000, 200)
        val bmp = Bitmap.createBitmap(1000, 200, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File("build/screenshot-widget.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Info reflects the reader state.
        val info = PlayerWidget.Info.of(ReaderState(channel = "x", title = "Канал", posts = listOf(Post(1, null, "Перший рядок\nдругий", "x")), current = 0, playing = true))
        assertEquals(PlayerWidget.Info("Канал", "Перший рядок", true), info)
    }
}
