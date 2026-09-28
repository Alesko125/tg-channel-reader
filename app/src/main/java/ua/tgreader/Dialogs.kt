package ua.tgreader

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.net.URL

/** Dialogs shared by the main screen, the drawer and the settings screen. */
object Dialogs {
    private fun AppCompatActivity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- add channel

    fun addChannel(a: AppCompatActivity) {
        val layout = TextInputLayout(a).apply {
            hint = a.getString(R.string.channel_hint)
            endIconMode = TextInputLayout.END_ICON_CUSTOM
            setEndIconDrawable(R.drawable.ic_paste)
            endIconContentDescription = "Вставити"
        }
        val input = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 1
        }
        layout.addView(input)
        layout.setEndIconOnClickListener {
            val clip = a.getSystemService(ClipboardManager::class.java).primaryClip
            if (clip != null && clip.itemCount > 0) input.setText(clip.getItemAt(0).coerceToText(a))
        }
        val box = LinearLayout(a).apply { setPadding(a.dp(24), a.dp(8), a.dp(24), 0); addView(layout) }
        val dialog = MaterialAlertDialogBuilder(a)
            .setTitle(R.string.add_channel)
            .setView(box)
            .setPositiveButton(R.string.add) { _, _ -> input.text?.toString()?.takeIf { it.isNotBlank() }?.let(Reader::openChannel) }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.catalog) { _, _ -> catalog(a) }
            .show()
        input.setOnEditorActionListener { _, _, _ ->
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); true
        }
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ---------------------------------------------------------------- catalog

    fun catalog(a: AppCompatActivity) {
        val added = Reader.state.value.channels.map { it.name.lowercase() }.toSet()
        val selected = LinkedHashMap<String, CatalogChannel>()
        val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(4), a.dp(16), a.dp(8)) }
        for (section in Catalog.sections) {
            column.addView(TextView(a).apply {
                text = section.title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setPadding(a.dp(8), a.dp(16), 0, a.dp(4))
            })
            for (ch in section.channels) {
                val already = ch.name.lowercase() in added
                column.addView(MaterialCheckBox(a).apply {
                    text = if (ch.note.isBlank()) ch.title else "${ch.title}\n${ch.note}"
                    isChecked = already
                    isEnabled = !already
                    setOnCheckedChangeListener { _, checked -> if (checked) selected[ch.name] = ch else selected.remove(ch.name) }
                })
            }
        }
        column.addView(TextView(a).apply {
            text = "Канали не пов'язані із застосунком. Моніторингові пабліки — не офіційне джерело: під час тривоги орієнтуйтеся на сирену та офіційні повідомлення."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setPadding(a.dp(8), a.dp(16), a.dp(8), 0)
        })
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.catalog)
            .setView(ScrollView(a).apply { addView(column) })
            .setPositiveButton(R.string.add) { _, _ ->
                val n = Reader.addChannels(selected.values.map { SavedChannel(it.name, it.title) })
                if (n > 0) Toast.makeText(a, "Додано каналів: $n", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- sleep timer

    fun sleepTimer(a: AppCompatActivity) {
        val s = Reader.state.value
        val options = listOf(
            "Вимкнено" to null, "Після цього поста" to 0,
            "15 хвилин" to 15, "30 хвилин" to 30, "45 хвилин" to 45, "1 година" to 60, "2 години" to 120,
        )
        val checked = when {
            s.sleepAfterPost -> 1
            s.sleepAt != null -> -1
            else -> 0
        }
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.sleep_timer)
            .setSingleChoiceItems(options.map { it.first }.toTypedArray(), checked) { d, which ->
                Reader.setSleepTimer(options[which].second)
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun sleepText(s: ReaderState): String? = when {
        s.sleepAfterPost -> "⏾ після цього поста"
        s.sleepAt != null -> {
            val min = ((s.sleepAt - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)
            "⏾ ще $min хв"
        }
        else -> null
    }

    // ---------------------------------------------------------------- voice

    fun voice(a: AppCompatActivity) {
        val s = Reader.state.value
        val labels = listOf(a.getString(R.string.voice_standard)) + s.voices.map { it.label }
        val checked = s.voices.indexOfFirst { it.name == s.voice } + 1
        val builder = MaterialAlertDialogBuilder(a)
            .setTitle(R.string.voice_title)
            .setPositiveButton(R.string.voice_done, null)
            .setNegativeButton(R.string.voice_more) { _, _ -> openTtsSettings(a) }
        if (s.voices.isEmpty()) builder.setMessage(R.string.voice_none)
        else builder.setSingleChoiceItems(labels.toTypedArray(), checked) { _, which ->
            // Selecting a voice plays a short sample right away.
            Reader.setVoice(if (which == 0) null else s.voices[which - 1].name)
        }
        if (s.engines.size > 1) builder.setNeutralButton(R.string.voice_engine) { _, _ -> engine(a) }
        builder.show()
    }

    private fun engine(a: AppCompatActivity) {
        val s = Reader.state.value
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.engine_title)
            .setSingleChoiceItems(s.engines.map { it.label }.toTypedArray(), s.engines.indexOfFirst { it.packageName == s.engine }) { d, which ->
                val pkg = s.engines[which].packageName
                Reader.setEngine(pkg)
                d.dismiss()
                // The new engine loads its voices asynchronously; reopen the picker once they arrive.
                a.lifecycleScope.launch {
                    withTimeoutOrNull(5_000) { Reader.state.first { it.voices.isNotEmpty() && it.engine == pkg } }
                    voice(a)
                }
            }
            .show()
    }

    private fun openTtsSettings(a: AppCompatActivity) {
        val intents = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for (i in intents) {
            if (runCatching { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    // ---------------------------------------------------------------- import / export

    /** Plain text with one t.me link per line: readable, and importable back by [importText]. */
    fun exportText(): String = Reader.state.value.channels.joinToString("\n") { "https://t.me/${it.name}" }

    fun importText(a: AppCompatActivity, text: String) {
        val names = Telegram.findChannels(text)
        if (names.isEmpty()) {
            Toast.makeText(a, "У тексті немає адрес каналів", Toast.LENGTH_LONG).show()
            return
        }
        val catalogTitles = Catalog.sections.flatMap { it.channels }.associate { it.name.lowercase() to it.title }
        val n = Reader.addChannels(names.map { SavedChannel(it, catalogTitles[it.lowercase()] ?: it) })
        Toast.makeText(a, if (n > 0) "Додано каналів: $n" else "Усі ці канали вже є у списку", Toast.LENGTH_LONG).show()
    }

    fun importExport(a: AppCompatActivity, saveToFile: () -> Unit, openFile: () -> Unit) {
        val hasChannels = Reader.state.value.channels.isNotEmpty()
        val items = buildList {
            if (hasChannels) add("Поділитися списком каналів" to {
                a.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, exportText()),
                    "Список каналів",
                ))
            })
            if (hasChannels) add("Зберегти у файл" to saveToFile)
            add("Імпорт з файлу" to openFile)
            add("Вставити список" to { pasteList(a) })
        }
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.import_export)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pasteList(a: AppCompatActivity) {
        val input = TextInputEditText(a).apply {
            hint = "Посилання t.me/… або @назви, по одному в рядку"
            minLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val box = LinearLayout(a).apply { setPadding(a.dp(24), a.dp(8), a.dp(24), 0); addView(input) }
        MaterialAlertDialogBuilder(a)
            .setTitle("Вставити список")
            .setView(box)
            .setPositiveButton(R.string.add) { _, _ -> importText(a, input.text?.toString().orEmpty()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- about

    fun about(a: AppCompatActivity) {
        val text = TextView(a).apply {
            setPadding(a.dp(24))
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            this.text = "Версія ${BuildConfig.VERSION_NAME}\n\n" +
                "Читає вголос пости публічних Telegram-каналів. Бере лише те, що видно на сторінці t.me/s/<канал>, " +
                "без акаунта Telegram і без доступу до ваших переписок.\n\n" +
                "Код і релізи: github.com/Alesko125/tg-channel-reader"
        }
        val dialog = MaterialAlertDialogBuilder(a)
            .setTitle(R.string.app_name)
            .setView(text)
            .setPositiveButton("GitHub") { _, _ ->
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Alesko125/tg-channel-reader")))
            }
            .setNegativeButton(R.string.voice_done, null)
            .show()
        // Downloads of the APK across all releases, straight from GitHub.
        a.lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val json = URL("https://api.github.com/repos/Alesko125/tg-channel-reader/releases").readText()
                    val arr = JSONArray(json)
                    (0 until arr.length()).sumOf { i ->
                        val assets = arr.getJSONObject(i).getJSONArray("assets")
                        (0 until assets.length()).map { assets.getJSONObject(it) }
                            .filter { it.getString("name") == "TgReader.apk" }.sumOf { it.getInt("download_count") }
                    }
                }.getOrNull()
            }
            if (count != null && dialog.isShowing) text.append("\n\nЗавантажень APK: $count")
        }
    }

    fun confirmRemove(a: AppCompatActivity, ch: SavedChannel) {
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.remove_channel_title)
            .setMessage(a.getString(R.string.remove_channel_text, ch.title.ifBlank { "@" + ch.name }))
            .setPositiveButton(R.string.remove) { _, _ -> Reader.removeChannel(ch.name) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
