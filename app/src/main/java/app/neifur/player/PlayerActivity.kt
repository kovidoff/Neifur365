package app.neifur.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color as AColor
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.neifur.AnimeStub
import app.neifur.PlaySession
import app.neifur.data.a365.Episode
import kotlinx.serialization.json.jsonObject
import app.neifur.container
import app.neifur.core.ApiError
import app.neifur.core.userMessage
import app.neifur.data.a365.Picker
import app.neifur.data.a365.Translation
import app.neifur.data.a365.TranslationKind
import app.neifur.data.WatchEntry
import app.neifur.ui.NeifurTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerActivity : ComponentActivity() {
    private lateinit var player: MpvPlayer
    private lateinit var session: PlaySession
    private var index by mutableIntStateOf(0)
    private var resolving by mutableStateOf(true)
    private var reconnecting by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var inPip by mutableStateOf(false)
    private var loadJob: Job? = null
    private var recoverJob: Job? = null
    private var reconnects = 0
    /** The translation playing, the alternatives for this episode and the qualities, for the panel and title. */
    private var stream by mutableStateOf(StreamUi())
    private var notice by mutableStateOf<String?>(null)
    /** Episodes of this title finished on this phone, for the checkmarks in the episode list. */
    private var watched by mutableStateOf<Set<Int>>(emptySet())
    /** The studio key the viewer wants; each episode resolves it to its own translation. */
    private var pick: String? = null
    /** The picked studio as the site writes it, for messages ("ХЗ Лол", not "хз лол"). */
    private var pickName: String? = null
    /** Translations already marked watched on Anime 365 in this session. */
    private val marked = HashSet<Long>()
    /** The episode and translation whose file is actually open: progress and "watched" go to these, not to the
     *  episode just tapped, which may still be loading. */
    private var playingIndex = -1
    private var playingTr: Translation? = null
    /** Seconds per skip, from the settings. */
    private var seekStep = 10
    private var sleep by mutableStateOf<SleepTimer>(SleepTimer.Off)
    private var sleepRemainingMs by mutableLongStateOf(0L)
    /** Set when an "end of episode" sleep timer fires so auto-advance stays suppressed on that episode. */
    private var sleepStoppedEpisode by mutableStateOf(false)
    private lateinit var media: MediaControls

    private val noisyReceiver = object : BroadcastReceiver() {
        // Headphones pulled out or Bluetooth gone: pause instead of playing out loud.
        override fun onReceive(c: Context, i: Intent) { if (::player.isInitialized) player.pause(true) }
    }

    /** Where the picture is on screen: PiP animates from exactly this rectangle instead of the whole window. */
    private var videoRect: android.graphics.Rect? = null

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getStringExtra("cmd")) {
                "toggle" -> player.toggle()
                "back" -> player.seekBy(-seekStep.toDouble())
                "fwd" -> player.seekBy(seekStep.toDouble())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // Android may have closed the app while the player was in the background: pick the session back up.
        val s = container.session ?: savedInstanceState?.let { restoreSession() }?.also { container.session = it }
        if (s == null) { finish(); return }
        session = s
        index = s.index
        pick = s.pick
        pickName = s.pickName
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        player = MpvPlayer(this)
        player.onFileLoaded = { reconnecting = false; lifecycleScope.launch { applyTrackPrefs() } }
        player.onStreamError = ::recover
        lifecycleScope.launch { player.changeSubScale(container.prefs.subScale()) }
        media = MediaControls(
            this, player,
            onNext = { if (index + 1 < session.episodes.size) loadEpisode(index + 1) },
            onPrevious = { if (index > 0) loadEpisode(index - 1) },
            step = { seekStep },
        )
        loadEpisode(index)

        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(PIP_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleScope.launch { container.prefs.seekStep.collect { seekStep = it; updatePipParams() } }
        // Starting to play keeps the screen awake and takes audio focus; pausing or ending lets the phone sleep.
        lifecycleScope.launch {
            snapshotFlow { player.loaded && !player.paused && !player.ended }.collect { playing ->
                if (playing) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    media.takeFocus()
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        // Sleep timer countdown: ticks once a second only while a timed sleep timer is armed.
        lifecycleScope.launch {
            snapshotFlow { sleep }.collectLatest { t ->
                when (t) {
                    SleepTimer.Off, SleepTimer.EndOfEpisode -> sleepRemainingMs = 0L
                    is SleepTimer.Until -> {
                        while (isActive) {
                            val left = t.endAtMs - android.os.SystemClock.elapsedRealtime()
                            if (left <= 0L) {
                                sleep = SleepTimer.Off
                                sleepRemainingMs = 0L
                                saveProgress()
                                player.pause(true)
                                notice = "Таймер сна сработал — пауза"
                                break
                            }
                            sleepRemainingMs = left
                            delay(1000)
                        }
                    }
                }
            }
        }
        // Sleep timer "at end of episode": pauses and suppresses auto-next once playback finishes.
        lifecycleScope.launch {
            snapshotFlow { player.ended }.collect { ended ->
                if (!ended) sleepStoppedEpisode = false
                else if (sleep == SleepTimer.EndOfEpisode) {
                    sleepStoppedEpisode = true
                    sleep = SleepTimer.Off
                    saveProgress()
                    player.pause(true)
                    notice = "Таймер сна: серия закончилась"
                }
            }
        }
        lifecycleScope.launch {
            snapshotFlow { listOf(player.paused, player.loaded, player.ended, player.speed, index, player.duration.toInt(), stream.current?.id) }.collect { syncMedia() }
        }

        // Progress is saved on a timer so a crash or a swipe-away never loses more than a few seconds.
        lifecycleScope.launch {
            while (isActive) { delay(5000); syncMedia(); if (!player.paused) { saveProgress(); markIfWatched() } }
        }
        // Stall watchdog: Anime 365's video servers sometimes drop the extra connection a seek opens, and the
        // picture freezes for good. After 8 s stuck, reopen the episode at the same spot with fresh links.
        lifecycleScope.launch {
            var stuck = 0
            var last = -1.0
            while (isActive) {
                delay(1000)
                val moving = player.position != last
                last = player.position
                when {
                    !player.loaded || player.paused || reconnecting || resolving -> stuck = 0
                    player.buffering && !moving -> if (++stuck >= 8) { stuck = 0; recover() }
                    else -> { stuck = 0; if (moving) reconnects = 0 }
                }
            }
        }
        lifecycleScope.launch { snapshotFlow { notice }.collect { if (it != null) { delay(4500); notice = null } } }
        lifecycleScope.launch {
            container.prefs.history.collect { h ->
                watched = h.filter { it.animeId == session.anime.id && it.finished }.mapTo(HashSet()) { it.episode }
            }
        }
        // Keep the PiP window's buttons (and auto-enter) in step with playback.
        lifecycleScope.launch { snapshotFlow { Triple(player.paused, player.loaded, player.videoW * 10000 + player.videoH) }.collect { updatePipParams() } }

        setContent {
            NeifurTheme {
                PlayerScreen(
                    player = player, session = session, index = index, resolving = resolving, reconnecting = reconnecting,
                    error = error, inPip = inPip, stream = stream, notice = notice, watched = watched,
                    sleep = sleep, sleepRemainingMs = sleepRemainingMs,
                    autoNextBlocked = sleep == SleepTimer.EndOfEpisode || sleepStoppedEpisode,
                    onTranslation = ::switchTranslation, onHeight = { h -> loadEpisode(index, resumeAt = player.position, height = h, keep = true) },
                    onBack = { finish() }, onRetry = { loadEpisode(index) }, onEpisode = { loadEpisode(it) }, onPip = { enterPip() },
                    onVideoBounds = { r ->
                        val rect = android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                        if (!inPip && rect.width() > 0 && rect != videoRect) { videoRect = rect; updatePipParams() }
                    },
                    onSelectTrack = ::selectTrack, onSubScale = { v ->
                        player.changeSubScale(v); lifecycleScope.launch { container.prefs.setSubScale(v) }
                    },
                    onSleepMinutes = { mins ->
                        sleep = SleepTimer.Until(android.os.SystemClock.elapsedRealtime() + mins * 60_000L)
                        notice = "Таймер сна: $mins мин"
                    },
                    onSleepExtend = { mins ->
                        (sleep as? SleepTimer.Until)?.let { cur ->
                            val base = maxOf(cur.endAtMs, android.os.SystemClock.elapsedRealtime())
                            sleep = SleepTimer.Until(base + mins * 60_000L)
                        }
                    },
                    onSleepEnd = { sleep = SleepTimer.EndOfEpisode; notice = "Таймер сна: в конце серии" },
                    onSleepOff = { sleep = SleepTimer.Off },
                )
            }
        }
    }

    /**
     * Opens episode [i]: finds the viewer's studio among its translations (or the closest one), asks Anime 365
     * for the video links, fetches subtitles when it's a subtitle translation, and starts at the saved spot.
     * [keep] reuses the current translation (quality change, reconnect) instead of picking again.
     */
    private fun loadEpisode(i: Int, resumeAt: Double? = null, height: Int? = null, keep: Boolean = false) {
        val ep = session.episodes.getOrNull(i) ?: return
        if (resumeAt == null && player.loaded) saveProgress()
        // Another episode (or a fresh start): close the old file now. Left playing while the new links load, it
        // would be saved and marked on the site under the new episode's number.
        if (resumeAt == null) { recoverJob?.cancel(); sleepStoppedEpisode = false; player.stop(); playingIndex = -1; playingTr = null; reconnects = 0 }
        index = i; resolving = resumeAt == null; error = null
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            runCatching {
                val c = container
                val quality = c.prefs.quality.first()
                val reuse = stream.current?.takeIf { keep && it.episodeId == ep.id }
                val tr = reuse ?: run {
                    val list = c.a365.episode(ep.id).translations.orEmpty()
                    val kind = runCatching { TranslationKind.valueOf(c.prefs.preferredKind.first()) }.getOrDefault(TranslationKind.VoiceRu)
                    val chosen = Picker.choose(list, pick, kind, c.prefs.favouriteAuthors(), quality)
                        ?: throw ApiError("Для этой серии пока нет ни одного перевода")
                    stream = StreamUi(Picker.options(list, quality), chosen)
                    // The chosen studio skipped this episode: say which one plays instead.
                    if (pick != null && chosen.pickKey != pick) {
                        val name = pickName ?: pick!!.substringAfter('|').replaceFirstChar { it.uppercase() }
                        notice = "У $name нет этой серии — включён ${chosen.author}"
                    }
                    chosen
                }
                val embed = c.a365.embed(tr.id)
                val streams = embed.stream.filter { it.urls.isNotEmpty() }.sortedByDescending { it.height }
                if (streams.isEmpty()) throw ApiError("Anime 365 не отдал видео для этого перевода")
                val want = height ?: quality
                val s = streams.firstOrNull { it.height <= want } ?: streams.last()
                stream = stream.copy(current = tr, heights = streams.map { it.height }.distinct(), height = s.height, externalSubs = false)
                val subs = embed.subtitlesUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    runCatching {
                        val text = c.a365.subtitles(url)
                        java.io.File(cacheDir, "subs").apply { mkdirs() }.resolve("${tr.id}.ass").apply { writeText(text) }.absolutePath
                    }.getOrNull()
                }
                stream = stream.copy(externalSubs = subs != null)
                val start = resumeAt ?: c.prefs.history.first()
                    .firstOrNull { it.animeId == session.anime.id && it.episode == ep.number }
                    ?.takeIf { !it.finished && it.positionSec > 15 }?.positionSec ?: 0.0
                player.preferAudio(if (tr.kind == TranslationKind.VoiceRu) "ru,rus" else if (tr.kind == TranslationKind.VoiceEn) "en,eng" else "ja,jpn,jp")
                val subLang = if (tr.kind == TranslationKind.SubEn) "en" else if (tr.typeLang.isNotBlank()) tr.typeLang else "ru"
                playingIndex = i; playingTr = tr
                player.load(s.urls.first(), start, subs?.let { Triple(it, tr.author, subLang) }, c.a365.baseUrl() + "/")
                watchOpening(start)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                error = it.userMessage(); reconnecting = false
            }
            resolving = false
        }
    }

    /** Another studio from the panel: same episode, same moment, and it becomes the pick for the next episodes. */
    private fun switchTranslation(t: Translation) {
        pick = t.pickKey
        pickName = t.author
        lifecycleScope.launch { container.prefs.setPick(session.anime.id, t.pickKey) }
        stream = stream.copy(current = t)
        loadEpisode(index, resumeAt = player.position.takeIf { player.loaded }, keep = true)
    }

    /** Past 85% the episode counts as watched on Anime 365 too (the site moves the list counter itself). */
    private fun markIfWatched() {
        val tr = playingTr ?: return
        if (!player.loaded || player.duration <= 0 || player.position / player.duration < 0.85 || tr.id in marked) return
        if (container.account.profile == null) return
        marked += tr.id
        container.scope.launch {
            runCatching { container.a365.markWatched(tr.id) }
                .onSuccess { session.anime.id.toLongOrNull()?.let { container.lists.reload(it) } }
                .onFailure { marked -= tr.id }
        }
    }

    private var channelChecked = false
    private var openJob: Job? = null

    /**
     * Video that never starts usually means the account's Anime 365 channel doesn't get through from this network
     * (the site's "без CDN" one is blocked by many Russian providers). Ten seconds without the file opening: test
     * the channels once, switch the account to one that works, and reopen the episode with links from it.
     */
    private fun watchOpening(start: Double) {
        openJob?.cancel()
        if (channelChecked) return
        openJob = lifecycleScope.launch {
            delay(10_000)
            if (player.loaded || error != null || channelChecked) return@launch
            channelChecked = true
            notice = "Видео не грузится — проверяем каналы Anime 365…"
            val fix = runCatching { container.channels.ensureWorking() }.getOrNull()
            if (player.loaded) return@launch
            if (fix != null) {
                val (old, new) = fix
                notice = (old?.let { "Канал «${it.label}» не работает в вашей сети, " } ?: "") + "включили «${new.label}»"
                loadEpisode(index, resumeAt = start, height = stream.height.takeIf { it > 0 }, keep = true)
            } else notice = null
        }
    }

    /** The stream broke mid-episode (links expire or the connection dropped): fetch fresh links and continue. */
    private fun recover() {
        if (reconnects >= 3) { error = "Поток оборвался. Проверьте соединение"; reconnecting = false; return }
        reconnects++
        reconnecting = true
        val at = player.position
        recoverJob?.cancel()
        recoverJob = lifecycleScope.launch {
            delay(600L * reconnects)
            loadEpisode(index, resumeAt = at, height = stream.height.takeIf { it > 0 }, keep = true)
        }
    }

    /** Re-applies the viewer's last explicit language choice to a freshly opened file. */
    private suspend fun applyTrackPrefs() {
        val tracks = player.tracks
        container.prefs.trackPref("audio")?.let { lang ->
            tracks.filter { it.type == "audio" && it.info.lang == lang }
                .maxByOrNull { (if (it.default) 2 else 0) }
                ?.takeIf { !it.selected }?.let { player.select("audio", it.id) }
        }
        when (val pref = container.prefs.trackPref("sub")) {
            null -> {}
            "off" -> if (tracks.any { it.type == "sub" && it.selected }) player.select("sub", null)
            else -> {
                val lang = pref.substringBefore(':')
                val signs = pref.endsWith(":signs")
                tracks.filter { it.type == "sub" && it.info.lang == lang }
                    .maxByOrNull { (if ((it.info.kind == SubKind.Signs) == signs) 4 else 0) + (if (it.default) 1 else 0) }
                    ?.takeIf { !it.selected }?.let { player.select("sub", it.id) }
            }
        }
    }

    private fun selectTrack(type: String, track: Track?) {
        player.select(type, track?.id)
        val pref = when {
            track == null -> "off"
            track.info.lang == null -> null
            type == "sub" && track.info.kind == SubKind.Signs -> "${track.info.lang}:signs"
            else -> track.info.lang
        }
        lifecycleScope.launch { container.prefs.setTrackPref(type, pref) }
    }

    private fun saveProgress() {
        if (!::player.isInitialized || !player.loaded || player.duration <= 0) return
        val ep = session.episodes.getOrNull(playingIndex) ?: return
        markIfWatched()
        val entry = WatchEntry(
            session.anime.id, session.anime.title, session.anime.poster, ep.number,
            player.position, player.duration, System.currentTimeMillis(),
        )
        container.scope.launch { container.prefs.saveProgress(entry) }
    }

    // --- picture in picture ------------------------------------------------------------------------------
    private fun action(cmd: String, icon: Int, title: String, req: Int) = RemoteAction(
        Icon.createWithResource(this, icon), title, title,
        PendingIntent.getBroadcast(
            this, req, Intent(PIP_ACTION).setPackage(packageName).putExtra("cmd", cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    private fun pipParams(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(pipAspect())
        .apply { videoRect?.let { r -> setSourceRectHint(visible(r)) } }
        .setActions(
            listOf(
                action("back", android.R.drawable.ic_media_rew, "−$seekStep секунд", 1),
                if (player.paused) action("toggle", android.R.drawable.ic_media_play, "Играть", 2)
                else action("toggle", android.R.drawable.ic_media_pause, "Пауза", 2),
                action("fwd", android.R.drawable.ic_media_ff, "+$seekStep секунд", 3),
            )
        )
        .apply { if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(player.loaded && !player.paused).setSeamlessResizeEnabled(false) }
        .build()

    /** The video's own shape, kept inside the range Android accepts for PiP windows. */
    private fun pipAspect(): Rational {
        val w = player.videoW.takeIf { it > 0 } ?: 16
        val h = player.videoH.takeIf { it > 0 } ?: 9
        val r = (w.toDouble() / h).coerceIn(0.42, 2.38)
        return Rational((r * 1000).toInt(), 1000)
    }

    /** The source hint must lie inside the window; a "fill" view can overflow the screen edges. */
    private fun visible(r: android.graphics.Rect): android.graphics.Rect {
        val wm = window.decorView
        return android.graphics.Rect(maxOf(r.left, 0), maxOf(r.top, 0), minOf(r.right, wm.width), minOf(r.bottom, wm.height))
    }

    private fun updatePipParams() { if (::player.isInitialized) runCatching { setPictureInPictureParams(pipParams()) } }

    /** What the system's media controls and headphone buttons see: this title, this episode, playing or not. */
    private fun syncMedia() {
        if (!::media.isInitialized) return
        val ep = session.episodes.getOrNull(index)
        media.update(
            title = session.anime.title,
            subtitle = listOfNotNull(ep?.takeIf { session.episodes.size > 1 }?.label, stream.current?.author).joinToString(" · "),
            playing = player.loaded && !player.paused && !player.ended,
            position = player.position, duration = player.duration,
            hasNext = index + 1 < session.episodes.size, hasPrevious = index > 0,
        )
    }

    private fun enterPip() { runCatching { enterPictureInPictureMode(pipParams()) } }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP on its own (setAutoEnterEnabled); older versions need a nudge.
        if (Build.VERSION.SDK_INT < 31 && player.loaded && !player.paused) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        if (::media.isInitialized) { media.activate(); syncMedia() }
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) { saveProgress(); player.pause(true) }
        if (::media.isInitialized) media.deactivate()
        // onStop while in PiP means the user dismissed the floating window.
        if (inPip) finish()
    }

    /**
     * Leaving the player: bring the system bars back here, while still in landscape, and tell the screen below to
     * re-seat them. HyperOS otherwise keeps the gesture line where it was in landscape (mid-screen in portrait).
     */
    override fun finish() {
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        container.reseatBars = true
        super.finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::session.isInitialized) saveSession()
    }

    /** The session (title, episodes, where we are, chosen studio), kept in a file: the episode list can be long. */
    private fun saveSession() = runCatching {
        val o = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(session.anime.id))
            put("title", kotlinx.serialization.json.JsonPrimitive(session.anime.title))
            put("poster", kotlinx.serialization.json.JsonPrimitive(session.anime.poster))
            put("index", kotlinx.serialization.json.JsonPrimitive(index))
            put("pick", kotlinx.serialization.json.JsonPrimitive(pick))
            put("pickName", kotlinx.serialization.json.JsonPrimitive(pickName))
            put("episodes", app.neifur.core.AppJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Episode.serializer()), session.episodes))
        }
        java.io.File(cacheDir, SESSION_FILE).writeText(o.toString())
    }

    private fun restoreSession(): PlaySession? = runCatching {
        val o = app.neifur.core.AppJson.parseToJsonElement(java.io.File(cacheDir, SESSION_FILE).readText()).jsonObject
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        val eps = app.neifur.core.AppJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Episode.serializer()), o["episodes"]!!)
        PlaySession(
            AnimeStub(str("id")!!, str("title").orEmpty(), str("poster")), eps,
            (o["index"] as kotlinx.serialization.json.JsonPrimitive).content.toInt(), str("pick"), str("pickName"),
        )
    }.getOrNull()

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        runCatching { unregisterReceiver(noisyReceiver) }
        if (::media.isInitialized) media.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    companion object {
        private const val PIP_ACTION = "app.neifur.PIP_CONTROL"
        /** The player's state kept across process death; another account signing in drops it. */
        const val SESSION_FILE = "player_session.json"
    }
}
