package ua.tgreader

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.tgreader.update.ApkDownloader
import ua.tgreader.update.DownloadResult
import ua.tgreader.update.UpdateManifest
import ua.tgreader.update.Updater
import ua.tgreader.update.isNewerVersion
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdaterTest {
    private fun manifestFile(code: Int, name: String): String {
        val f = File.createTempFile("version", ".json")
        f.writeText("""{"version_code": $code, "version_name": "$name", "apk_url": "https://example.com/a.apk", "sha256": "", "notes": "Що нового"}""")
        return f.toURI().toString()
    }

    @Test fun parsesManifest() {
        val m = UpdateManifest.parse("""{"version_code":5,"version_name":"1.1","apk_url":"u","sha256":"ab","mandatory":true,"notes":"n","extra":1}""")!!
        assertEquals(UpdateManifest(5, "1.1", "u", "ab", true, "n"), m)
        assertNull(UpdateManifest.parse("""{"version_code":5}"""))   // без адреси APK
        assertNull(UpdateManifest.parse("not json"))
    }

    @Test fun comparesVersions() {
        assertTrue(isNewerVersion("1.10", "1.9"))
        assertTrue(isNewerVersion("v1.1", "1.0"))
        assertTrue(isNewerVersion("1.0.1", "1.0"))
        assertFalse(isNewerVersion("1.0", "1.0"))
        assertFalse(isNewerVersion("0.9", "1.0"))
    }

    @Test fun findsOnlyNewerBuilds() = runBlocking {
        val newer = Updater.fetchNewer(manifestFile(BuildConfig.VERSION_CODE + 1, "9.9"))
        assertNotNull(newer)
        assertEquals("Що нового", newer!!.notes)
        assertNull(Updater.fetchNewer(manifestFile(BuildConfig.VERSION_CODE, "9.9")))
        assertNull(Updater.fetchNewer("file:///does/not/exist.json"))
    }

    @Test fun downloadChecksSha256() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val src = File.createTempFile("app", ".apk").apply { writeBytes(ByteArray(700_000) { (it % 251).toByte() }) }
        val sha = MessageDigest.getInstance("SHA-256").digest(src.readBytes()).joinToString("") { "%02x".format(it) }
        var lastProgress = 0L

        val ok = ApkDownloader.download(context, src.toURI().toString(), sha.uppercase()) { done, _ -> lastProgress = done }
        assertTrue(ok is DownloadResult.Success)
        assertEquals(700_000L, lastProgress)
        assertTrue((ok as DownloadResult.Success).file.readBytes().contentEquals(src.readBytes()))

        val bad = ApkDownloader.download(context, src.toURI().toString(), "00".repeat(32)) { _, _ -> }
        assertEquals(DownloadResult.Corrupted, bad)
        assertFalse(File(context.cacheDir, "updates/update.apk").exists())
    }
}
