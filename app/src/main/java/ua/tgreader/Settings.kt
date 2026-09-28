package ua.tgreader

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    /** "system", "light" or "dark". */
    val theme: String = "system",
    /** Post text size in percent of normal. */
    val fontScale: Int = 100,
    /** Bottom panel with only ⏮ ⏪ ▶ ⏩ ⏭; speed and voice live in settings. */
    val compactPanel: Boolean = false,
    val showPhotos: Boolean = true,
    /** Say "channel, 14:56" before each post. */
    val announce: Boolean = false,
    /** A short beep between posts. */
    val chime: Boolean = false,
    val skipAds: Boolean = true,
    /** Posts containing any of these (case-insensitive) are skipped. */
    val filterWords: List<String> = emptyList(),
    /** Posts shorter than this many characters are skipped; 0 = off. */
    val minLength: Int = 0,
)

/** User settings, stored in the default preferences so the settings screen edits them directly. */
object Settings {
    const val THEME = "theme"
    const val FONT_SCALE = "font_scale"
    const val COMPACT = "compact_panel"
    const val PHOTOS = "show_photos"
    const val ANNOUNCE = "announce"
    const val CHIME = "chime"
    const val SKIP_ADS = "skip_ads"
    const val FILTER_WORDS = "filter_words"
    const val MIN_LENGTH = "min_length"

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state
    private var prefs: SharedPreferences? = null

    // Kept as a field: SharedPreferences holds listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        _state.value = read(p)
        if (key == THEME) applyTheme()
    }

    fun init(context: Context) {
        if (prefs != null) return
        val p = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        prefs = p
        _state.value = read(p)
        p.registerOnSharedPreferenceChangeListener(listener)
        applyTheme()
    }

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (_state.value.theme) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun read(p: SharedPreferences) = AppSettings(
        theme = p.getString(THEME, "system") ?: "system",
        fontScale = p.getInt(FONT_SCALE, 100),
        compactPanel = p.getBoolean(COMPACT, false),
        showPhotos = p.getBoolean(PHOTOS, true),
        announce = p.getBoolean(ANNOUNCE, false),
        chime = p.getBoolean(CHIME, false),
        skipAds = p.getBoolean(SKIP_ADS, true),
        filterWords = (p.getString(FILTER_WORDS, "") ?: "").split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() },
        minLength = p.getString(MIN_LENGTH, "0")?.toIntOrNull() ?: 0,
    )
}

/** Which posts the reader skips. */
object PostFilter {
    // Telegram channels mark paid posts like this; Ukrainian law requires "реклама" labels.
    private val AD = Regex(
        "(?iu)(#реклама|#ad\\b|#promo|#партнерськ|\\berid\\b|на правах реклами|партнерський матеріал|^\\s*реклама\\s*$)",
        RegexOption.MULTILINE,
    )

    fun isAd(text: String) = AD.containsMatchIn(text)

    fun passes(post: Post, s: AppSettings): Boolean {
        if (s.minLength > 0 && post.text.length < s.minLength) return false
        if (s.skipAds && isAd(post.text)) return false
        val lower = post.text.lowercase()
        return s.filterWords.none { lower.contains(it.lowercase()) }
    }
}
