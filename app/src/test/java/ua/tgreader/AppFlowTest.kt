package ua.tgreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
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
    @Before fun initWorkManager() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )
    }

    private fun idleUntil(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50)
        }
    }

    private fun open(scenario: ActivityScenario<MainActivity>, input: String, expected: String) {
        scenario.onActivity { a ->
            a.findViewById<android.widget.EditText>(R.id.channelInput).setText(input)
            a.findViewById<android.view.View>(R.id.openButton).performClick()
        }
        idleUntil { Reader.state.value.channel == expected && Reader.state.value.posts.isNotEmpty() && !Reader.state.value.loading }
        assertEquals(expected, Reader.state.value.channel)
    }

    @Test fun openChannelAndSpeak() {
        assumeTrue(System.getenv("RUN_ONLINE_TESTS") != null)
        // Two Ukrainian voices, as Google TTS reports them.
        ShadowTextToSpeech.addLanguageAvailability(java.util.Locale("uk", "UA"))
        ShadowTextToSpeech.addVoice(android.speech.tts.Voice("uk-ua-x-hfd-local", java.util.Locale("uk", "UA"), 400, 200, false, emptySet()))
        ShadowTextToSpeech.addVoice(android.speech.tts.Voice("uk-ua-x-hfd-network", java.util.Locale("uk", "UA"), 400, 200, true, emptySet()))
        ShadowTextToSpeech.addVoice(android.speech.tts.Voice("en-us-x-sfg-local", java.util.Locale.US, 400, 200, false, emptySet()))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val tts = Reader::class.java.getDeclaredField("tts").apply { isAccessible = true }.get(Reader) as TextToSpeech
            scenario.onActivity {
                shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
                shadowOf(Looper.getMainLooper()).idle()
            }
            // Only Ukrainian voices, with friendly names.
            assertEquals(
                listOf("Голос 1 · без інтернету", "Голос 2 · потрібен інтернет"),
                Reader.state.value.voices.map { it.label },
            )

            // Several channels: both appear as chips, the input is cleared after adding.
            open(scenario, "https://t.me/durov", "durov")
            open(scenario, "@ukrpravda_news", "ukrpravda_news")
            val loaded = Reader.state.value
            assertEquals(listOf("durov", "ukrpravda_news"), loaded.channels.map { it.name })
            scenario.onActivity { a ->
                val chips = a.findViewById<com.google.android.material.chip.ChipGroup>(R.id.channelChips)
                assertEquals(2, chips.childCount)
                assertEquals("Українська правда", (chips.getChildAt(1) as com.google.android.material.chip.Chip).text)
                assertTrue((chips.getChildAt(1) as com.google.android.material.chip.Chip).isChecked)
                assertEquals("", a.findViewById<android.widget.EditText>(R.id.channelInput).text.toString())

                // Opens at the newest post.
                val list = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.posts)
                list.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1500, android.view.View.MeasureSpec.EXACTLY),
                )
                list.layout(0, 0, 1080, 1500)
                val lm = list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
                assertEquals(list.adapter!!.itemCount - 1, lm.findLastVisibleItemPosition())
            }

            // Picking a voice applies it and plays a sample.
            scenario.onActivity {
                Reader.setVoice("uk-ua-x-hfd-network")
                assertEquals("uk-ua-x-hfd-network", shadowOf(tts).currentVoice.name)
                assertEquals("Привіт! Так звучатиме озвучка каналу.", shadowOf(tts).lastSpokenText)
                shadowOf(Looper.getMainLooper()).idle()
            }
            scenario.onActivity { a ->
                assertEquals("Голос: Голос 2", a.findViewById<android.widget.Button>(R.id.voiceButton).text)
            }

            // Press play on the last post; the shadow engine finishes each phrase instantly.
            scenario.onActivity {
                val before = shadowOf(tts).spokenTextList.size
                Reader.playAt(loaded.posts.lastIndex)
                shadowOf(Looper.getMainLooper()).idle()
                val spoken = shadowOf(tts).spokenTextList.drop(before)
                println("SPOKEN: $spoken")
                assertEquals(TextTools.chunks(loaded.posts.last().text), spoken)
                assertTrue(Reader.state.value.waitingForNew)
                assertEquals(loaded.posts.last().id, Reader.state.value.lastReadId)
                Reader.pause()
                Reader.playAt(loaded.posts.size - 2)
                Reader.pause()
                shadowOf(Looper.getMainLooper()).idle()
            }
            scenario.onActivity { a ->
                val root = a.window.decorView.rootView
                val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bmp))
                File("build/screenshot.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }

            // Switch back to the first channel by tapping its chip.
            scenario.onActivity { a ->
                a.findViewById<com.google.android.material.chip.ChipGroup>(R.id.channelChips).getChildAt(0).performClick()
            }
            idleUntil { Reader.state.value.channel == "durov" && !Reader.state.value.loading }
            assertEquals("durov", Reader.state.value.channel)

            // Removing the open channel switches to the remaining one.
            scenario.onActivity { Reader.removeChannel("durov") }
            idleUntil { Reader.state.value.channel == "ukrpravda_news" && !Reader.state.value.loading }
            assertEquals(listOf("ukrpravda_news"), Reader.state.value.channels.map { it.name })
            assertEquals("ukrpravda_news", Reader.state.value.channel)
        }
    }
}
