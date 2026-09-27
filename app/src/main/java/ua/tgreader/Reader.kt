package ua.tgreader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

data class ReaderState(
    val channel: String? = null,
    val title: String = "",
    val posts: List<Post> = emptyList(),
    /** Index of the post being read; `posts.size` means "reached the end, waiting for new posts". */
    val current: Int = -1,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val status: String = "",
    val hasOlder: Boolean = false,
    val lastReadId: Int = 0,
    val rate: Float = 1f,
    val follow: Boolean = true,
) {
    val currentPost: Post? get() = posts.getOrNull(current)
    val waitingForNew: Boolean get() = playing && current >= posts.size
}

/**
 * App-wide reader: owns the loaded posts, the text-to-speech engine and polling for new posts.
 * The UI and [PlaybackService] only observe [state] and call the public commands.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object Reader {
    private const val POLL_INTERVAL_MS = 60_000L
    private const val WAITING_STATUS = "Усе прочитано. Чекаю на нові пости…"

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("reader", Context.MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var chunks: List<String> = emptyList()
    private var chunkIdx = 0
    private var utteranceGen = 0
    private var pollJob: Job? = null
    private var olderBefore: Int? = null
    private var uiVisible = false
    private var pausedByFocusLoss = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val audioManager by lazy { app.getSystemService(AudioManager::class.java) }
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(ttsAudioAttributes)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (_state.value.playing) {
                        pause(); pausedByFocusLoss = true
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocusLoss) play()
                }
            }
            .build()
    }
    private val ttsAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        _state.value = ReaderState(
            rate = prefs.getFloat("rate", 1f),
            follow = prefs.getBoolean("follow", true),
        )
        initTts()
        prefs.getString("channel", null)?.let { openChannel(it) }
    }

    val savedChannel: String? get() = prefs.getString("channel", null)

    // ---------------------------------------------------------------- loading

    fun openChannel(input: String) {
        val channel = try {
            Telegram.normalizeChannel(input)
        } catch (e: ChannelException) {
            _state.update { it.copy(status = e.message.orEmpty()) }
            return
        }
        pause()
        _state.update {
            it.copy(loading = true, status = "Завантажую…")
        }
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { Telegram.fetch(channel) }
                prefs.edit().putString("channel", page.channel).apply()
                olderBefore = page.prevBefore
                val lastRead = prefs.getInt(lastReadKey(page.channel), 0)
                val unread = page.posts.count { it.id > lastRead }
                _state.update {
                    it.copy(
                        channel = page.channel, title = page.title, posts = page.posts,
                        current = -1, loading = false, hasOlder = page.prevBefore != null,
                        lastReadId = lastRead,
                        status = buildString {
                            append("${page.posts.size} постів")
                            if (lastRead > 0) append(" · непрочитаних: $unread")
                        },
                    )
                }
                chunks = emptyList()
                restartPolling()
            } catch (e: ChannelException) {
                _state.update { it.copy(loading = false, status = e.message.orEmpty()) }
            } catch (e: IOException) {
                _state.update { it.copy(loading = false, status = "Немає зв'язку з Telegram. Перевірте інтернет.") }
            }
        }
    }

    fun loadOlder() {
        val s = _state.value
        val channel = s.channel ?: return
        val before = olderBefore ?: return
        if (s.loading) return
        _state.update { it.copy(loading = true) }
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { Telegram.fetch(channel, before) }
                olderBefore = page.prevBefore
                _state.update { st ->
                    val known = st.posts.mapTo(HashSet()) { it.id }
                    val older = page.posts.filter { it.id !in known }
                    st.copy(
                        posts = older + st.posts,
                        current = if (st.current >= 0) st.current + older.size else st.current,
                        loading = false, hasOlder = page.prevBefore != null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, status = "Не вдалося завантажити старіші пости") }
            }
        }
    }

    private suspend fun checkForNewPosts() {
        val s = _state.value
        val channel = s.channel ?: return
        val page = try {
            withContext(Dispatchers.IO) { Telegram.fetch(channel) }
        } catch (e: Exception) {
            return
        }
        if (_state.value.channel != channel) return
        val lastId = _state.value.posts.lastOrNull()?.id ?: 0
        val fresh = page.posts.filter { it.id > lastId }
        if (fresh.isEmpty()) return
        val wasWaiting = _state.value.waitingForNew
        _state.update { it.copy(posts = it.posts + fresh, status = "Нових постів: ${fresh.size}") }
        if (wasWaiting) moveTo(_state.value.posts.size - fresh.size)
    }

    private fun restartPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val s = _state.value
                if (s.follow && (s.playing || uiVisible)) checkForNewPosts()
            }
        }
    }

    fun setUiVisible(visible: Boolean) {
        val becameVisible = visible && !uiVisible
        uiVisible = visible
        if (becameVisible && _state.value.follow && _state.value.channel != null) {
            scope.launch { checkForNewPosts() }
        }
    }

    // ---------------------------------------------------------------- playback

    fun play() {
        pausedByFocusLoss = false
        val s = _state.value
        if (s.posts.isEmpty()) return
        if (s.current !in s.posts.indices) {
            // Continue from the first unread post, or the latest one if everything was read.
            val next = s.posts.indexOfFirst { it.id > s.lastReadId }
            val index = if (next == -1) s.posts.lastIndex else next
            _state.update { it.copy(current = index) }
            chunks = TextTools.chunks(s.posts[index].text)
            chunkIdx = 0
        }
        startPlaying()
    }

    fun playAt(index: Int) {
        pausedByFocusLoss = false
        val s = _state.value
        if (index !in s.posts.indices) return
        _state.update { it.copy(current = index) }
        chunks = TextTools.chunks(s.posts[index].text)
        chunkIdx = 0
        startPlaying()
    }

    fun pause() {
        pausedByFocusLoss = false
        stopSpeech()
        _state.update { it.copy(playing = false, status = if (it.status == WAITING_STATUS) "" else it.status) }
        audioManager.abandonAudioFocusRequest(focusRequest)
        releaseWakeLock()
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun next() {
        val s = _state.value
        if (s.posts.isEmpty()) return
        s.currentPost?.let { markRead(it) }
        val target = (if (s.current < 0) 0 else s.current + 1).coerceAtMost(s.posts.lastIndex)
        if (s.playing) moveTo(target) else selectOnly(target)
    }

    fun previous() {
        val s = _state.value
        if (s.posts.isEmpty()) return
        val target = (if (s.current !in s.posts.indices) s.posts.lastIndex else s.current - 1).coerceAtLeast(0)
        if (s.playing) moveTo(target) else selectOnly(target)
    }

    fun setRate(rate: Float) {
        prefs.edit().putFloat("rate", rate).apply()
        _state.update { it.copy(rate = rate) }
        tts?.setSpeechRate(rate)
        if (_state.value.playing && _state.value.currentPost != null) speakCurrentChunk()
    }

    fun setFollow(follow: Boolean) {
        prefs.edit().putBoolean("follow", follow).apply()
        _state.update { it.copy(follow = follow) }
    }

    private fun selectOnly(index: Int) {
        stopSpeech()
        _state.update { it.copy(current = index) }
        chunks = TextTools.chunks(_state.value.posts[index].text)
        chunkIdx = 0
    }

    private fun moveTo(index: Int) {
        val posts = _state.value.posts
        _state.update { it.copy(current = index) }
        chunks = posts.getOrNull(index)?.let { TextTools.chunks(it.text) } ?: emptyList()
        chunkIdx = 0
        speakCurrentChunk()
    }

    private fun startPlaying() {
        if (_state.value.status == WAITING_STATUS) _state.update { it.copy(status = "") }
        if (!_state.value.playing) {
            audioManager.requestAudioFocus(focusRequest)
            acquireWakeLock()
            _state.update { it.copy(playing = true) }
            try {
                ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
            } catch (e: IllegalStateException) {
                // Background start not allowed (e.g. resumed by audio focus); the running service keeps working.
            }
        }
        speakCurrentChunk()
    }

    private fun speakCurrentChunk() {
        val s = _state.value
        if (!s.playing) return
        val engine = tts
        if (engine == null || !ttsReady) return // resumes from the TTS init callback
        val post = s.currentPost
        if (post == null) {
            engine.stop()
            if (s.follow) {
                _state.update { it.copy(status = WAITING_STATUS) }
            } else {
                pause()
                _state.update { it.copy(status = "Усе прочитано") }
            }
            return
        }
        if (chunkIdx >= chunks.size) {
            markRead(post)
            moveTo(s.current + 1)
            return
        }
        val id = "${++utteranceGen}"
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        engine.speak(chunks[chunkIdx], TextToSpeech.QUEUE_FLUSH, params, id)
    }

    private fun onUtteranceFinished(id: String?) = main.post {
        if (id != utteranceGen.toString() || !_state.value.playing) return@post
        chunkIdx++
        speakCurrentChunk()
    }

    private fun stopSpeech() {
        utteranceGen++
        tts?.stop()
    }

    private fun markRead(post: Post) {
        val s = _state.value
        val channel = s.channel ?: return
        if (post.id > s.lastReadId) {
            prefs.edit().putInt(lastReadKey(channel), post.id).apply()
            _state.update { it.copy(lastReadId = post.id) }
        }
    }

    private fun lastReadKey(channel: String) = "lastRead:${channel.lowercase()}"

    // ---------------------------------------------------------------- TTS engine

    private fun initTts() {
        tts = TextToSpeech(app) { status ->
            main.post {
                val engine = tts ?: return@post
                if (status != TextToSpeech.SUCCESS) {
                    _state.update { it.copy(status = "Синтезатор мовлення недоступний. Встановіть «Синтез мовлення Google».") }
                    return@post
                }
                val lang = engine.setLanguage(Locale("uk", "UA"))
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    _state.update { it.copy(status = "Немає українського голосу: Налаштування → Мова → Синтез мовлення → встановіть «Українська».") }
                }
                engine.setAudioAttributes(ttsAudioAttributes)
                engine.setSpeechRate(_state.value.rate)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { onUtteranceFinished(utteranceId) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { onUtteranceFinished(utteranceId) }
                    override fun onError(utteranceId: String?, errorCode: Int) { onUtteranceFinished(utteranceId) }
                })
                ttsReady = true
                if (_state.value.playing) speakCurrentChunk()
            }
        }
    }

    // Keeps the CPU awake while listening with the screen off (including waiting for new posts).
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgReader:playback")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }
}
