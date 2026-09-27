package ua.tgreader.update

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ua.tgreader.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "Updater"

/** version.json — той самий формат, що в «Основа 2.0» і «Основа TV». */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String = "",
    val mandatory: Boolean = false,
    val notes: String = "",
) {
    companion object {
        /** null, якщо файл битий або в ньому немає адреси APK. */
        fun parse(json: String): UpdateManifest? = runCatching {
            val o = JSONObject(json)
            UpdateManifest(
                versionCode = o.optInt("version_code", 0),
                versionName = o.optString("version_name", ""),
                apkUrl = o.optString("apk_url", ""),
                sha256 = o.optString("sha256", ""),
                mandatory = o.optBoolean("mandatory", false),
                notes = o.optString("notes", ""),
            )
        }.getOrNull()?.takeIf { it.apkUrl.isNotBlank() && (it.versionCode > 0 || it.versionName.isNotBlank()) }
    }

    fun isNewerThan(installedCode: Int, installedName: String): Boolean =
        if (versionCode > 0) versionCode > installedCode else isNewerVersion(versionName, installedName)
}

/** «1.10» новіша за «1.9»: порівнюємо числа, а не рядки. */
fun isNewerVersion(candidate: String, installed: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
    val a = parts(candidate)
    val b = parts(installed)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** Що зараз з оновленням застосунку. */
sealed interface UpdateState {
    data object None : UpdateState

    data class Available(val version: String, val notes: String, val mandatory: Boolean) : UpdateState

    /** percent == null, поки розмір невідомий. */
    data class Downloading(val percent: Int?) : UpdateState

    /** Завантажено й перевірено — лишилось встановити. */
    data object Ready : UpdateState

    data class Failed(val reason: String) : UpdateState
}

/**
 * Оновлення застосунку з GitHub: читає version.json з останнього релізу,
 * завантажує APK, звіряє SHA-256 і відкриває системне встановлення.
 */
@SuppressLint("StaticFieldLeak") // лише applicationContext
object Updater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var manifest: UpdateManifest? = null
    private var ready: File? = null
    private var download: Job? = null
    // Пішли по дозвіл на встановлення — після повернення ставимо самі.
    private var waitingForPermission = false
    private var lastCheck = 0L

    /** Перевірка при відкритті застосунку; частіше ніж раз на 30 хвилин не смикаємо GitHub. */
    fun checkInBackground(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 30 * 60_000L) return
        lastCheck = now
        val app = context.applicationContext
        scope.launch { check(app) }
    }

    suspend fun check(context: Context, url: String = BuildConfig.UPDATE_MANIFEST_URL): UpdateManifest? {
        // Поки файл завантажується чи чекає встановлення, нічого не міняємо.
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Ready) return manifest
        val found = fetchNewer(url)
        manifest = found
        _state.value = found?.let { UpdateState.Available(it.versionName, it.notes, it.mandatory) } ?: UpdateState.None
        return found
    }

    /** Нова версія з маніфесту або null. Не чіпає стан — годиться і для фонової перевірки. */
    suspend fun fetchNewer(url: String = BuildConfig.UPDATE_MANIFEST_URL): UpdateManifest? {
        if (url.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection()
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                (conn as? HttpURLConnection)?.let { http ->
                    http.instanceFollowRedirects = true
                    if (http.responseCode !in 200..299) return@runCatching null
                }
                UpdateManifest.parse(conn.getInputStream().bufferedReader().use { it.readText() })
            }.onFailure { Log.i(TAG, "Маніфест за $url недоступний: ${it.message}") }
                .getOrNull()
                ?.takeIf { it.isNewerThan(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME) }
        }
    }

    /** Натиснули «Оновити»: завантажити, встановити або продовжити почате. */
    fun start(activity: Activity) {
        when (_state.value) {
            is UpdateState.Ready -> install(activity)
            is UpdateState.Available, is UpdateState.Failed -> downloadAndInstall(activity)
            else -> Unit
        }
    }

    /** Застосунок повернувся на екран — наприклад, із налаштувань дозволу на встановлення. */
    fun onResume(activity: Activity) {
        if (waitingForPermission && ApkInstaller.canInstall(activity)) {
            waitingForPermission = false
            install(activity)
        }
    }

    private fun downloadAndInstall(activity: Activity) {
        val m = manifest ?: return
        if (download?.isActive == true) return
        _state.value = UpdateState.Downloading(null)
        val app = activity.applicationContext
        download = scope.launch {
            val result = ApkDownloader.download(app, m.apkUrl, m.sha256) { done, total ->
                scope.launch {
                    _state.value = UpdateState.Downloading(if (total > 0) (done * 100 / total).toInt() else null)
                }
            }
            when (result) {
                is DownloadResult.Success -> {
                    ready = result.file
                    _state.value = UpdateState.Ready
                    install(activity)
                }
                is DownloadResult.Failed -> _state.value = UpdateState.Failed(result.reason)
                DownloadResult.Corrupted -> _state.value = UpdateState.Failed("файл пошкоджено під час завантаження")
            }
        }
    }

    private fun install(activity: Activity) {
        val apk = ready ?: return
        if (!ApkInstaller.canInstall(activity)) {
            // З Android 8 дозвіл на встановлення вмикають у налаштуваннях системи.
            waitingForPermission = true
            ApkInstaller.openPermissionSettings(activity)
            return
        }
        runCatching { activity.startActivity(ApkInstaller.installIntent(activity, apk)) }
            .onFailure { _state.value = UpdateState.Failed(it.message ?: "не вдалося відкрити встановлення") }
    }
}
