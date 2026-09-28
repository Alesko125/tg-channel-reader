package ua.tgreader

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Reader.init(this)
        setContentView(R.layout.activity_settings)
        val root = findViewById<android.view.View>(R.id.settingsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.settingsToolbar).setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settingsContainer, SettingsFragment()).commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("voice_ui")!!.setOnPreferenceClickListener {
                Dialogs.voice(requireActivity() as AppCompatActivity); true
            }
            findPreference<SeekBarPreference>("rate_ui")!!.apply {
                value = (Reader.state.value.rate * 10).toInt()
                setOnPreferenceChangeListener { _, v -> Reader.setRate((v as Int) / 10f); true }
            }
            findPreference<SwitchPreferenceCompat>("follow_ui")!!.apply {
                isChecked = Reader.state.value.follow
                setOnPreferenceChangeListener { _, v -> Reader.setFollow(v as Boolean); true }
            }
            findPreference<EditTextPreference>(Settings.FILTER_WORDS)!!.summaryProvider =
                Preference.SummaryProvider<EditTextPreference> { p -> p.text?.takeIf { it.isNotBlank() } ?: "Не задано" }
            findPreference<SeekBarPreference>(Settings.FONT_SCALE)!!.summaryProvider =
                Preference.SummaryProvider<SeekBarPreference> { p -> "${p.value}%" }

            viewLifecycleOwnerLiveData.observe(this) { owner ->
                owner?.lifecycleScope?.launch {
                    owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        Reader.state.collect { s ->
                            findPreference<Preference>("voice_ui")?.summary =
                                s.voices.firstOrNull { it.name == s.voice }?.label ?: "Стандартний голос"
                            findPreference<SeekBarPreference>("rate_ui")?.summary = String.format("%.1f×", s.rate)
                        }
                    }
                }
            }
        }
    }
}
