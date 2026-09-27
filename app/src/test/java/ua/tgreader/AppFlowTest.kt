package ua.tgreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowTextToSpeech
import java.io.File

/**
 * End-to-end check against the live t.me preview: open a channel, press play, hear the first chunk.
 * Skipped when RUN_ONLINE_TESTS is not set (e.g. offline CI).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowTest {
    @Test fun openChannelAndSpeak() {
        assumeTrue(System.getenv("RUN_ONLINE_TESTS") != null)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.widget.EditText>(R.id.channelInput).setText("https://t.me/ukrpravda_news")
                activity.findViewById<android.view.View>(R.id.openButton).performClick()
            }
            val deadline = System.currentTimeMillis() + 20_000
            while (Reader.state.value.posts.isEmpty() && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(100)
            }
            val loaded = Reader.state.value
            assertEquals("ukrpravda_news", loaded.channel)
            assertTrue(loaded.posts.isNotEmpty())

            // Finish TTS initialisation, then press play on the last post.
            ShadowTextToSpeech.addLanguageAvailability(java.util.Locale("uk", "UA"))
            scenario.onActivity { a ->
                val tts = Reader::class.java.getDeclaredField("tts").apply { isAccessible = true }.get(Reader) as TextToSpeech
                shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
                shadowOf(Looper.getMainLooper()).idle()
                Reader.playAt(loaded.posts.lastIndex)
                shadowOf(Looper.getMainLooper()).idle()
                // The shadow engine finishes each utterance instantly, so the whole post gets read.
                val spoken = shadowOf(tts).spokenTextList
                println("SPOKEN: $spoken")
                assertEquals(TextTools.chunks(loaded.posts.last().text), spoken)
                // Reached the end: keeps "playing" while waiting for new posts.
                assertTrue(Reader.state.value.waitingForNew)
                assertEquals(loaded.posts.last().id, Reader.state.value.lastReadId)

                // Show a post as "current" for the screenshot.
                Reader.pause()
                Reader.playAt(loaded.posts.size - 2)
                Reader.pause()
                shadowOf(Looper.getMainLooper()).idle()

                val root = a.window.decorView.rootView
                val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bmp))
                File("build/screenshot.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
