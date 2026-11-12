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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.neifur.PlaySession
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
    private var reconnects = 0
    /** The translation playing, the alternatives for this episode and the qualities, for the panel and title. */
    private var stream by mutableStateOf(StreamUi())
    private var notice by mutableStateOf<String?>(null)
    /** The studio key the viewer wants; each episode resolves it to its own translation. */
    private var pick: String? = null
    /** Translations already marked watched on Anime 365 in this session. */
    private val marked = HashSet<Long>()
    /** Where the picture is on screen: PiP animates from exactly this rectangle instead of the whole window. */
    private var videoRect: android.graphics.Rect? = null

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getStringExtra("cmd")) {
                "toggle" -> player.toggle()
                "back" -> player.seekBy(-10.0)
                "fwd" -> player.seekBy(10.0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val s = container.session
        if (s == null) { finish(); return }
        session = s
        index = s.index
        pick = s.pick
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        player = MpvPlayer(this)
        player.onFileLoaded = { reconnecting = false; lifecycleScope.launch { applyTrackPrefs() } }
        player.onStreamError = ::recover
        lifecycleScope.launch { player.changeSubScale(container.prefs.subScale()) }
        loadEpisode(index)

        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(PIP_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Progress is saved on a timer so a crash or a swipe-away never loses more than a few seconds.
        lifecycleScope.launch {
            while (isActive) { delay(5000); if (!player.paused) { saveProgress(); markIfWatched() } }
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
        // Keep the PiP window's buttons (and auto-enter) in step with playback.
        lifecycleScope.launch { snapshotFlow { Triple(player.paused, player.loaded, player.videoW * 10000 + player.videoH) }.collect { updatePipParams() } }

        setContent {
            NeifurTheme {
                PlayerScreen(
                    player = player, session = session, index = index, resolving = resolving, reconnecting = reconnecting,
                    error = error, inPip = inPip, stream = stream, notice = notice,
                    onTranslation = ::switchTranslation, onHeight = { h -> loadEpisode(index, resumeAt = player.position, height = h, keep = true) },
                    onBack = { finish() }, onRetry = { loadEpisode(index) }, onEpisode = { loadEpisode(it) }, onPip = { enterPip() },
                    onVideoBounds = { r ->
                        val rect = android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                        if (!inPip && rect.width() > 0 && rect != videoRect) { videoRect = rect; updatePipParams() }
                    },
                    onSelectTrack = ::selectTrack, onSubScale = { v ->
                        player.changeSubScale(v); lifecycleScope.launch { container.prefs.setSubScale(v) }
                    },
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
        if (resumeAt == null) reconnects = 0
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
                        notice = "${pick!!.substringAfter('|').replaceFirstChar { it.uppercase() }} нет для этой серии — включён ${chosen.author}"
                    }
                    chosen
                }
                val embed = c.a365.embed(tr.id)
                val streams = embed.stream.filter { it.urls.isNotEmpty() }.sortedByDescending { it.height }
                if (streams.isEmpty()) throw ApiError("Anime 365 не отдал видео для этого перевода")
                val want = height ?: quality
                val s = streams.firstOrNull { it.height <= want } ?: streams.last()
                stream = stream.copy(current = tr, heights = streams.map { it.height }.distinct(), height = s.height)
                val subs = embed.subtitlesUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    runCatching {
                        val text = c.a365.subtitles(url)
                        java.io.File(cacheDir, "subs").apply { mkdirs() }.resolve("${tr.id}.ass").apply { writeText(text) }.absolutePath
                    }.getOrNull()
                }
                val start = resumeAt ?: c.prefs.history.first()
                    .firstOrNull { it.animeId == session.anime.id && it.episode == ep.number }
                    ?.takeIf { !it.finished && it.positionSec > 15 }?.positionSec ?: 0.0
                player.preferAudio(if (tr.kind == TranslationKind.VoiceRu) "ru,rus" else if (tr.kind == TranslationKind.VoiceEn) "en,eng" else "ja,jpn,jp")
                player.load(s.urls.first(), start, subs, c.a365.baseUrl() + "/")
            }.onFailure {
                if (it !is kotlinx.coroutines.CancellationException) { error = it.userMessage(); reconnecting = false }
            }
            resolving = false
        }
    }

    /** Another studio from the panel: same episode, same moment, and it becomes the pick for the next episodes. */
    private fun switchTranslation(t: Translation) {
        pick = t.pickKey
        lifecycleScope.launch { container.prefs.setPick(session.anime.id, t.pickKey, t.author) }
        stream = stream.copy(current = t)
        loadEpisode(index, resumeAt = player.position.takeIf { player.loaded }, keep = true)
    }

    /** Past 85% the episode counts as watched on Anime 365 too (the site moves the list counter itself). */
    private fun markIfWatched() {
        val tr = stream.current ?: return
        if (player.duration <= 0 || player.position / player.duration < 0.85 || tr.id in marked) return
        if (container.account.profile == null) return
        marked += tr.id
        container.scope.launch { runCatching { container.a365.markWatched(tr.id) }.onFailure { marked -= tr.id } }
    }

    /** The stream broke mid-episode (links expire or the connection dropped): fetch fresh links and continue. */
    private fun recover() {
        if (reconnects >= 3) { error = "Поток оборвался. Проверьте соединение"; reconnecting = false; return }
        reconnects++
        reconnecting = true
        val at = player.position
        lifecycleScope.launch {
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
        val ep = session.episodes.getOrNull(index) ?: return
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
                action("back", android.R.drawable.ic_media_rew, "−10 секунд", 1),
                if (player.paused) action("toggle", android.R.drawable.ic_media_play, "Играть", 2)
                else action("toggle", android.R.drawable.ic_media_pause, "Пауза", 2),
                action("fwd", android.R.drawable.ic_media_ff, "+10 секунд", 3),
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

    private fun updatePipParams() { runCatching { setPictureInPictureParams(pipParams()) } }

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

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) { saveProgress(); player.pause(true) }
        // onStop while in PiP means the user dismissed the floating window.
        if (inPip) finish()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private companion object { const val PIP_ACTION = "app.neifur.PIP_CONTROL" }
}
