package app.neifur.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.neifur.BuildConfig
import app.neifur.R
import app.neifur.core.USER_AGENT
import dev.jdtech.mpv.MPVLib
import java.io.File
import kotlin.math.abs

data class Track(
    val id: Int,
    val type: String,
    val title: String?,
    val lang: String?,
    val codec: String?,
    val selected: Boolean,
    val default: Boolean = false,
    val forced: Boolean = false,
    val external: Boolean = false,
    val channels: Int? = null,
) {
    val info: TrackInfo by lazy { TrackNames.describe(this) }

    val codecLabel: String?
        get() = when (codec?.lowercase()) {
            null -> null
            "subrip" -> "SRT"; "hdmv_pgs_subtitle" -> "PGS"; "dvd_subtitle" -> "VobSub"; "webvtt" -> "VTT"
            "eac3" -> "E-AC-3"; "ac3" -> "AC-3"; "truehd" -> "TrueHD"
            else -> codec.uppercase()
        }

    val channelLabel: String?
        get() = when (channels) { null, 0 -> null; 1 -> "Моно"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"; else -> "$channels кан." }
}

/** A chapter mark from the file; names like "Opening" let the player offer a skip. */
data class Chapter(val start: Double, val title: String) {
    val kind: ChapterKind = ChapterKind.of(title)
}

enum class ChapterKind {
    Opening, Ending, Preview, Other;

    companion object {
        private val OP = Regex("""(?i)(?<![a-z])(op\d*|opening|intro|オープニング|片头|片頭)(?![a-z])""")
        private val ED = Regex("""(?i)(?<![a-z])(ed\d*|ending|outro|credits|エンディング|片尾)(?![a-z])""")
        private val PV = Regex("""(?i)(?<![a-z])(preview|next episode|次回予告|予告)(?![a-z])""")
        fun of(t: String) = when {
            OP.containsMatchIn(t) -> Opening
            ED.containsMatchIn(t) -> Ending
            PV.containsMatchIn(t) -> Preview
            else -> Other
        }
    }
}

/** Clamps a seek target to valid bounds, or returns null when duration is not known yet. */
internal fun clampSeekTarget(sec: Double, duration: Double): Double? {
    if (duration <= 0.0) return null
    return sec.coerceIn(0.0, (duration - 0.5).coerceAtLeast(0.0))
}

/** True when a rapid numeric property update changed less than [threshold] since the last emitted value. */
internal fun shouldThrottle(value: Double, last: Double, threshold: Double): Boolean =
    last >= 0.0 && abs(value - last) < threshold

/**
 * Thin, main-thread-friendly wrapper around libmpv. mpv renders ASS/SSA with libass (styles, embedded
 * fonts, karaoke) and decodes practically everything fansub groups ship, which is why it's here
 * instead of ExoPlayer.
 */
class MpvPlayer(context: Context) : MPVLib.EventObserver {
    private val main = Handler(Looper.getMainLooper())
    private val mpv: MPVLib = MPVLib.create(context.applicationContext) ?: error("libmpv failed to start")
    @Volatile private var released = false

    var position by mutableDoubleStateOf(0.0); private set
    var duration by mutableDoubleStateOf(0.0); private set
    var buffered by mutableDoubleStateOf(0.0); private set
    var paused by mutableStateOf(false); private set
    var buffering by mutableStateOf(true); private set
    var loaded by mutableStateOf(false); private set
    /** True once the current video has rendered to an active VO surface — hides stale frames while switching/resuming. */
    var videoReady by mutableStateOf(false); private set
    var ended by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    var speed by mutableDoubleStateOf(1.0); private set
    var tracks by mutableStateOf<List<Track>>(emptyList()); private set
    var chapters by mutableStateOf<List<Chapter>>(emptyList()); private set
    var subScale by mutableDoubleStateOf(1.0); private set
    var subDelay by mutableDoubleStateOf(0.0); private set
    var fill by mutableStateOf(false); private set
    /** Display size of the video (after aspect correction); 0 until the first frame is known. */
    var videoW by mutableIntStateOf(0); private set
    var videoH by mutableIntStateOf(0); private set

    /** Called once per file when its tracks are known — the place to apply remembered language choices. */
    var onFileLoaded: (() -> Unit)? = null

    /** The stream broke after it had started (expired link, dropped connection). */
    var onStreamError: (() -> Unit)? = null

    init {
        // libass needs a fallback font for subtitles that don't embed their own: <config-dir>/subfont.ttf.
        val dir = File(context.filesDir, "mpv").apply { mkdirs() }
        // A static SemiBold cut of Manrope: libass mis-spaces a variable font when a style asks for bold (words ran
        // together). Rewritten whenever the bundled file changes size, so updates replace an older copy.
        val font = File(dir, "subfont.ttf")
        runCatching {
            val bundled = context.resources.openRawResource(R.raw.subfont).use { it.readBytes() }
            if (font.length() != bundled.size.toLong()) font.writeBytes(bundled)
        }
        fun opt(k: String, v: String) = mpv.setOptionString(k, v)
        opt("config", "no"); opt("config-dir", dir.absolutePath)
        opt("profile", "fast")
        // Keep vo=null until SurfaceView reports a valid size so OpenGL never renders into a stale/zero viewport.
        opt("vo", "null"); opt("gpu-context", "android"); opt("opengl-es", "yes")
        opt("hwdec", "mediacodec,mediacodec-copy"); opt("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        opt("ao", "audiotrack,opensles")
        opt("force-window", "no"); opt("idle", "yes"); opt("keep-open", "yes")
        opt("hr-seek", "yes"); opt("hr-seek-framedrop", "yes")
        opt("cache", "yes"); opt("cache-secs", "120"); opt("demuxer-max-bytes", "64MiB"); opt("demuxer-max-back-bytes", "24MiB")
        opt("network-timeout", "12"); opt("user-agent", USER_AGENT)
        opt("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5")
        opt("alang", "ja,jpn,jp"); opt("slang", "ru,rus,en,eng"); opt("subs-fallback", "yes")
        // External subtitles are always added explicitly via sub-add; fuzzy auto-load duplicates downloaded .ass files.
        opt("sub-auto", "no"); opt("sub-font", "Manrope"); opt("sub-scale-with-window", "yes")
        opt("embeddedfonts", "yes"); opt("sub-fix-timing", "no")
        // No system fonts: on Xiaomi, libass picked a clock font (MiClockTibetan) for missing families and lost every space.
        // Fonts embedded in the file still win; anything else falls back to our own subfont.ttf.
        opt("sub-font-provider", "none")
        opt("screenshot-directory", dir.absolutePath)
        mpv.init()
        mpv.addObserver(this)
        val F = MPVLib.MpvFormat
        mapOf(
            "time-pos" to F.MPV_FORMAT_DOUBLE, "duration" to F.MPV_FORMAT_DOUBLE, "demuxer-cache-time" to F.MPV_FORMAT_DOUBLE,
            "pause" to F.MPV_FORMAT_FLAG, "paused-for-cache" to F.MPV_FORMAT_FLAG, "eof-reached" to F.MPV_FORMAT_FLAG,
            "seeking" to F.MPV_FORMAT_FLAG, "speed" to F.MPV_FORMAT_DOUBLE, "track-list" to F.MPV_FORMAT_NONE,
            "chapter-list" to F.MPV_FORMAT_NONE, "dwidth" to F.MPV_FORMAT_INT64, "dheight" to F.MPV_FORMAT_INT64,
        ).forEach { (k, f) -> mpv.observeProperty(k, f) }
    }

    // --- surface -------------------------------------------------------------------------------------
    private var surfaceAttached = false
    private var surfaceSized = false
    private var voEnabled = false
    private var surfaceW = 0
    private var surfaceH = 0
    /** Local files open faster than SurfaceView creates its buffer; wait until both surface and size exist. */
    private var pendingLoad: (() -> Unit)? = null
    private var revealTask: Runnable? = null

    fun attach(surface: Surface) {
        if (released) return
        mpv.attachSurface(surface)
        surfaceAttached = true
        if (surfaceSized) enableVoAndFlush()
    }

    fun resize(w: Int, h: Int) {
        if (released || w <= 0 || h <= 0) return
        surfaceW = w
        surfaceH = h
        surfaceSized = true
        mpv.setPropertyString("android-surface-size", "${w}x$h")
        if (surfaceAttached) enableVoAndFlush()
    }

    private fun enableVoAndFlush() {
        if (released || !surfaceAttached || !surfaceSized) return
        if (!voEnabled) {
            mpv.setPropertyString("force-window", "yes")
            mpv.setPropertyString("vo", "gpu")
            voEnabled = true
            // Resuming on pause won't emit PLAYBACK_RESTART; reveal once the fresh VO has drawn a frame.
            if (loaded && !videoReady) scheduleVideoReveal(140L)
        }
        val run = pendingLoad ?: return
        pendingLoad = null
        run()
    }

    private fun scheduleVideoReveal(delayMs: Long) {
        revealTask?.let { main.removeCallbacks(it) }
        val gen = loadGen
        val task = Runnable {
            revealTask = null
            if (!released && loadGen == gen && loaded && voEnabled) videoReady = true
        }
        revealTask = task
        main.postDelayed(task, delayMs)
    }

    private fun cancelVideoReveal() {
        revealTask?.let { main.removeCallbacks(it) }
        revealTask = null
    }

    /** Rebuilds the video output on the same surface: the cure for a stale picture after PiP or buffer resize. */
    fun reattach(surface: Surface, w: Int, h: Int) {
        if (released || !surface.isValid || w <= 0 || h <= 0) return
        detach()
        attach(surface)
        resize(w, h)
    }

    /** Shrinks the picture area from the right (fraction of the width), e.g. to make room for a side panel. */
    fun setRightMargin(fraction: Double) { if (!released) mpv.setPropertyDouble("video-margin-ratio-right", fraction) }

    fun detach() {
        if (released) return
        cancelVideoReveal()
        surfaceAttached = false
        surfaceSized = false
        voEnabled = false
        surfaceW = 0
        surfaceH = 0
        videoReady = false
        mpv.setPropertyString("vo", "null")
        mpv.setPropertyString("force-window", "no")
        mpv.detachSurface()
    }

    // --- control -------------------------------------------------------------------------------------
    /** A file is open or opening; replacing it makes mpv emit END_FILE for the old one first. */
    private var active = false
    private var loadGen = 0
    /** Number of files currently submitted to libmpv whose END_FILE has not arrived yet. */
    private var mpvOpenCount = 0
    private var skipEndFile = 0
    private var cacheWait = false
    private var seeking = false
    private var pendingPrefsGen = -1
    private var prefsFallbackTask: Runnable? = null

    /** An external subtitle file to attach once the video opens (Anime 365 serves subtitles separately). */
    private var pendingSub: Triple<String, String, String>? = null

    /** [subtitles] is (file, title, language): the title and language name the track in the subtitle list. */
    fun load(
        url: String,
        startAt: Double,
        subtitles: Triple<String, String, String>? = null,
        referrer: String? = null,
        startPaused: Boolean = false,
    ) {
        if (released) return
        cancelVideoReveal()
        cancelPrefsFallback()
        loadGen++
        val gen = loadGen
        pendingSub = subtitles
        pendingLoad = null
        active = true
        loaded = false; videoReady = false; ended = false; failed = false; buffering = true
        cacheWait = false; seeking = false
        position = startAt; duration = 0.0; buffered = 0.0
        lastPos = -1.0; lastBuffered = -1.0
        tracks = emptyList(); chapters = emptyList()
        val startNow = {
            if (!released && active && loadGen == gen) {
                if (mpvOpenCount > 0) skipEndFile += mpvOpenCount
                mpvOpenCount = 1
                mpv.setPropertyString("referrer", referrer.orEmpty())
                mpv.setOptionString("start", if (startAt > 0.5) String.format(java.util.Locale.US, "%.2f", startAt) else "none")
                mpv.command(arrayOf("loadfile", url, "replace"))
                mpv.setPropertyBoolean("pause", startPaused)
            }
        }
        if (surfaceAttached && surfaceSized && voEnabled) startNow() else pendingLoad = startNow
    }

    /**
     * Drops the open file while the next one is being looked up: the old episode must not keep playing (and
     * counting as watched) under the new episode's number.
     */
    fun stop() {
        if (released) return
        cancelVideoReveal()
        cancelPrefsFallback()
        loadGen++
        pendingLoad = null
        pendingSub = null
        val hadOpen = mpvOpenCount > 0
        if (hadOpen) {
            skipEndFile += mpvOpenCount
            mpvOpenCount = 0
        }
        active = false
        loaded = false; videoReady = false; ended = false; failed = false; buffering = true
        cacheWait = false; seeking = false
        position = 0.0; duration = 0.0; buffered = 0.0
        lastPos = -1.0; lastBuffered = -1.0
        videoW = 0; videoH = 0
        tracks = emptyList(); chapters = emptyList()
        if (subDelay != 0.0) changeSubDelay(0.0)
        if (hadOpen) mpv.command(arrayOf("stop"))
    }

    /** Voice-overs are Russian tracks; subtitle and raw releases keep the Japanese audio first. */
    fun preferAudio(langs: String) { if (!released) mpv.setPropertyString("alang", langs) }

    fun pause(p: Boolean) {
        if (released) return
        if (!p && ended) seekTo(0.0, exact = true)
        mpv.setPropertyBoolean("pause", p)
    }

    fun toggle() = pause(!paused)

    fun seekTo(sec: Double, exact: Boolean = true) {
        if (released || !loaded) return
        val t = clampSeekTarget(sec, duration) ?: return
        ended = false
        seeking = true
        position = t
        lastPos = -1.0
        val mode = if (exact) "absolute+exact" else "absolute+keyframes"
        mpv.command(arrayOf("seek", String.format(java.util.Locale.US, "%.3f", t), mode))
    }

    fun seekBy(delta: Double) = seekTo(position + delta, exact = true)

    fun changeSpeed(v: Double) { if (!released) { speed = v; mpv.setPropertyDouble("speed", v) } }

    fun select(type: String, id: Int?) {
        if (released) return
        val gen = loadGen
        mpv.setPropertyString(if (type == "audio") "aid" else "sid", id?.toString() ?: "no")
        // Reflect the choice at once; mpv's own track-list update follows and confirms it.
        tracks = tracks.map { if (it.type == type) it.copy(selected = it.id == id) else it }
        main.postDelayed({ if (!released && loadGen == gen) readTracks() }, 150)
    }

    fun changeSubScale(v: Double) { if (!released) { subScale = v; mpv.setPropertyDouble("sub-scale", v) } }

    fun changeSubDelay(v: Double) {
        if (released) return
        subDelay = Math.round(v * 10) / 10.0
        mpv.setPropertyDouble("sub-delay", subDelay)
    }

    /** Fill the screen by cropping (for 16:9 video on a taller display) instead of letterboxing. */
    fun changeFill(on: Boolean) { fill = on }

    fun release() {
        if (released) return
        released = true
        active = false
        loaded = false
        videoReady = false
        onFileLoaded = null; onStreamError = null
        runCatching { mpv.removeObserver(this) }
        runCatching { mpv.command(arrayOf("stop")) }
        runCatching { mpv.destroy() }
        // Clear pending UI callbacks only AFTER mpv.destroy() joins the native event thread.
        main.removeCallbacksAndMessages(null)
    }

    /** The chapter playing now, if the file has chapters. */
    fun chapterAt(t: Double): Pair<Chapter, Double>? {
        val list = chapters
        val i = list.indexOfLast { it.start <= t + 0.05 }
        if (i < 0) return null
        val end = list.getOrNull(i + 1)?.start ?: duration
        return list[i] to end
    }

    private fun cancelPrefsFallback() {
        prefsFallbackTask?.let { main.removeCallbacks(it) }
        prefsFallbackTask = null
        pendingPrefsGen = -1
    }

    private fun firePendingPrefs() {
        if (released || pendingPrefsGen != loadGen) return
        cancelPrefsFallback()
        onFileLoaded?.invoke()
    }

    private fun readTracks() {
        if (released) return
        val n = mpv.getPropertyInt("track-list/count") ?: 0
        tracks = (0 until n).mapNotNull { i ->
            val p = "track-list/$i"
            val type = mpv.getPropertyString("$p/type") ?: return@mapNotNull null
            if (type != "audio" && type != "sub") return@mapNotNull null
            Track(
                id = mpv.getPropertyInt("$p/id") ?: return@mapNotNull null, type = type,
                title = mpv.getPropertyString("$p/title"), lang = mpv.getPropertyString("$p/lang"),
                codec = mpv.getPropertyString("$p/codec"), selected = mpv.getPropertyBoolean("$p/selected") == true,
                default = mpv.getPropertyBoolean("$p/default") == true, forced = mpv.getPropertyBoolean("$p/forced") == true,
                external = mpv.getPropertyBoolean("$p/external") == true,
                channels = if (type == "audio") mpv.getPropertyInt("$p/demux-channel-count") else null,
            )
        }
        if (pendingPrefsGen == loadGen && tracks.any { it.type == "sub" && it.external }) {
            firePendingPrefs()
        }
    }

    private fun readChapters() {
        if (released) return
        val n = mpv.getPropertyInt("chapter-list/count") ?: 0
        chapters = (0 until n).mapNotNull { i ->
            val t = mpv.getPropertyDouble("chapter-list/$i/time") ?: return@mapNotNull null
            Chapter(t, mpv.getPropertyString("chapter-list/$i/title").orEmpty())
        }.sortedBy { it.start }
        if (BuildConfig.DEBUG) android.util.Log.i("NeifurPlayer", "chapters: " + chapters.joinToString { "${it.start.toInt()}s '${it.title}' ${it.kind}" })
    }

    /** Reads dwidth and dheight atomically as a pair so Compose never sees a transient mismatched square aspect. */
    private fun syncVideoDimensions() {
        if (released || !active) return
        val w = mpv.getPropertyInt("dwidth") ?: 0
        val h = mpv.getPropertyInt("dheight") ?: 0
        if (w > 0 && h > 0 && (w != videoW || h != videoH)) {
            videoW = w
            videoH = h
        }
    }

    // --- mpv event thread ----------------------------------------------------------------------------
    @Volatile private var lastPos = -1.0
    @Volatile private var lastBuffered = -1.0

    override fun eventProperty(property: String) {
        if (released) return
        when (property) {
            "track-list" -> main.post { if (!released && active) readTracks() }
            "chapter-list" -> main.post { if (!released && active) readChapters() }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        if (released) return
        when (property) {
            "dwidth", "dheight" -> if (value > 0) main.post { syncVideoDimensions() }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        if (released) return
        when (property) {
            "time-pos" -> if (!shouldThrottle(value, lastPos, 0.2)) {
                val advanced = lastPos >= 0.0 && value > lastPos && value - lastPos < 4.0
                lastPos = value
                main.post {
                    if (released || !loaded) return@post
                    // Time moving forward in small steps is the ground truth that nothing is stalled:
                    // mpv doesn't always send the matching "seeking=false" after a track switch.
                    if (advanced && !paused) { seeking = false; cacheWait = false; buffering = false }
                    if (!seeking) {
                        position = value
                        if (voEnabled && !videoReady) { cancelVideoReveal(); videoReady = true }
                    }
                }
            }
            "duration" -> main.post { if (!released && active) duration = value }
            "demuxer-cache-time" -> if (!shouldThrottle(value, lastBuffered, 0.5)) {
                lastBuffered = value
                main.post { if (!released && active) buffered = value }
            }
            "speed" -> main.post { if (!released) speed = value }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (released) return
        main.post {
            if (released) return@post
            when (property) {
                "pause" -> paused = value
                // Both flags feed the spinner; one turning off must not hide the other.
                "paused-for-cache" -> { cacheWait = value; buffering = cacheWait || seeking || !loaded }
                "seeking" -> { seeking = value; buffering = cacheWait || seeking || !loaded }
                "eof-reached" -> if (loaded) ended = value
            }
        }
    }

    override fun eventProperty(property: String, value: String) {}

    override fun event(eventId: Int) {
        if (released) return
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> main.post {
                // While skipEndFile > 0, an older file's END_FILE is still pending, so this FILE_LOADED is stale.
                if (released || !active || skipEndFile > 0) return@post
                loaded = true; buffering = cacheWait || seeking; failed = false
                syncVideoDimensions()
                val sub = pendingSub
                pendingSub = null
                val gen = loadGen
                if (sub != null) {
                    val (file, title, lang) = sub
                    runCatching { mpv.command(arrayOf("sub-add", file, "select", title, lang)) }
                    pendingPrefsGen = gen
                    readTracks()
                    // If the external track isn't in track-list yet, wait briefly for track-list event before applying prefs.
                    if (pendingPrefsGen == gen) {
                        val fallback = Runnable { firePendingPrefs() }
                        prefsFallbackTask = fallback
                        main.postDelayed(fallback, 220L)
                    }
                } else {
                    readTracks()
                    onFileLoaded?.invoke()
                }
                readChapters()
                if (voEnabled && !videoReady) scheduleVideoReveal(320L)
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> main.post {
                if (released || !active || skipEndFile > 0) return@post
                seeking = false
                buffering = cacheWait || !loaded
                if (loaded && voEnabled) {
                    syncVideoDimensions()
                    cancelVideoReveal()
                    videoReady = true
                }
            }
            // With keep-open a normal ending never unloads the file: END_FILE means the open failed or the stream broke.
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> main.post {
                if (released) return@post
                when {
                    skipEndFile > 0 -> skipEndFile--
                    else -> {
                        mpvOpenCount = (mpvOpenCount - 1).coerceAtLeast(0)
                        when {
                            !active -> {}
                            !loaded -> { failed = true; buffering = false; active = false; videoReady = false }
                            else -> { active = false; loaded = false; videoReady = false; buffering = true; onStreamError?.invoke() }
                        }
                    }
                }
            }
        }
    }
}
