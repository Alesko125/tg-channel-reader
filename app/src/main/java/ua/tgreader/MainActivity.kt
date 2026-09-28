package ua.tgreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ua.tgreader.databinding.ActivityMainBinding
import ua.tgreader.update.UpdateCheckWorker
import ua.tgreader.update.UpdateState
import ua.tgreader.update.Updater

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val adapter = PostAdapter(onClick = { Reader.playAt(it) }, onLoadOlder = { Reader.loadOlder() })
    private var lastChannel: String? = null
    private var lastCurrent = -2
    private var lastPostCount = 0
    private var renderedDrawer: Pair<List<SavedChannel>, String?>? = null
    private var updateDialog: androidx.appcompat.app.AlertDialog? = null
    // Вікно про оновлення показуємо раз за запуск; далі лишається банер.
    private var updateDialogShownFor: String? = null
    private var forceUpdateDialog = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@registerForActivityResult
        runCatching { contentResolver.openOutputStream(uri)?.use { it.write(Dialogs.exportText().toByteArray()) } }
            .onSuccess { Toast.makeText(this, "Список каналів збережено", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "Не вдалося зберегти файл", Toast.LENGTH_LONG).show() }
    }

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val text = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
        if (text == null) Toast.makeText(this, "Не вдалося прочитати файл", Toast.LENGTH_LONG).show()
        else Dialogs.importText(this, text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Reader.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        setupToolbar()
        setupDrawer()

        b.posts.layoutManager = LinearLayoutManager(this)
        b.posts.adapter = adapter
        b.emptyAdd.setOnClickListener { Dialogs.addChannel(this) }
        b.emptyCatalog.setOnClickListener { Dialogs.catalog(this) }

        b.playButton.setOnClickListener {
            askNotificationPermission()
            Reader.toggle()
        }
        b.nextButton.setOnClickListener { Reader.next() }
        b.prevButton.setOnClickListener { Reader.previous() }
        b.rewindButton.setOnClickListener { Reader.seek(-1) }
        b.forwardButton.setOnClickListener { Reader.seek(1) }
        b.rateSlider.addOnChangeListener { _, value, fromUser -> if (fromUser) b.rateLabel.text = rateText(value) }
        b.rateSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = Reader.setRate(slider.value)
        })
        b.voiceButton.setOnClickListener { Dialogs.voice(this) }
        b.followSwitch.setOnCheckedChangeListener { _, checked -> Reader.setFollow(checked) }
        b.updateButton.setOnClickListener { Updater.start(this) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.drawer.isDrawerOpen(GravityCompat.START)) b.drawer.closeDrawer(GravityCompat.START)
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Reader.state.combine(Settings.state) { s, set -> s to set }.collect { (s, set) -> render(s, set) } }
                launch { Updater.state.collect(::renderUpdate) }
                // The sleep timer countdown in the subtitle.
                launch { while (true) { delay(30_000); render(Reader.state.value, Settings.state.value) } }
            }
        }
        UpdateCheckWorker.schedule(this)
        if (savedInstanceState == null) askNotificationPermissionOnce()
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Updater.onResume(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Reader.setUiVisible(true)
        Updater.checkInBackground(this)
    }

    override fun onStop() {
        Reader.setUiVisible(false)
        super.onStop()
    }

    // ---------------------------------------------------------------- toolbar and drawer

    private fun setupToolbar() {
        b.toolbar.setNavigationOnClickListener { b.drawer.openDrawer(GravityCompat.START) }
        b.toolbar.inflateMenu(R.menu.main)
        b.toolbar.setOnMenuItemClickListener { item ->
            val s = Reader.state.value
            when (item.itemId) {
                R.id.action_sleep -> Dialogs.sleepTimer(this)
                R.id.action_add -> Dialogs.addChannel(this)
                R.id.action_voice -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.action_open_tg -> s.channel?.takeIf { !s.isFeed }?.let {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$it")))
                }
                R.id.action_remove -> s.channels.firstOrNull { it.name.equals(s.channel, ignoreCase = true) }
                    ?.let { Dialogs.confirmRemove(this, it) }
            }
            true
        }
    }

    private fun setupDrawer() {
        b.nav.getHeaderView(0).findViewById<TextView>(R.id.navVersion).text = "версія ${BuildConfig.VERSION_NAME}"
        b.nav.setNavigationItemSelectedListener { item ->
            b.drawer.closeDrawer(GravityCompat.START)
            val channels = Reader.state.value.channels
            when (item.itemId) {
                ID_FEED -> Reader.openFeed()
                ID_ADD -> Dialogs.addChannel(this)
                ID_CATALOG -> Dialogs.catalog(this)
                ID_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
                ID_IMPORT -> Dialogs.importExport(this, { saveFile.launch("канали.txt") }, { openFile.launch(arrayOf("text/*")) })
                ID_UPDATE -> {
                    forceUpdateDialog = true
                    lifecycleScope.launch {
                        val found = Updater.check(this@MainActivity)
                        if (found == null) {
                            Toast.makeText(this@MainActivity, getString(R.string.up_to_date, BuildConfig.VERSION_NAME), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                ID_ABOUT -> Dialogs.about(this)
                else -> channels.getOrNull(item.itemId - ID_CHANNEL)?.let { Reader.openChannel(it.name) }
            }
            true
        }
    }

    /** Telegram-style list: all channels, each channel, then the app items. */
    private fun renderDrawer(s: ReaderState) {
        val key = s.channels to s.channel
        if (key == renderedDrawer) return
        renderedDrawer = key
        val menu = b.nav.menu
        menu.clear()
        if (s.channels.size > 1) {
            menu.add(GROUP_CHANNELS, ID_FEED, 0, R.string.all_channels).setIcon(R.drawable.ic_feed)
                .isChecked = s.isFeed
        }
        s.channels.forEachIndexed { i, ch ->
            menu.add(GROUP_CHANNELS, ID_CHANNEL + i, 1 + i, ch.title.ifBlank { "@" + ch.name }).setIcon(R.drawable.ic_channel)
                .isChecked = ch.name.equals(s.channel, ignoreCase = true)
        }
        menu.setGroupCheckable(GROUP_CHANNELS, true, true)
        menu.add(GROUP_MANAGE, ID_ADD, 1000, R.string.add_channel).setIcon(R.drawable.ic_add)
        menu.add(GROUP_MANAGE, ID_CATALOG, 1001, R.string.catalog).setIcon(R.drawable.ic_explore)
        menu.add(GROUP_APP, ID_SETTINGS, 2000, R.string.settings).setIcon(R.drawable.ic_settings)
        menu.add(GROUP_APP, ID_IMPORT, 2001, R.string.import_export).setIcon(R.drawable.ic_import_export)
        menu.add(GROUP_APP, ID_UPDATE, 2002, R.string.check_updates).setIcon(R.drawable.ic_refresh)
        menu.add(GROUP_APP, ID_ABOUT, 2003, R.string.about).setIcon(R.drawable.ic_info)
    }

    // ---------------------------------------------------------------- intents

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_UPDATE) {
            // Натиснули на сповіщення про нову версію.
            forceUpdateDialog = true
            lifecycleScope.launch { Updater.check(this@MainActivity) }
            renderUpdate(Updater.state.value)
            return
        }
        // "Share" a t.me link (or a whole list of them) from Telegram or a browser.
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val names = Telegram.findChannels(text)
        if (names.size > 1) Dialogs.importText(this, text) else Reader.openChannel(names.firstOrNull() ?: text)
    }

    /** Сповіщення потрібні і для керування читанням, і щоб дізнатися про оновлення. */
    private fun askNotificationPermissionOnce() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("askedNotifications", false)) return
        prefs.edit().putBoolean("askedNotifications", true).apply()
        askNotificationPermission()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------------------------------------------------------------- rendering

    private fun render(s: ReaderState, settings: AppSettings) {
        b.progress.isVisible = s.loading
        b.toolbar.title = s.title.ifEmpty { getString(R.string.app_name) }
        b.toolbar.subtitle = listOfNotNull(Dialogs.sleepText(s), s.status.ifBlank { null }).joinToString(" · ").ifEmpty { null }
        b.emptyView.isVisible = s.posts.isEmpty() && !s.loading && s.channels.isEmpty()
        b.playButton.setIconResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        b.playButton.contentDescription = getString(if (s.playing) R.string.pause else R.string.play)
        val hasPosts = s.posts.isNotEmpty()
        b.playButton.isEnabled = hasPosts
        b.nextButton.isEnabled = hasPosts
        b.prevButton.isEnabled = hasPosts
        b.rewindButton.isEnabled = s.currentPost != null
        b.forwardButton.isEnabled = s.currentPost != null
        b.extraControls.isVisible = !settings.compactPanel
        if (!b.rateSlider.isPressed) {
            b.rateSlider.value = s.rate.coerceIn(b.rateSlider.valueFrom, b.rateSlider.valueTo)
            b.rateLabel.text = rateText(s.rate)
        }
        if (b.followSwitch.isChecked != s.follow) b.followSwitch.isChecked = s.follow
        b.voiceButton.text = getString(
            R.string.voice_label,
            s.voices.firstOrNull { it.name == s.voice }?.label?.substringBefore(" · ") ?: "стандартний",
        )
        updateMenu(b.toolbar.menu, s, settings)
        renderDrawer(s)

        val channelChanged = s.channel != lastChannel
        val lm = b.posts.layoutManager as LinearLayoutManager
        // Like a chat: if the newest post was on screen, new posts keep the list at the bottom.
        val wasAtBottom = adapter.itemCount == 0 || lm.findLastVisibleItemPosition() >= adapter.itemCount - 1
        val grew = s.posts.size > lastPostCount && !channelChanged
        lastPostCount = s.posts.size
        adapter.submit(s, settings)
        if (channelChanged && s.posts.isNotEmpty()) {
            lastChannel = s.channel
            // Open at the newest post, like a chat.
            b.posts.scrollToPosition(adapter.positionOf(s.posts.lastIndex))
        } else if (grew && wasAtBottom && !s.playing) {
            b.posts.scrollToPosition(adapter.positionOf(s.posts.lastIndex))
        } else if (s.current != lastCurrent && s.current in s.posts.indices) {
            b.posts.smoothScrollToPosition(adapter.positionOf(s.current))
        }
        lastCurrent = s.current
    }

    private fun updateMenu(menu: Menu, s: ReaderState, settings: AppSettings) {
        val single = s.channel != null && !s.isFeed
        menu.findItem(R.id.action_open_tg)?.isVisible = single
        menu.findItem(R.id.action_remove)?.isVisible = single
        menu.findItem(R.id.action_voice)?.isVisible = settings.compactPanel
        menu.findItem(R.id.action_sleep)?.setIcon(R.drawable.ic_bedtime)
    }

    private fun renderUpdate(state: UpdateState) {
        b.updateBanner.isVisible = state != UpdateState.None
        b.updateText.text = when (state) {
            is UpdateState.Available -> getString(R.string.update_available_version, state.version)
            is UpdateState.Downloading -> state.percent?.let { getString(R.string.update_downloading_pct, it) }
                ?: getString(R.string.update_downloading)
            UpdateState.Ready -> getString(R.string.update_ready)
            is UpdateState.Failed -> getString(R.string.update_failed, state.reason)
            UpdateState.None -> ""
        }
        b.updateButton.text = when (state) {
            is UpdateState.Failed -> getString(R.string.update_retry)
            UpdateState.Ready -> getString(R.string.update_install)
            else -> getString(R.string.update_button)
        }
        b.updateButton.isEnabled = state !is UpdateState.Downloading

        if (state is UpdateState.Available && (forceUpdateDialog || updateDialogShownFor != state.version)) {
            forceUpdateDialog = false
            updateDialogShownFor = state.version
            showUpdateDialog(state)
        }
    }

    private fun showUpdateDialog(state: UpdateState.Available) {
        if (updateDialog?.isShowing == true) return
        val message = buildString {
            append(getString(R.string.update_available_version, state.version))
            if (state.notes.isNotBlank()) append("\n\n").append(state.notes)
        }
        updateDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_button) { _, _ -> Updater.start(this) }
            .apply { if (!state.mandatory) setNegativeButton(R.string.update_later, null) }
            .setCancelable(!state.mandatory)
            .show()
    }

    private fun rateText(rate: Float) = String.format("%.1f×", rate)

    companion object {
        const val ACTION_SHOW_UPDATE = "ua.tgreader.SHOW_UPDATE"
        private const val GROUP_CHANNELS = 1
        private const val GROUP_MANAGE = 2
        private const val GROUP_APP = 3
        private const val ID_FEED = 1
        private const val ID_ADD = 2
        private const val ID_CATALOG = 3
        private const val ID_SETTINGS = 4
        private const val ID_IMPORT = 5
        private const val ID_UPDATE = 6
        private const val ID_ABOUT = 7
        private const val ID_CHANNEL = 100
    }
}
