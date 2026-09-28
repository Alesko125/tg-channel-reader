package ua.tgreader

import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import ua.tgreader.update.UpdateCheckWorker
import ua.tgreader.update.UpdateManifest
import ua.tgreader.update.UpdateState
import ua.tgreader.update.Updater
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateFlowTest {
    @Before fun isolateUpdater() {
        // Without this the real GitHub check finds a "newer" release than the test build and shows its dialog.
        ua.tgreader.update.Updater::class.java.getDeclaredField("lastCheck").apply { isAccessible = true }
            .setLong(ua.tgreader.update.Updater, System.currentTimeMillis())
        (ua.tgreader.update.Updater::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(ua.tgreader.update.Updater) as kotlinx.coroutines.flow.MutableStateFlow<ua.tgreader.update.UpdateState>)
            .value = ua.tgreader.update.UpdateState.None
    }

    @Before fun initWorkManager() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )
        // Автоматична перевірка при відкритті ходила б у справжній GitHub і перетерла б тестовий маніфест.
        Updater::class.java.getDeclaredField("lastCheck").apply { isAccessible = true }
            .setLong(Updater, System.currentTimeMillis())
    }

    private fun idleUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20)
        }
    }

    @Test fun dialogDownloadAndInstallPermission() {
        val apk = File.createTempFile("new", ".apk").apply { writeBytes(ByteArray(300_000) { it.toByte() }) }
        val sha = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        val manifest = File.createTempFile("version", ".json").apply {
            writeText("""{"version_code": 999, "version_name": "1.1", "apk_url": "${apk.toURI()}", "sha256": "$sha",
                "notes": "Сповіщення про оновлення і встановлення в один дотик."}""")
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                runBlocking { Updater.check(a, manifest.toURI().toString()) }
                idleUntil { ShadowDialog.getLatestDialog() is AlertDialog }
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue(dialog.isShowing)
                val text = dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString()
                assertTrue(text, text.startsWith("Доступне оновлення до версії 1.1"))
                assertTrue(a.findViewById<android.view.View>(R.id.updateBanner).isShown)

                val act = a.window.decorView
                act.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(act.width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(act.height, android.view.View.MeasureSpec.EXACTLY),
                )
                act.layout(0, 0, act.width, act.height)
                val root = dialog.window!!.decorView
                val dw = act.width - (48 * a.resources.displayMetrics.density).toInt()
                root.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(dw, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(act.height, android.view.View.MeasureSpec.AT_MOST),
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val bmp = Bitmap.createBitmap(act.width, act.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                act.draw(canvas)
                canvas.drawARGB(90, 0, 0, 0)
                canvas.save()
                canvas.translate(((act.width - root.width) / 2).toFloat(), ((act.height - root.height) / 2).toFloat())
                root.draw(canvas)
                canvas.restore()
                File("build/update-dialog.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

                // «Оновити»: завантажує, звіряє хеш і, поки немає дозволу, веде в налаштування.
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                idleUntil { Updater.state.value == UpdateState.Ready }
                assertEquals(UpdateState.Ready, Updater.state.value)
                val next = shadowOf(a).nextStartedActivity
                assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, next.action)
                assertEquals("package:ua.tgreader", next.dataString)

                // Дозвіл надали й повернулися — відкривається системне встановлення.
                shadowOf(a.packageManager).setCanRequestPackageInstalls(true)
                Updater.onResume(a)
                val install = shadowOf(a).nextStartedActivity
                assertEquals(android.content.Intent.ACTION_VIEW, install.action)
                assertEquals("application/vnd.android.package-archive", install.type)
                assertEquals("content://ua.tgreader.updates/updates/update.apk", install.dataString)
            }
        }
    }

    @Test fun backgroundCheckPostsNotification() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val posted = UpdateCheckWorker.notify(context, UpdateManifest(999, "1.1", "https://x/a.apk", notes = "Що нового"))
        assertTrue(posted)
        val nm = context.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).allNotifications.single()
        assertEquals("Доступне оновлення до версії 1.1", shadowOf(n).contentTitle)
        assertEquals("Що нового", shadowOf(n).contentText)
    }
}
