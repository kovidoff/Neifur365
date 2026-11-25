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
import android.os.PowerManager
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun canAutoEnterPip(loaded: Boolean, paused: Boolean, ended: Boolean): Boolean =
    loaded && !paused && !ended

internal fun shouldFinishOnStop(inPip: Boolean, isScreenInteractive: Boolean): Boolean =
    inPip && isScreenInteractive

class PlayerActivity : ComponentActivity() {
    private lateinit var player: MpvPlayer
    private var sessionState by mutableStateOf<PlaySession?>(null)
    private val session: PlaySession get() = sessionState!!
    private var index by mutableIntStateOf(0)
    private var resolving by mutableStateOf(true)
    private var reconnecting by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var inPip by mutableStateOf(false)
    private var started = false
    private var loadJob: Job? = null
    private var recoverJob: Job? = null
    private var pipUpdateJob: Job? = null
    private var reconnects = 0
    /** The translation playing, the alternatives for this episode and the qualities, for the panel and title. */
    private var stream by mutableStateOf(StreamUi())
    private var notice by mutableStateOf<String?>(null)
    /** Episodes of this title finished on this phone, for the checkmarks in the episode list. */
    private var watched by mutableStateOf<Set<Int>>(emptySet())
    /** Cached pref so saveProgress() can synchronously queue watched downloads before onDestroy() flushes them. */
    private var autoDeleteWatchedEnabled = false
    /** Restored when returning from PiP so manual player brightness never leaks onto other apps. */
    private var savedWindowBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    /** The studio key the viewer wants; each episode resolves it to its own translation. */
    private var pick: String? = null
    /** The picked studio as the site writes it, for messages ("ХЗ Лол", not "хз лол"). */
    private var pickName: String? = null
    /** Translations already marked watched on Anime 365 in this session. */
    private val marked = HashSet<Long>()
    /** Downloaded episodes finished during this session, deleted safely once playback moves on or closes. */
    private val deleteOnClose = HashSet<Long>()
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

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun syncContainerSession() {
        val cur = sessionState ?: return
        val updated = cur.copy(index = index, pick = pick, pickName = pickName)
        sessionState = updated
        container.session = updated
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        // On Activity recreation (config change / process death), prefer the persisted latest episode/pick over a stale container.session.
        val s = (if (savedInstanceState != null) restoreSession() else null)?.also { container.session = it }
            ?: container.session
            ?: savedInstanceState?.let { restoreSession() }?.also { container.session = it }
        if (s == null) { finish(); return }
        sessionState = s
        index = s.index
        pick = s.pick
        pickName = s.pickName
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        player = MpvPlayer(this)
        player.onFileLoaded = { reconnecting = false; lifecycleScope.launch { applyTrackPrefs() } }
        player.onOpenFailed = ::handleOpenFailed
        player.onStreamError = ::recover
        lifecycleScope.launch {
            container.networkKey.drop(1).collect { key ->
                if (key.isNotEmpty()) channelChecked = false
            }
        }
        lifecycleScope.launch { player.changeSubScale(container.prefs.subScale()) }
        lifecycleScope.launch {
            container.prefs.savedBrightness()?.let { b ->
                savedWindowBrightness = b
                if (!inPip) window.attributes = window.attributes.apply { screenBrightness = b }
            }
        }
        lifecycleScope.launch { container.prefs.autoDeleteWatched.collect { autoDeleteWatchedEnabled = it } }
        media = MediaControls(
            this, player,
            onNext = { if (index + 1 < session.episodes.size) loadEpisode(index + 1) },
            onPrevious = { if (index > 0) loadEpisode(index - 1) },
            step = { seekStep },
        )
        loadEpisode(index)

        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(PIP_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleScope.launch { container.prefs.seekStep.collect { seekStep = it; schedulePipUpdate() } }
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
                                sleepStoppedEpisode = true
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
            snapshotFlow { listOf(player.paused, player.loaded, player.ended, player.speed, index, player.duration.toInt(), stream.current?.id, sessionState?.anime?.id) }.collect { syncMedia() }
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
                    !player.loaded || player.paused || player.ended || reconnecting || resolving -> stuck = 0
                    player.buffering && !moving -> if (++stuck >= 8) { stuck = 0; recover() }
                    else -> { stuck = 0; if (moving) reconnects = 0 }
                }
            }
        }
        lifecycleScope.launch { snapshotFlow { notice }.collectLatest { if (it != null) { delay(4500); notice = null } } }
        lifecycleScope.launch {
            snapshotFlow { sessionState?.anime?.id }.collectLatest { animeId ->
                if (animeId == null) return@collectLatest
                container.prefs.history.collect { h ->
                    watched = h.filter { it.animeId == animeId && it.finished }.mapTo(HashSet()) { it.episode }
                }
            }
        }
        // Keep the PiP window's buttons (and auto-enter) in step with playback.
        lifecycleScope.launch {
            snapshotFlow { listOf(player.paused, player.loaded, player.ended, player.videoW, player.videoH) }.collect { schedulePipUpdate() }
        }

        setContent {
            NeifurTheme {
                val curSession = sessionState ?: return@NeifurTheme
                PlayerScreen(
                    player = player, session = curSession, index = index, resolving = resolving, reconnecting = reconnecting,
                    error = error, inPip = inPip, stream = stream, notice = notice, watched = watched,
                    sleep = sleep, sleepRemainingMs = { sleepRemainingMs },
                    autoNextBlocked = sleep == SleepTimer.EndOfEpisode || sleepStoppedEpisode,
                    onTranslation = ::switchTranslation, onHeight = { h -> loadEpisode(index, resumeAt = player.position, height = h, keep = true) },
                    onBack = { finish() }, onRetry = { channelChecked = false; freshEmbedRetried = false; loadEpisode(index) }, onEpisode = { loadEpisode(it) }, onPip = { enterPip() },
                    onVideoBounds = { r ->
                        val rect = android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                        if (!inPip && rect.width() > 0 && rect != videoRect) { videoRect = rect; schedulePipUpdate() }
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

    /** singleTask: launching another anime or episode while PlayerActivity is alive (e.g. in PiP) must switch to it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val next = container.session ?: return
        val cur = sessionState
        if (cur != null && cur.anime.id == next.anime.id && index == next.index && pick == next.pick) return
        if (::player.isInitialized && player.loaded) saveProgress()
        flushWatchedDeletes()
        marked.clear()
        sessionState = next
        index = next.index
        pick = next.pick
        pickName = next.pickName
        stream = StreamUi()
        error = null
        notice = null
        sleepStoppedEpisode = false
        channelChecked = false
        freshEmbedRetried = false
        streamUrls = emptyList()
        streamUrlIndex = 0
        openFallbackJob?.cancel()
        loadEpisode(next.index)
    }

    /**
     * Opens episode [i]: finds the viewer's studio among its translations (or the closest one), asks Anime 365
     * for the video links, fetches subtitles when it's a subtitle translation, and starts at the saved spot.
     * [keep] reuses the current translation (quality change, reconnect) instead of picking again.
     */
    private fun loadEpisode(
        i: Int,
        resumeAt: Double? = null,
        height: Int? = null,
        keep: Boolean = false,
        fromRecover: Boolean = false,
        rollback: Triple<StreamUi, String?, String?>? = null,
    ) {
        val ep = session.episodes.getOrNull(i) ?: return
        if (resumeAt == null && player.loaded) saveProgress()
        // Always cancel stale background jobs so an old channel-check or reconnect never overrides a manual switch.
        openJob?.cancel()
        openFallbackJob?.cancel()
        if (!fromRecover) {
            recoverJob?.cancel()
            reconnects = 0
            reconnecting = false
            freshEmbedRetried = false
        }
        // Another episode (or a fresh start): close the old file now. Left playing while the new links load, it
        // would be saved and marked on the site under the new episode's number.
        if (resumeAt == null) {
            sleepStoppedEpisode = false; player.stop(); playingIndex = -1; playingTr = null
            flushWatchedDeletes(keepEpisodeId = ep.id)
        }
        index = i; resolving = resumeAt == null; error = null
        syncContainerSession()
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            runCatching {
                val c = container
                // Prefer a locally downloaded copy on normal playback (and when recovering an offline episode).
                val local = if (height == null && (!keep || stream.offline)) c.downloads.localFile(ep.id) else null
                if (local != null) {
                    val tr = local.meta.toTranslation()
                    val sub = local.sub?.let { Triple(it.absolutePath, local.meta.studioName, local.meta.subLang) }
                    stream = StreamUi(
                        options = mapOf(tr.kind to listOf(tr)),
                        current = tr,
                        heights = listOf(local.meta.height),
                        height = local.meta.height,
                        externalSubs = sub != null,
                        offline = true,
                    )
                    val start = resumeAt ?: c.prefs.history.first()
                        .firstOrNull { it.animeId == session.anime.id && it.episode == ep.number }
                        ?.takeIf { !it.finished && it.positionSec > 15 }?.positionSec ?: 0.0
                    streamUrls = emptyList()
                    streamUrlIndex = 0
                    streamSub = sub
                    streamRef = null
                    streamStart = start
                    player.preferAudio(if (tr.kind == TranslationKind.VoiceRu) "ru,rus" else if (tr.kind == TranslationKind.VoiceEn) "en,eng" else "ja,jpn,jp")
                    playingIndex = i; playingTr = tr
                    player.load(local.video.absolutePath, start, sub, null, startPaused = !started && !inPip)
                    return@runCatching
                }
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
                stream = stream.copy(current = tr, heights = streams.map { it.height }.distinct(), height = s.height, externalSubs = false, offline = false)
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
                streamUrls = s.urls.distinct()
                streamUrlIndex = 0
                streamSub = subs?.let { Triple(it, tr.author, subLang) }
                streamRef = c.a365.baseUrl() + "/"
                streamStart = start
                playingIndex = i; playingTr = tr
                player.load(streamUrls.first(), start, streamSub, streamRef, startPaused = !started && !inPip)
                watchOpening(ep.id, start)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                reconnecting = false
                if (resumeAt != null && player.loaded) {
                    // Keep the currently playing stream alive instead of showing a full-screen error over active video.
                    rollback?.let { (oldStream, oldPick, oldName) ->
                        stream = oldStream; pick = oldPick; pickName = oldName; syncContainerSession()
                    }
                    notice = it.userMessage()
                } else {
                    error = it.userMessage()
                }
            }
            resolving = false
        }
    }

    /** Another studio from the panel: same episode, same moment, and it becomes the pick for the next episodes. */
    private fun switchTranslation(t: Translation) {
        val rollback = Triple(stream, pick, pickName)
        pick = t.pickKey
        pickName = t.author
        syncContainerSession()
        lifecycleScope.launch { container.prefs.setPick(session.anime.id, t.pickKey) }
        stream = stream.copy(current = t)
        loadEpisode(index, resumeAt = player.position.takeIf { player.loaded }, keep = true, rollback = rollback)
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
    private var openFallbackJob: Job? = null
    private var streamUrls: List<String> = emptyList()
    private var streamUrlIndex = 0
    private var streamSub: Triple<String, String, String>? = null
    private var streamRef: String? = null
    private var streamStart = 0.0
    private var freshEmbedRetried = false

    /**
     * When libmpv fails to open the current video URL: silently walk backup URLs first, then probe & lock
     * onto the best video channel in the current network, then retry fresh embed links once before showing an error.
     */
    private fun handleOpenFailed() {
        openJob?.cancel()
        openFallbackJob?.cancel()
        if (streamUrlIndex + 1 < streamUrls.size) {
            streamUrlIndex++
            player.load(streamUrls[streamUrlIndex], streamStart, streamSub, streamRef, startPaused = !started && !inPip)
            sessionState?.episodes?.getOrNull(index)?.id?.let { watchOpening(it, streamStart) }
            return
        }
        if (stream.offline) {
            player.markOpenFailed()
            return
        }
        val targetEpId = sessionState?.episodes?.getOrNull(index)?.id ?: run {
            player.markOpenFailed()
            return
        }
        openFallbackJob = lifecycleScope.launch {
            reconnecting = true
            if (!channelChecked) {
                channelChecked = true
                notice = "Канал не отвечает — подбираем рабочий…"
                val fix = runCatching { container.channels.ensureWorking(preferSwitch = true) }.getOrNull()
                if (sessionState?.episodes?.getOrNull(index)?.id != targetEpId) return@launch
                if (fix != null) {
                    val (old, new) = fix
                    notice = (old?.let { "Канал «${it.label}» не работает, " } ?: "") + "включили «${new.label}»"
                    freshEmbedRetried = true
                    loadEpisode(index, resumeAt = streamStart, height = stream.height.takeIf { it > 0 }, keep = true, fromRecover = true)
                    return@launch
                }
                notice = null
            }
            if (!freshEmbedRetried) {
                freshEmbedRetried = true
                delay(400)
                if (sessionState?.episodes?.getOrNull(index)?.id != targetEpId) return@launch
                loadEpisode(index, resumeAt = streamStart, height = stream.height.takeIf { it > 0 }, keep = true, fromRecover = true)
                return@launch
            }
            reconnecting = false
            player.markOpenFailed()
        }
    }

    /**
     * Video that never starts usually means the account's Anime 365 channel doesn't get through from this network
     * (the site's "без CDN" one is blocked by many Russian providers). Eight seconds without the file opening: test
     * the channels once, switch the account to one that works, and reopen the episode with links from it.
     */
    private fun watchOpening(episodeId: Long, start: Double) {
        openJob?.cancel()
        if (channelChecked) return
        openJob = lifecycleScope.launch {
            delay(8_000)
            if (player.loaded || player.failed || error != null || channelChecked) return@launch
            if (session.episodes.getOrNull(index)?.id != episodeId) return@launch
            channelChecked = true
            notice = "Видео не грузится — подбираем рабочий канал…"
            val fix = runCatching { container.channels.ensureWorking(preferSwitch = true) }.getOrNull()
            if (player.loaded || session.episodes.getOrNull(index)?.id != episodeId) return@launch
            if (fix != null) {
                val (old, new) = fix
                notice = (old?.let { "Канал «${it.label}» не работает, " } ?: "") + "включили «${new.label}»"
                loadEpisode(index, resumeAt = start, height = stream.height.takeIf { it > 0 }, keep = true, fromRecover = true)
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
            loadEpisode(index, resumeAt = at, height = stream.height.takeIf { it > 0 && !stream.offline }, keep = true, fromRecover = true)
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
            // Never auto-hide subtitles when the viewer explicitly chose a subtitle translation.
            "off" -> if (!stream.isSubs && tracks.any { it.type == "sub" && it.selected }) player.select("sub", null)
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
        // Queue auto-delete synchronously so a rapid finish() -> onDestroy() -> flushWatchedDeletes() never misses it.
        if (entry.finished && autoDeleteWatchedEnabled && container.downloads.state.value.isDownloaded(ep.id)) {
            synchronized(deleteOnClose) { deleteOnClose += ep.id }
        }
        container.scope.launch { container.prefs.saveProgress(entry) }
    }

    private fun flushWatchedDeletes(keepEpisodeId: Long? = null) {
        val ids = synchronized(deleteOnClose) {
            val out = deleteOnClose.filter { it != keepEpisodeId }
            deleteOnClose.removeAll(out.toSet())
            out
        }
        ids.forEach { container.downloads.delete(it) }
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
                if (player.paused || player.ended) action("toggle", android.R.drawable.ic_media_play, "Играть", 2)
                else action("toggle", android.R.drawable.ic_media_pause, "Пауза", 2),
                action("fwd", android.R.drawable.ic_media_ff, "+$seekStep секунд", 3),
            )
        )
        .apply { if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(canAutoEnterPip(player.loaded, player.paused, player.ended)).setSeamlessResizeEnabled(true) }
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

    /** Debounced PiP update so layout animations never flood SystemUI with 60–120 Binder IPC calls per second. */
    private fun schedulePipUpdate() {
        pipUpdateJob?.cancel()
        pipUpdateJob = lifecycleScope.launch {
            delay(80)
            updatePipParams()
        }
    }

    /** What the system's media controls and headphone buttons see: this title, this episode, playing or not. */
    private fun syncMedia() {
        if (!::media.isInitialized || sessionState == null) return
        val ep = session.episodes.getOrNull(index)
        media.update(
            title = session.anime.title,
            subtitle = listOfNotNull(
                ep?.takeIf { session.episodes.size > 1 || (ep.number > 0 && ep.episodeFull.contains("сери", ignoreCase = true)) }?.label,
                stream.current?.author,
            ).joinToString(" · "),
            playing = player.loaded && !player.paused && !player.ended,
            position = player.position, duration = player.duration,
            hasNext = index + 1 < session.episodes.size, hasPrevious = index > 0,
        )
    }

    private fun enterPip() { runCatching { enterPictureInPictureMode(pipParams()) } }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP on its own (setAutoEnterEnabled); older versions need a nudge.
        if (Build.VERSION.SDK_INT < 31 && canAutoEnterPip(player.loaded, player.paused, player.ended)) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            savedWindowBrightness = window.attributes.screenBrightness
            if (savedWindowBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        } else {
            if (savedWindowBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                window.attributes = window.attributes.apply { screenBrightness = savedWindowBrightness }
            }
            hideSystemBars()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !inPip) hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (!inPip) hideSystemBars()
        if (::media.isInitialized) { media.activate(); syncMedia() }
    }

    override fun onStop() {
        super.onStop()
        started = false
        if (::player.isInitialized) { saveProgress(); player.pause(true) }
        if (::media.isInitialized) media.deactivate()
        // onStop while in PiP with screen still on means the user dismissed the floating window;
        // turning off the screen with the power button should pause without killing the player.
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive != false
        if (shouldFinishOnStop(inPip, interactive)) finish()
    }

    /**
     * Leaving the player: bring the system bars back here, while still in landscape, and tell the screen below to
     * re-seat them. HyperOS otherwise keeps the gesture line where it was in landscape (mid-screen in portrait).
     */
    override fun finish() {
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        container.reseatBars = !inPip
        super.finish()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (sessionState != null) saveSession()
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
        flushWatchedDeletes()
        super.onDestroy()
    }

    companion object {
        private const val PIP_ACTION = "app.neifur.PIP_CONTROL"
        /** The player's state kept across process death; another account signing in drops it. */
        const val SESSION_FILE = "player_session.json"
    }
}
