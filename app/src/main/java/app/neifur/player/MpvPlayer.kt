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

/**
 * Thin, main-thread-friendly wrapper around libmpv. mpv renders ASS/SSA with libass (styles, embedded
 * fonts, karaoke) and decodes practically everything fansub groups ship, which is why it's here
 * instead of ExoPlayer.
 */
class MpvPlayer(context: Context) : MPVLib.EventObserver {
    private val main = Handler(Looper.getMainLooper())
    private val mpv: MPVLib = MPVLib.create(context.applicationContext) ?: error("libmpv failed to start")
    private var released = false

    var position by mutableDoubleStateOf(0.0); private set
    var duration by mutableDoubleStateOf(0.0); private set
    var buffered by mutableDoubleStateOf(0.0); private set
    var paused by mutableStateOf(false); private set
    var buffering by mutableStateOf(true); private set
    var loaded by mutableStateOf(false); private set
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
        opt("config", "yes"); opt("config-dir", dir.absolutePath)
        opt("profile", "fast")
        opt("vo", "gpu"); opt("gpu-context", "android"); opt("opengl-es", "yes")
        opt("hwdec", "mediacodec-copy"); opt("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        opt("ao", "audiotrack,opensles")
        opt("force-window", "no"); opt("idle", "yes"); opt("keep-open", "yes")
        opt("cache", "yes"); opt("cache-secs", "180"); opt("demuxer-max-bytes", "160MiB"); opt("demuxer-max-back-bytes", "48MiB")
        opt("network-timeout", "30"); opt("user-agent", USER_AGENT)
        opt("alang", "ja,jpn,jp"); opt("slang", "ru,rus,en,eng"); opt("subs-fallback", "yes")
        opt("sub-auto", "fuzzy"); opt("sub-font", "Manrope"); opt("sub-scale-with-window", "yes")
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
    fun attach(surface: Surface) {
        if (released) return
        mpv.attachSurface(surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu")
    }

    fun resize(w: Int, h: Int) { if (!released) mpv.setPropertyString("android-surface-size", "${w}x$h") }

    /** Rebuilds the video output on the same surface: the cure for a stale picture after PiP. */
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
        mpv.setPropertyString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
    }

    // --- control -------------------------------------------------------------------------------------
    /** A file is open or opening; replacing it makes mpv emit END_FILE for the old one first. */
    private var active = false
    @Volatile private var skipEndFile = 0
    private var cacheWait = false
    private var seeking = false

    /** An external subtitle file to attach once the video opens (Anime 365 serves subtitles separately). */
    private var pendingSub: Triple<String, String, String>? = null

    /** [subtitles] is (file, title, language): the title and language name the track in the subtitle list. */
    fun load(url: String, startAt: Double, subtitles: Triple<String, String, String>? = null, referrer: String? = null) {
        pendingSub = subtitles
        referrer?.let { mpv.setPropertyString("referrer", it) }
        if (active) skipEndFile++
        active = true
        loaded = false; ended = false; failed = false; buffering = true; position = startAt; duration = 0.0; buffered = 0.0
        tracks = emptyList(); chapters = emptyList()
        mpv.setOptionString("start", if (startAt > 1) String.format(java.util.Locale.US, "%.2f", startAt) else "none")
        mpv.command(arrayOf("loadfile", url, "replace"))
        mpv.setPropertyBoolean("pause", false)
    }

    /**
     * Raises subtitles above the player controls while they are on screen. sub-pos moves ASS subtitles too
     * (the margin options don't, short of overriding their styles), so the whole layer shifts up a little.
     */
    fun liftSubtitles(up: Boolean) {
        if (released) return
        mpv.setPropertyInt("sub-pos", if (up) 80 else 100)
    }

    /** Voice-overs are Russian tracks; subtitle and raw releases keep the Japanese audio first. */
    fun preferAudio(langs: String) { if (!released) mpv.setPropertyString("alang", langs) }

    fun pause(p: Boolean) {
        if (released) return
        if (!p && ended) seekTo(0.0)
        mpv.setPropertyBoolean("pause", p)
    }

    fun toggle() = pause(!paused)

    fun seekTo(sec: Double) {
        if (released || !loaded) return
        val t = sec.coerceIn(0.0, (duration - 0.5).coerceAtLeast(0.0))
        ended = false; position = t
        mpv.command(arrayOf("seek", t.toString(), "absolute"))
    }

    fun seekBy(delta: Double) = seekTo(position + delta)

    fun changeSpeed(v: Double) { if (!released) { speed = v; mpv.setPropertyDouble("speed", v) } }

    fun select(type: String, id: Int?) {
        if (released) return
        mpv.setPropertyString(if (type == "audio") "aid" else "sid", id?.toString() ?: "no")
        // Reflect the choice at once; mpv's own track-list update follows and confirms it.
        tracks = tracks.map { if (it.type == type) it.copy(selected = it.id == id) else it }
        main.postDelayed({ readTracks() }, 150)
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
        onFileLoaded = null; onStreamError = null
        main.removeCallbacksAndMessages(null)
        runCatching { mpv.removeObserver(this) }
        runCatching { mpv.command(arrayOf("stop")) }
        runCatching { mpv.destroy() }
    }

    /** The chapter playing now, if the file has chapters. */
    fun chapterAt(t: Double): Pair<Chapter, Double>? {
        val list = chapters
        val i = list.indexOfLast { it.start <= t + 0.05 }
        if (i < 0) return null
        val end = list.getOrNull(i + 1)?.start ?: duration
        return list[i] to end
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

    // --- mpv event thread ----------------------------------------------------------------------------
    private var lastPos = -1.0

    override fun eventProperty(property: String) {
        when (property) {
            "track-list" -> main.post { readTracks() }
            "chapter-list" -> main.post { readChapters() }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "dwidth" -> if (value > 0) main.post { videoW = value.toInt() }
            "dheight" -> if (value > 0) main.post { videoH = value.toInt() }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> if (abs(value - lastPos) >= 0.2) {
                val advanced = value > lastPos && value - lastPos < 2.0
                lastPos = value
                main.post {
                    if (!loaded) return@post
                    // Time moving forward in small steps is the ground truth that nothing is stalled:
                    // mpv doesn't always send the matching "seeking=false" after a track switch.
                    if (advanced && !paused) { seeking = false; cacheWait = false; buffering = false }
                    if (!seeking) position = value
                }
            }
            "duration" -> main.post { duration = value }
            "demuxer-cache-time" -> main.post { buffered = value }
            "speed" -> main.post { speed = value }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        main.post {
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
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> main.post {
                loaded = true; buffering = false; failed = false
                pendingSub?.let { (file, title, lang) -> runCatching { mpv.command(arrayOf("sub-add", file, "select", title, lang)) } }
                pendingSub = null
                readTracks(); readChapters()
                onFileLoaded?.invoke()
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> main.post { seeking = false; buffering = cacheWait }
            // With keep-open a normal ending never unloads the file: END_FILE means the open failed or the stream broke.
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> main.post {
                when {
                    skipEndFile > 0 -> skipEndFile--
                    !loaded -> { failed = true; buffering = false; active = false }
                    !released -> { active = false; loaded = false; buffering = true; onStreamError?.invoke() }
                }
            }
        }
    }
}
