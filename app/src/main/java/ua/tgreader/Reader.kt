package ua.tgreader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A channel the user added; [title] is remembered so the list shows names before loading. */
data class SavedChannel(val name: String, val title: String)

/** A text-to-speech voice as shown in the voice picker. */
data class VoiceOption(val name: String, val label: String)

/** An installed text-to-speech engine (Google, Samsung, RHVoice, …). */
data class EngineOption(val packageName: String, val label: String)

data class ReaderState(
    /** Open channel's username, [Reader.FEED] for all channels together, or null. */
    val channel: String? = null,
    val title: String = "",
    /** Posts after the filter, oldest first. */
    val posts: List<Post> = emptyList(),
    /** Index of the post being read; `posts.size` means "reached the end, waiting for new posts". */
    val current: Int = -1,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val status: String = "",
    val hasOlder: Boolean = false,
    /** Last read post id per channel (lowercase username). */
    val lastRead: Map<String, Int> = emptyMap(),
    val rate: Float = 1f,
    val follow: Boolean = true,
    val channels: List<SavedChannel> = emptyList(),
    val voices: List<VoiceOption> = emptyList(),
    /** null — the engine's default voice for Ukrainian. */
    val voice: String? = null,
    val engines: List<EngineOption> = emptyList(),
    val engine: String? = null,
    /** Sleep timer: stop at this time (epoch ms)… */
    val sleepAt: Long? = null,
    /** …or after the current post. */
    val sleepAfterPost: Boolean = false,
    val chunkIndex: Int = 0,
    val chunkCount: Int = 0,
) {
    val currentPost: Post? get() = posts.getOrNull(current)
    val waitingForNew: Boolean get() = playing && current >= posts.size
    val isFeed: Boolean get() = channel == Reader.FEED
    fun isRead(p: Post) = p.id <= (lastRead[p.channel.lowercase()] ?: 0)
    fun titleOf(channel: String) =
        channels.firstOrNull { it.name.equals(channel, ignoreCase = true) }?.title?.ifBlank { null } ?: "@$channel"
}

