package ua.tgreader.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val TAG = "ApkDownloader"

/** Тека всередині кешу: якщо система її почистить, втратимо лише те, що завантажиться наново. */
const val UPDATE_DIR = "updates"

sealed interface DownloadResult {
    data class Success(val file: File) : DownloadResult

    /** Немає мережі, сервер відповів помилкою, скінчилось місце. */
    data class Failed(val reason: String) : DownloadResult

    /** Завантажилось, але вміст не той, що обіцяв маніфест. */
    data object Corrupted : DownloadResult
}

/** Завантаження APK нової версії — як в «Основа 2.0», з перевіркою SHA-256 на льоту. */
object ApkDownloader {

    /**
     * Хеш ловить обрив і пошкодження при завантаженні. Від чужого APK захищає
     * підпис: Android не поставить поверх застосунку пакет з іншим ключем.
     *
     * @param expectedSha256 хеш із маніфесту; порожній — перевірку пропускаємо.
     * @param onProgress (завантажено, усього) у байтах; total = -1, якщо розмір невідомий.
     */
    suspend fun download(
        context: Context,
        url: String,
        expectedSha256: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        // Попередні завантаження прибираємо, інакше кеш ріс би з кожним оновленням.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "update.apk")

        try {
            val conn = URL(url).openConnection()
            conn.connectTimeout = 15_000
            // Таймаут обмежує паузу між порціями, а не все завантаження.
            conn.readTimeout = 60_000
            if (conn is HttpURLConnection) {
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) return@withContext DownloadResult.Failed("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")

            conn.getInputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var reported = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        // Хеш рахуємо одразу, щоб не читати файл з диска ще раз.
                        digest.update(buffer, 0, read)
                        downloaded += read
                        // Не смикаємо екран на кожні 64 КБ.
                        if (downloaded - reported >= 256 * 1024) {
                            reported = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    onProgress(downloaded, total)
                }
            }

            if (expectedSha256.isNotBlank()) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                    Log.w(TAG, "Хеш не збігся: очікували $expectedSha256, отримали $actual")
                    target.delete()
                    return@withContext DownloadResult.Corrupted
                }
            }
            DownloadResult.Success(target)
        } catch (e: Exception) {
            Log.w(TAG, "Завантаження оновлення не вдалось", e)
            target.delete()
            DownloadResult.Failed(e.message ?: e::class.java.simpleName)
        }
    }
}
