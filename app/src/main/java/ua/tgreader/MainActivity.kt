package ua.tgreader

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import ua.tgreader.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val adapter = PostAdapter(onClick = { Reader.playAt(it) }, onLoadOlder = { Reader.loadOlder() })
    private var lastChannel: String? = null
    private var lastCurrent = -2

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Reader.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        val layoutManager = LinearLayoutManager(this)
        b.posts.layoutManager = layoutManager
        b.posts.adapter = adapter

        b.channelInput.setText(Reader.savedChannel?.let { "@$it" })
        b.openButton.setOnClickListener { submit() }
        b.channelInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { submit(); true } else false
        }
        b.channelLayout.setEndIconOnClickListener { pasteFromClipboard() }

        b.playButton.setOnClickListener {
            askNotificationPermission()
            Reader.toggle()
        }
        b.nextButton.setOnClickListener { Reader.next() }
        b.prevButton.setOnClickListener { Reader.previous() }
        b.rateSlider.addOnChangeListener { _, value, fromUser -> if (fromUser) b.rateLabel.text = rateText(value) }
        b.rateSlider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) = Reader.setRate(slider.value)
        })
        b.followSwitch.setOnCheckedChangeListener { _, checked -> Reader.setFollow(checked) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Reader.state.collect(::render)
            }
        }
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Reader.setUiVisible(true)
    }

    override fun onStop() {
        Reader.setUiVisible(false)
        super.onStop()
    }

    /** "Share" a t.me link from Telegram or a browser straight into the app. */
    private fun handleShareIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        b.channelInput.setText(text)
        submit()
    }

    private fun submit() {
        val text = b.channelInput.text?.toString().orEmpty()
        if (text.isBlank()) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(b.channelInput.windowToken, 0)
        b.channelInput.clearFocus()
        Reader.openChannel(text)
    }

    private fun pasteFromClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this).toString()
        b.channelInput.setText(text)
        submit()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun render(s: ReaderState) {
        b.progress.isVisible = s.loading
        b.title.text = s.title.ifEmpty { getString(R.string.app_name) }
        b.status.text = s.status
        b.status.isVisible = s.status.isNotEmpty()
        b.emptyHint.isVisible = s.posts.isEmpty() && !s.loading
        b.playButton.setIconResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
        b.playButton.contentDescription = if (s.playing) "Пауза" else "Слухати"
        val controlsEnabled = s.posts.isNotEmpty()
        b.playButton.isEnabled = controlsEnabled
        b.nextButton.isEnabled = controlsEnabled
        b.prevButton.isEnabled = controlsEnabled
        if (!b.rateSlider.isPressed) {
            b.rateSlider.value = s.rate.coerceIn(b.rateSlider.valueFrom, b.rateSlider.valueTo)
            b.rateLabel.text = rateText(s.rate)
        }
        if (b.followSwitch.isChecked != s.follow) b.followSwitch.isChecked = s.follow

        val channelChanged = s.channel != lastChannel
        adapter.submit(s.posts, s.current, s.lastReadId, s.hasOlder)
        if (channelChanged && s.posts.isNotEmpty()) {
            lastChannel = s.channel
            // Show the first unread post (or the newest one).
            val firstUnread = s.posts.indexOfFirst { it.id > s.lastReadId }.let { if (it == -1) s.posts.lastIndex else it }
            b.posts.scrollToPosition(adapter.positionOf(firstUnread))
        } else if (s.current != lastCurrent && s.current in s.posts.indices) {
            b.posts.smoothScrollToPosition(adapter.positionOf(s.current))
        }
        lastCurrent = s.current
    }

    private fun rateText(rate: Float) = String.format("%.1f×", rate)
}