/**
 * App-wide reader: owns the loaded posts, the text-to-speech engine and polling for new posts.
 * The UI, [PlaybackService] and the widget only observe [state] and call the public commands.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object Reader {
    /** Pseudo channel: posts of all saved channels merged by time. */
    const val FEED = "*"
    private const val POLL_INTERVAL_MS = 60_000L
    private const val WAITING_STATUS = "Усе прочитано. Чекаю на нові пости…"
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("reader", Context.MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    /** Posts as loaded, before the filter. */
    private var rawPosts: List<Post> = emptyList()
    private var chunks: List<String> = emptyList()
    private var chunkIdx = 0
        set(value) { field = value; _state.update { it.copy(chunkIndex = value, chunkCount = chunks.size) } }
    private var utteranceGen = 0
    private var loadGen = 0
    private var pollJob: Job? = null
    private var sleepJob: Job? = null
    private var olderBefore: Int? = null
    private var uiVisible = false
    private var pausedByFocusLoss = false
    private var pendingPlay = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }

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
        Settings.init(app)
        val channels = loadChannels()
        _state.value = ReaderState(
            rate = prefs.getFloat("rate", 1f),
            follow = prefs.getBoolean("follow", true),
            channels = channels,
            lastRead = channels.associate { it.name.lowercase() to prefs.getInt(lastReadKey(it.name), 0) },
            voice = prefs.getString("voice", null),
            engine = prefs.getString("engine", null),
        )
        initTts()
        // Filter settings apply to what is already loaded.
        scope.launch { Settings.state.collect { refilter() } }
        scope.launch {
            state.map { PlayerWidget.Info.of(it) }.distinctUntilChanged().collect { PlayerWidget.update(app, it) }
        }
        when (val saved = prefs.getString("channel", null)) {
            null -> Unit
            FEED -> openFeed()
            else -> openChannel(saved)
        }
    }

    // ---------------------------------------------------------------- saved channels

    private fun loadChannels(): List<SavedChannel> {
        val json = prefs.getString("channels", null)
        if (json == null) {
            // Before 1.2 only one channel was remembered.
            return listOfNotNull(prefs.getString("channel", null)?.takeIf { it != FEED }?.let { SavedChannel(it, it) })
        }
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SavedChannel(o.getString("name"), o.optString("title", o.getString("name")))
            }
        }.getOrDefault(emptyList())
    }

    private fun saveChannels(list: List<SavedChannel>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("title", it.title)) }
        prefs.edit().putString("channels", arr.toString()).apply()
        _state.update { st ->
            st.copy(
                channels = list,
                lastRead = list.associate { it.name.lowercase() to (st.lastRead[it.name.lowercase()] ?: prefs.getInt(lastReadKey(it.name), 0)) },
            )
        }
    }

    private fun rememberChannel(name: String, title: String) {
        val list = _state.value.channels
        val i = list.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        saveChannels(if (i >= 0) list.toMutableList().also { it[i] = SavedChannel(name, title) } else list + SavedChannel(name, title))
    }

    /** Adds channels (from the catalog or an imported list); returns how many were new. */
    fun addChannels(toAdd: List<SavedChannel>): Int {
        val list = _state.value.channels.toMutableList()
        var added = 0
        for (c in toAdd) {
            if (list.none { it.name.equals(c.name, ignoreCase = true) }) { list += c; added++ }
        }
        if (added == 0) return 0
        saveChannels(list)
        when {
            _state.value.channel == null -> openChannel(list.first().name)
            _state.value.isFeed -> openFeed()
        }
        return added
    }

    /** Removes a channel from the list; if it was open, switches to another one. */
    fun removeChannel(name: String) {
        val list = _state.value.channels.filterNot { it.name.equals(name, ignoreCase = true) }
        saveChannels(list)
        prefs.edit().remove(lastReadKey(name)).apply()
        val s = _state.value
        when {
            s.isFeed && list.isNotEmpty() -> {
                rawPosts = rawPosts.filterNot { it.channel.equals(name, ignoreCase = true) }
                refilter()
            }
            s.isFeed || s.channel.equals(name, ignoreCase = true) -> {
                val next = list.firstOrNull()
                if (next != null) openChannel(next.name) else closeAll()
            }
        }
    }

    private fun closeAll() {
        pause()
        pollJob?.cancel()
        prefs.edit().remove("channel").apply()
        olderBefore = null
        rawPosts = emptyList()
        _state.update { it.copy(channel = null, title = "", posts = emptyList(), current = -1, hasOlder = false, status = "") }
    }

    // ---------------------------------------------------------------- loading

    fun openChannel(input: String) {
        val channel = try {
            Telegram.normalizeChannel(input)
        } catch (e: ChannelException) {
            _state.update { it.copy(status = e.message.orEmpty()) }
            return
        }
        startLoading()
        val gen = loadGen
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { Telegram.fetch(channel) }
                if (gen != loadGen) return@launch
                prefs.edit().putString("channel", page.channel).apply()
                rememberChannel(page.channel, page.title)
                olderBefore = page.prevBefore
                showLoaded(page.channel, page.title, page.posts, hasOlder = page.prevBefore != null)
            } catch (e: ChannelException) {
                if (gen == loadGen) _state.update { it.copy(loading = false, status = e.message.orEmpty()) }
            } catch (e: IOException) {
                if (gen == loadGen) _state.update { it.copy(loading = false, status = "Немає зв'язку з Telegram. Перевірте інтернет.") }
            }
        }
    }

    /** All saved channels in one feed, ordered by time. */
    fun openFeed() {
        val channels = _state.value.channels
        if (channels.isEmpty()) {
            _state.update { it.copy(status = "Спершу додайте канали") }
            return
        }
        startLoading()
        val gen = loadGen
        scope.launch {
            val pages = fetchAll(channels.map { it.name })
            if (gen != loadGen) return@launch
            if (pages.isEmpty()) {
                _state.update { it.copy(loading = false, status = "Немає зв'язку з Telegram. Перевірте інтернет.") }
                return@launch
            }
            pages.forEach { rememberChannel(it.channel, it.title) }
            prefs.edit().putString("channel", FEED).apply()
            olderBefore = null
            val failed = channels.size - pages.size
            showLoaded(FEED, "Усі канали", sortByTime(pages.flatMap { it.posts }), hasOlder = false)
            if (failed > 0) _state.update { it.copy(status = it.status + " · не завантажилось каналів: $failed") }
        }
    }

    private fun startLoading() {
        loadGen++
        pause()
        _state.update { it.copy(loading = true, status = "Завантажую…") }
    }

    private suspend fun fetchAll(names: List<String>): List<ChannelPage> = withContext(Dispatchers.IO) {
        names.map { n -> async { runCatching { Telegram.fetch(n) }.getOrNull() } }.awaitAll().filterNotNull()
    }

    private fun showLoaded(channel: String, title: String, posts: List<Post>, hasOlder: Boolean) {
        rawPosts = posts
        chunks = emptyList()
        // One update, so the screen never sees the new channel with the old posts.
        val visible = posts.filter { PostFilter.passes(it, Settings.state.value) }
        _state.update {
            it.copy(channel = channel, title = title, posts = visible, current = -1, loading = false, hasOlder = hasOlder)
        }
        val s = _state.value
        val unread = s.posts.count { !s.isRead(it) }
        _state.update { it.copy(status = "${s.posts.size} постів" + if (unread in 1 until s.posts.size) " · непрочитаних: $unread" else "") }
        restartPolling()
        if (pendingPlay) { pendingPlay = false; play() }
    }

    private fun sortByTime(posts: List<Post>) = posts.sortedBy { p ->
        p.date?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
    }

    /** Re-applies the filter, keeping the current post (or the "waiting at the end" position). */
    private fun refilter() {
        val settings = Settings.state.value
        val s = _state.value
        val currentKey = s.currentPost?.key
        val visible = rawPosts.filter { PostFilter.passes(it, settings) }
        val current = when {
            currentKey != null -> visible.indexOfFirst { it.key == currentKey }.let { if (it == -1 && s.playing) visible.size else it }
            s.current >= s.posts.size && s.current >= 0 -> visible.size
            else -> -1
        }
        _state.update { it.copy(posts = visible, current = current) }
    }

    fun loadOlder() {
        val s = _state.value
        val channel = s.channel?.takeIf { it != FEED } ?: return
        val before = olderBefore ?: return
        if (s.loading) return
        _state.update { it.copy(loading = true) }
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { Telegram.fetch(channel, before) }
                if (_state.value.channel != channel) return@launch
                olderBefore = page.prevBefore
                val known = rawPosts.mapTo(HashSet()) { it.key }
                rawPosts = page.posts.filter { it.key !in known } + rawPosts
                _state.update { it.copy(loading = false, hasOlder = page.prevBefore != null) }
                refilter()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, status = "Не вдалося завантажити старіші пости") }
            }
        }
    }

    private suspend fun checkForNewPosts() {
        val s = _state.value
        val channel = s.channel ?: return
        val names = if (channel == FEED) s.channels.map { it.name } else listOf(channel)
        val pages = fetchAll(names)
        if (_state.value.channel != channel) return
        val maxId = rawPosts.groupBy { it.channel.lowercase() }.mapValues { (_, v) -> v.maxOf { it.id } }
        val fresh = sortByTime(pages.flatMap { page -> page.posts.filter { it.id > (maxId[page.channel.lowercase()] ?: 0) } })
        if (fresh.isEmpty()) return
        val wasWaiting = _state.value.waitingForNew
        val oldSize = _state.value.posts.size
        rawPosts = rawPosts + fresh
        refilter()
        val added = _state.value.posts.size - oldSize
        if (added <= 0) return
        _state.update { it.copy(status = "Нових постів: $added") }
        if (wasWaiting) advanceTo(oldSize, auto = true)
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
        if (becameVisible && _state.value.follow && _state.value.channel != null && !_state.value.loading) {
            scope.launch { checkForNewPosts() }
        }
    }

    // ---------------------------------------------------------------- playback

    fun play() {
        pausedByFocusLoss = false
        val s = _state.value
        if (s.posts.isEmpty()) {
            // Started from the widget or notification before the channel finished loading.
            if (s.loading) pendingPlay = true
            return
        }
        if (s.current !in s.posts.indices) {
            // Continue from the first unread post, or the latest one if everything was read.
            val next = s.posts.indexOfFirst { !s.isRead(it) }
            select(if (next == -1) s.posts.lastIndex else next)
        }
        startPlaying()
    }

    fun playAt(index: Int) {
        pausedByFocusLoss = false
        if (index !in _state.value.posts.indices) return
        select(index)
        startPlaying()
    }

    fun pause() {
        pausedByFocusLoss = false
        pendingPlay = false
        stopSpeech()
        _state.update { it.copy(playing = false, status = if (it.status == WAITING_STATUS) "" else it.status) }
        if (::app.isInitialized) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            releaseWakeLock()
        }
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun next() {
        val s = _state.value
        if (s.posts.isEmpty()) return
        s.currentPost?.let { markRead(it) }
        val target = (if (s.current < 0) 0 else s.current + 1).coerceAtMost(s.posts.lastIndex)
        if (s.playing) advanceTo(target) else { stopSpeech(); select(target) }
    }

    fun previous() {
        val s = _state.value
        if (s.posts.isEmpty()) return
        // Like a music player: first press restarts the post, a quick second one goes back.
        if (s.playing && s.currentPost != null && chunkIdx > 1) {
            chunkIdx = 0
            speakCurrentChunk()
            return
        }
        val target = (if (s.current !in s.posts.indices) s.posts.lastIndex else s.current - 1).coerceAtLeast(0)
        if (s.playing) advanceTo(target) else { stopSpeech(); select(target) }
    }

    /** Jumps [delta] sentences within the current post. */
    fun seek(delta: Int) {
        val s = _state.value
        if (s.currentPost == null || chunks.isEmpty()) return
        val target = chunkIdx + delta
        when {
            target < 0 -> chunkIdx = 0
            target >= chunks.size -> { next(); return }
            else -> chunkIdx = target
        }
        if (s.playing) speakCurrentChunk() else startPlaying()
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

    /** minutes > 0: stop after that long; 0: after the current post; null: off. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        _state.update { it.copy(sleepAt = null, sleepAfterPost = false) }
        when {
            minutes == null -> Unit
            minutes == 0 -> _state.update { it.copy(sleepAfterPost = true) }
            else -> {
                val at = System.currentTimeMillis() + minutes * 60_000L
                _state.update { it.copy(sleepAt = at) }
                sleepJob = scope.launch {
                    delay(minutes * 60_000L)
                    sleepNow()
                }
            }
        }
    }

    private fun sleepNow() {
        pause()
        sleepJob?.cancel()
        _state.update { it.copy(sleepAt = null, sleepAfterPost = false, status = "Таймер сну: читання зупинено") }
    }

    private fun select(index: Int) {
        val post = _state.value.posts.getOrNull(index)
        chunks = post?.let { chunksFor(it) } ?: emptyList()
        _state.update { it.copy(current = index) }
        chunkIdx = 0
    }

    private fun chunksFor(post: Post): List<String> {
        val body = TextTools.chunks(post.text)
        if (!Settings.state.value.announce) return body
        val title = TextTools.clean(_state.value.titleOf(post.channel)).ifBlank { post.channel }
        val time = post.date?.let {
            runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(TIME) }.getOrNull()
        }
        return listOf(if (time != null) "$title, $time." else "$title.") + body
    }

    /** Moves to another post while playing; [auto] — the previous one finished by itself. */
    private fun advanceTo(index: Int, auto: Boolean = false) {
        stopSpeech()
        select(index)
        if (auto && Settings.state.value.chime && _state.value.currentPost != null) {
            val token = utteranceGen
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ if (token == utteranceGen) speakCurrentChunk() }, 500)
        } else {
            speakCurrentChunk()
        }
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
            if (s.sleepAfterPost) {
                // Show the next post as current so ▶ continues from there.
                select(s.current + 1)
                sleepNow()
                return
            }
            advanceTo(s.current + 1, auto = true)
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
        val key = post.channel.lowercase()
        if (post.id > (_state.value.lastRead[key] ?: 0)) {
            prefs.edit().putInt(lastReadKey(post.channel), post.id).apply()
            _state.update { it.copy(lastRead = it.lastRead + (key to post.id)) }
        }
    }

    private fun lastReadKey(channel: String) = "lastRead:${channel.lowercase()}"

    // ---------------------------------------------------------------- TTS engine

    private fun initTts() {
        ttsReady = false
        val enginePkg = _state.value.engine
        val listener = TextToSpeech.OnInitListener { status -> main.post { onTtsReady(status) } }
        tts = if (enginePkg != null) TextToSpeech(app, listener, enginePkg) else TextToSpeech(app, listener)
    }

    private fun onTtsReady(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            _state.update { it.copy(status = "Синтезатор мовлення недоступний. Встановіть «Синтез мовлення Google».") }
            return
        }
        val lang = engine.setLanguage(Locale("uk", "UA"))
        val voices = listVoices(engine)
        val saved = _state.value.voice?.let { name -> engine.voices?.firstOrNull { it.name == name } }
        if (saved != null) {
            engine.voice = saved
        } else if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            _state.update { it.copy(status = "Немає українського голосу: Налаштування → Мова → Синтез мовлення → встановіть «Українська».") }
        }
        _state.update {
            it.copy(
                voices = voices,
                voice = saved?.name,
                engines = engine.engines.map { e -> EngineOption(e.name, e.label) },
                engine = it.engine ?: engine.defaultEngine,
            )
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
        if (pendingPreview) { pendingPreview = false; previewOrRestart() }
    }

    /** Ukrainian voices of the current engine; all voices if it has none. */
    private fun listVoices(engine: TextToSpeech): List<VoiceOption> {
        val all = runCatching { engine.voices?.toList() }.getOrNull().orEmpty()
            .filter { v -> v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
        val ukrainian = all.filter { it.locale.language == "uk" }
        val pool = ukrainian.ifEmpty { all }
        return pool
            .sortedWith(compareBy({ it.locale.displayName }, { it.isNetworkConnectionRequired }, { it.name }))
            .mapIndexed { i, v -> VoiceOption(v.name, voiceLabel(v, i, ukrainian.isNotEmpty())) }
    }

    private fun voiceLabel(v: android.speech.tts.Voice, index: Int, onlyUkrainian: Boolean): String {
        // Google names voices like "uk-ua-x-hfd-local"; other engines (RHVoice) use real names.
        val codeName = Regex("^[a-z]{2,3}[-_][a-z]{2}([-_].*)?$", RegexOption.IGNORE_CASE).matches(v.name)
        val base = if (codeName) "Голос ${index + 1}" else v.name.replaceFirstChar { it.uppercase() }
        val lang = if (onlyUkrainian) "" else " (${v.locale.getDisplayName(Locale("uk"))})"
        val net = if (v.isNetworkConnectionRequired) " · потрібен інтернет" else " · без інтернету"
        return base + lang + net
    }

    fun setVoice(name: String?) {
        val engine = tts ?: return
        val voice = name?.let { n -> engine.voices?.firstOrNull { it.name == n } }
        if (voice != null) engine.voice = voice else engine.setLanguage(Locale("uk", "UA"))
        prefs.edit().putString("voice", voice?.name).apply()
        _state.update { it.copy(voice = voice?.name) }
        previewOrRestart()
    }

    /** Switching engines restarts TTS; the saved voice belongs to the old engine, so it is reset. */
    fun setEngine(packageName: String) {
        if (packageName == _state.value.engine) return
        val wasPlaying = _state.value.playing
        stopSpeech()
        tts?.shutdown()
        prefs.edit().putString("engine", packageName).remove("voice").apply()
        _state.update { it.copy(engine = packageName, voice = null, voices = emptyList()) }
        initTts()
        if (!wasPlaying) pendingPreview = true
    }

    private var pendingPreview = false

    private fun previewOrRestart() {
        val s = _state.value
        if (s.playing && s.currentPost != null) {
            speakCurrentChunk()
        } else {
            utteranceGen++
            tts?.speak("Привіт! Так звучатиме озвучка каналу.", TextToSpeech.QUEUE_FLUSH, null, "preview")
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
