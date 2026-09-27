package ua.tgreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the launcher icon to build/icon-*.png (for the README and a visual check). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconRenderTest {
    @Test fun render() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val size = 512
        fun save(name: String, draw: (Canvas) -> Unit) {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            draw(Canvas(bmp))
            File("build/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // Round mask like most launchers: background + foreground scaled so the 72dp viewport fills it.
        save("icon-round") { c ->
            val clip = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
            c.clipPath(clip)
            val inset = -(size * 18 / 72)
            for (id in listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground)) {
                ContextCompat.getDrawable(ctx, id)!!.apply { setBounds(inset, inset, size - inset, size - inset) }.draw(c)
            }
        }
        save("icon-mono") { c ->
            c.drawColor(Color.parseColor("#1F2A36"))
            ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_monochrome)!!.apply {
                setTint(Color.parseColor("#C8E0FF")); setBounds(0, 0, size, size)
            }.draw(c)
        }
        save("icon-notification") { c ->
            c.drawColor(Color.parseColor("#1560BD"))
            ContextCompat.getDrawable(ctx, R.drawable.ic_notification)!!.apply {
                setBounds(size / 4, size / 4, size * 3 / 4, size * 3 / 4)
            }.draw(c)
        }
        Paint()
    }
}
