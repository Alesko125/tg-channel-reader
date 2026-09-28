package ua.tgreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.navigation.NavigationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowTextToSpeech
import java.io.File

/**
 * End-to-end check against the live t.me preview: channels, the shared feed, the drawer,
 * voices, announcing, the filter, seeking and the sleep timer.
 * Skipped when RUN_ONLINE_TESTS is not set (e.g. offline CI).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowTest {
    @Before fun isolateUpdater() {
        // Without this the real GitHub check finds a "newer" release than the test build and shows its dialog.
        ua.tgreader.update.Updater::class.java.getDeclaredField("lastCheck").apply { isAccessible = true }
            .setLong(ua.tgreader.update.Updater, System.currentTimeMillis())
        (ua.tgreader.update.Updater::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(ua.tgreader.update.Updater) as kotlinx.coroutines.flow.MutableStateFlow<ua.tgreader.update.UpdateState>)
            .value = ua.tgreader.update.UpdateState.None
    }

    @Before fun initWorkManager() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleUntil(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(50) }
    }

    private fun waitLoaded(channel: String) {
        idleUntil { Reader.state.value.channel == channel && Reader.state.value.posts.isNotEmpty() && !Reader.state.value.loading }
        assertEquals("status: " + Reader.state.value.status, channel, Reader.state.value.channel)
    }

    private fun screenshot(a: MainActivity, name: String) {
        val root = a.window.decorView.rootView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun layoutList(a: MainActivity): RecyclerView {
        val list = a.findViewById<RecyclerView>(R.id.posts)
        if (list.width > 0) {
            // Real layout pass of the screen, as it would happen on the next frame.
            a.window.decorView.let { d ->
                d.measure(
                    View.MeasureSpec.makeMeasureSpec(d.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(d.height, View.MeasureSpec.EXACTLY),
                )
                d.layout(0, 0, d.width, d.height)
            }
            return list
        }
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 1080, 1500)
        return list
    }

    @Test fun fullFlow() {
        assumeTrue(System.getenv("RUN_ONLINE_TESTS") != null)
        val prefs = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext())
        // Two Ukrainian voices, as Google TTS reports them.
        ShadowTextToSpeech.addLanguageAvailability(java.util.Locale("uk", "UA"))
        ShadowTextToSpeech.addVoice(android.speech.tts.Voice("uk-ua-x-hfd-local", java.util.Locale("uk", "UA"), 400, 200, false, emptySet()))
        ShadowTextToSpeech.addVoice(android.speech.tts.Voice("uk-ua-x-hfd-network", java.util.Locale("uk", "UA"), 400, 200, true, emptySet()))
        ShadowTextToSpeech.addVoice(android.speech.tts.Voice("en-us-x-sfg-local", java.util.Locale.US, 400, 200, false, emptySet()))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val tts = Reader::class.java.getDeclaredField("tts").apply { isAccessible = true }.get(Reader) as TextToSpeech
            scenario.onActivity { a ->
                shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
                idle()
                // Nothing added yet: the empty screen offers "add" and the catalog.
                assertTrue(a.findViewById<View>(R.id.emptyView).isShown)
            }
            assertEquals(
                listOf("Голос 1 · без інтернету", "Голос 2 · потрібен інтернет"),
                Reader.state.value.voices.map { it.label },
            )

            // Add a channel through the "+" dialog.
            scenario.onActivity { a ->
                a.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_add, 0)
                idle()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                val input = findEditText(dialog.window!!.decorView)!!
                input.setText("https://t.me/ukrpravda_news")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            waitLoaded("ukrpravda_news")

            // Add two monitoring channels from the catalog list (same path the catalog dialog uses).
            scenario.onActivity {
                Reader.addChannels(listOf(SavedChannel("Northern_Sich_ukr", "Північний Сич 🦉"), SavedChannel("war_monitor", "monitor")))
            }
            assertEquals(listOf("ukrpravda_news", "Northern_Sich_ukr", "war_monitor"), Reader.state.value.channels.map { it.name })

            // The drawer lists all channels plus the app items.
            scenario.onActivity { a ->
                val menu = a.findViewById<NavigationView>(R.id.nav).menu
                val titles = (0 until menu.size()).map { menu.getItem(it).title.toString() }
                assertEquals(
                    listOf("Усі канали", "Українська правда", "Північний Сич 🦉", "monitor", "Додати канал", "Каталог каналів",
                        "Налаштування", "Імпорт / експорт каналів", "Перевірити оновлення", "Про застосунок"),
                    titles,
                )
                // Open the shared feed from the drawer.
                a.findViewById<NavigationView>(R.id.nav).menu.performIdentifierAction(1, 0)
            }
            waitLoaded(Reader.FEED)
            val feed = Reader.state.value
            assertTrue("feed has posts of several channels", feed.posts.map { it.channel }.toSet().size >= 2)
            val times = feed.posts.map { java.time.OffsetDateTime.parse(it.date).toInstant() }
            assertEquals("feed is ordered by time", times.sorted(), times)

            scenario.onActivity { a ->
                val list = layoutList(a)
                // Opens at the newest post.
                assertEquals(list.adapter!!.itemCount - 1, (list.layoutManager as LinearLayoutManager).findLastVisibleItemPosition())
                // Posts in the feed are labelled with their channel.
                val holder = list.findViewHolderForAdapterPosition(list.adapter!!.itemCount - 1) as PostAdapter.PostHolder
                val last = feed.posts.last()
                assertTrue(holder.b.date.text.startsWith(feed.titleOf(last.channel)))
            }

            // Voice picker applies the voice and plays a sample.
            scenario.onActivity {
                Reader.setVoice("uk-ua-x-hfd-network")
                assertEquals("uk-ua-x-hfd-network", shadowOf(tts).currentVoice.name)
                assertEquals("Привіт! Так звучатиме озвучка каналу.", shadowOf(tts).lastSpokenText)
            }

            // Announce channel and time; seek by sentence; sleep after the post.
            prefs.edit().putBoolean(Settings.ANNOUNCE, true).putBoolean(Settings.CHIME, true).commit()
            idle()
            scenario.onActivity {
                val s = Reader.state.value
                val target = s.posts.indexOfLast { TextTools.chunks(it.text).size >= 2 }
                assertTrue(target >= 0)
                Reader.setSleepTimer(0)
                Reader.playAt(target)
                idle()
                val post = s.posts[target]
                val spoken = shadowOf(tts).spokenTextList
                val announce = spoken[spoken.size - TextTools.chunks(post.text).size - 1]
                println("ANNOUNCE: $announce")
                assertTrue(announce, announce.startsWith(TextTools.clean(s.titleOf(post.channel)) + ", ") && Regex("\\d\\d:\\d\\d\\.$").containsMatchIn(announce))
                // Sleep timer "after this post": stopped, and the next post is selected.
                assertFalse(Reader.state.value.playing)
                assertEquals(target + 1, Reader.state.value.current)
                assertEquals("Таймер сну: читання зупинено", Reader.state.value.status)

                // Seek: back one sentence from the start of a post stays at 0; forward moves on.
                Reader.pause()
                Reader.playAt(target)
                Reader.pause()
                assertEquals(0, Reader.state.value.chunkIndex)
                Reader.seek(-1)
                Reader.pause()
                assertEquals(0, Reader.state.value.chunkIndex)
            }

            // Filter: hiding posts with a word from the newest post removes it from the list.
            val newest = Reader.state.value.posts.last()
            val word = newest.text.split(Regex("\\s+")).first { it.length >= 5 }
            prefs.edit().putString(Settings.FILTER_WORDS, word).putBoolean(Settings.ANNOUNCE, false).putBoolean(Settings.CHIME, false).commit()
            idle()
            assertTrue(Reader.state.value.posts.none { it.key == newest.key })
            prefs.edit().putString(Settings.FILTER_WORDS, "").commit()
            idle()
            assertTrue(Reader.state.value.posts.any { it.key == newest.key })

            // Compact panel hides speed/voice; photos show on posts that have them.
            prefs.edit().putBoolean(Settings.COMPACT, true).putInt(Settings.FONT_SCALE, 120).commit()
            idle()
            scenario.onActivity { a ->
                assertFalse(a.findViewById<View>(R.id.extraControls).isShown)
                Reader.openChannel("ukrpravda_news")
            }
            waitLoaded("ukrpravda_news")
            scenario.onActivity { a ->
                val s = Reader.state.value
                val withPhoto = s.posts.indexOfLast { it.photo != null }
                println("PHOTO posts: ${s.posts.count { it.photo != null }} / ${s.posts.size}")
                if (withPhoto >= 0) {
                    val list = layoutList(a)
                    list.scrollToPosition(withPhoto)
                    layoutList(a)
                    val holder = list.findViewHolderForAdapterPosition(withPhoto) as PostAdapter.PostHolder
                    assertTrue(holder.b.photo.visibility == View.VISIBLE)
                    assertEquals(16f * 1.2f * a.resources.displayMetrics.scaledDensity, holder.b.text.textSize, 0.5f)
                }
                Reader.playAt(s.posts.size - 2)
                Reader.pause()
                idle()
                screenshot(a, "screenshot")
                a.findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawer).openDrawer(androidx.core.view.GravityCompat.START, false)
                idle()
                screenshot(a, "screenshot-drawer")
                a.findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawer).closeDrawer(androidx.core.view.GravityCompat.START, false)
            }

            // Removing the open channel switches to another one.
            scenario.onActivity { Reader.removeChannel("ukrpravda_news") }
            idleUntil { Reader.state.value.channel == "Northern_Sich_ukr" && !Reader.state.value.loading }
            assertEquals(listOf("Northern_Sich_ukr", "war_monitor"), Reader.state.value.channels.map { it.name })
            prefs.edit().putBoolean(Settings.COMPACT, false).putInt(Settings.FONT_SCALE, 100).commit()
        }
    }

    private fun findEditText(v: View): android.widget.EditText? {
        if (v is android.widget.EditText) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findEditText(v.getChildAt(i))?.let { return it }
        return null
    }
}
