package app.neifur.player

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.neifur.PlaySession
import app.neifur.container
import app.neifur.ui.Nf
import app.neifur.ui.Type
import app.neifur.ui.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun time(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

internal fun speedLabel(v: Double): String = if (v % 1.0 == 0.0) "${v.toInt()}×" else "${v.toString().trimEnd('0')}×"

/** A double-tap seek burst: consecutive double-taps on the same side within [windowMs] sum up visually (5, 10, 15 s...). */
internal data class SeekBurst(val side: Int, val seconds: Int, val at: Long)

internal fun nextSeekBurst(current: SeekBurst?, side: Int, stepSec: Int, nowMs: Long, windowMs: Long = 850L): SeekBurst {
    val secs = if (current != null && current.side == side && nowMs - current.at < windowMs) current.seconds + stepSec else stepSec
    return SeekBurst(side, secs, nowMs)
}

/** True when a finger hold may temporarily boost playback to 2× (never slows down 2×/3× playback). */
internal fun canHoldBoost(holdBoost: Boolean, loaded: Boolean, paused: Boolean, panelOpen: Boolean, currentSpeed: Double): Boolean =
    holdBoost && loaded && !paused && !panelOpen && currentSpeed < 2.0

/**
 * PlayerActivity is locked to sensorLandscape, but Android briefly measures it in portrait (maxW < maxH) for 1–2 frames
 * when launching from portrait MainActivity, unlocking a portrait lockscreen, or resuming from a portrait launcher.
 * Resolving to the target landscape dimensions immediately prevents a transient 1080×608 SurfaceView buffer and UI jump.
 */
internal fun resolvePlayerWindowSize(maxW: Int, maxH: Int, inPip: Boolean): Pair<Int, Int> =
    if (!inPip && maxW < maxH) maxH to maxW else maxW to maxH

/** Brightness / volume while a vertical swipe is in progress. */
private data class Level(val volume: Boolean, val value: Float)

@Composable
fun PlayerScreen(
    player: MpvPlayer, session: PlaySession, index: Int, resolving: Boolean, reconnecting: Boolean, error: String?, inPip: Boolean,
    stream: StreamUi, notice: String?, watched: Set<Int>,
    sleep: SleepTimer, sleepRemainingMs: () -> Long, autoNextBlocked: Boolean,
    onBack: () -> Unit, onRetry: () -> Unit, onEpisode: (Int) -> Unit, onPip: () -> Unit,
    onSelectTrack: (String, Track?) -> Unit, onSubScale: (Double) -> Unit,
    onTranslation: (app.neifur.data.a365.Translation) -> Unit, onHeight: (Int) -> Unit,
    onSleepMinutes: (Int) -> Unit, onSleepExtend: (Int) -> Unit, onSleepEnd: () -> Unit, onSleepOff: () -> Unit,
    onVideoBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val layoutDir = LocalLayoutDirection.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val scope = rememberCoroutineScope()
    val episodes = session.episodes
    val ep = episodes.getOrNull(index)
    val hasNext = index + 1 < episodes.size
    val hasPrev = index > 0

    var controls by remember { mutableStateOf(true) }
    var remaining by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var lockHint by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<PanelTab?>(null) }
    var menu by remember { mutableStateOf<QuickMenu?>(null) }
    val hasSubTracks by remember(player) { derivedStateOf { player.tracks.any { it.type == "sub" } } }
    val tabs = remember(episodes.size, hasSubTracks) {
        listOfNotNull(PanelTab.Translation, PanelTab.Episodes.takeIf { episodes.size > 1 }, PanelTab.Subs.takeIf { hasSubTracks })
    }
    var burst by remember { mutableStateOf<SeekBurst?>(null) }
    // Keep high-frequency gesture state inside State holders read only by leaf overlays so PlayerScreen doesn't recompose at 60–120 FPS.
    val levelState = remember { mutableStateOf<Level?>(null) }
    val scrubState = remember { mutableStateOf<Double?>(null) }
    val isScrubbing by remember { derivedStateOf { scrubState.value != null } }
    var boosting by remember { mutableStateOf(false) }
    var pop by remember { mutableStateOf<Pair<ImageVector, Long>?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    var autoNextCancelled by remember(session.anime.id, index) { mutableStateOf(false) }
    val stepSec by remember { ctx.container.prefs.seekStep }.collectAsState(10)
    val holdBoost by remember { ctx.container.prefs.holdBoost }.collectAsState(true)
    val sideGestures by remember { ctx.container.prefs.sideGestures }.collectAsState(true)
    val sideGesturesNow by rememberUpdatedState(sideGestures)
    val holdBoostNow by rememberUpdatedState(holdBoost)
    val panelNow by rememberUpdatedState(panel)
    val boostingNow by rememberUpdatedState(boosting)
    val dragActive = remember { object { var value = false } }

    // Auto-hide: controls go away after a few seconds of playback without touches.
    LaunchedEffect(controls, interaction, player.paused, panel, menu, isScrubbing, locked) {
        if (controls && !player.paused && panel == null && menu == null && !isScrubbing && !locked) { delay(3500); controls = false }
    }
    LaunchedEffect(burst?.at) { if (burst != null) { delay(850); burst = null } }
    LaunchedEffect(pop?.second) { if (pop != null) { delay(550); pop = null } }

    BackHandler(enabled = panel != null || menu != null || locked) {
        when { menu != null -> menu = null; panel != null -> panel = null; else -> lockHint++ }
    }

    val rawBusy = resolving || reconnecting || (player.buffering && error == null && !player.failed)
    // Short hiccups (a track switch, a seek inside the cache) shouldn't flash a spinner.
    val busy by produceState(rawBusy, rawBusy) { if (rawBusy) { delay(350); value = true } else value = false }
    val failure = error ?: if (player.failed) "Не удалось открыть видео. Возможно, ссылка устарела" else null

    // Auto-advance when an episode ends (paused while panel is open, player is paused, or sleep timer suppressed it).
    val countdown = remember { Animatable(0f) }
    val autoNextActive = player.ended && hasNext && failure == null && !autoNextCancelled && !autoNextBlocked && !player.paused && panel == null
    LaunchedEffect(autoNextActive, session.anime.id, index) {
        countdown.snapTo(0f)
        if (autoNextActive) {
            countdown.animateTo(1f, tween(7000, easing = LinearEasing))
            onEpisode(index + 1)
        }
    }

    fun seekStep(side: Int) {
        if (!player.loaded || player.duration <= 0.0) return
        val now = System.currentTimeMillis()
        player.seekBy(side * stepSec.toDouble())
        burst = nextSeekBurst(burst, side, stepSec, now)
    }

    fun jumpOpening() {
        if (!player.loaded || player.duration <= 0.0) return
        val now = System.currentTimeMillis()
        player.seekBy(JUMP_SECONDS.toDouble())
        burst = nextSeekBurst(burst, 1, JUMP_SECONDS, now)
        menu = null
        interaction++
    }

    fun togglePlay(fromGesture: Boolean) {
        val willPause = !player.paused && !player.ended
        player.toggle()
        // The big centre button already animates its own icon; a second icon on top of it read as a glitch.
        if (fromGesture && !controls) pop = (if (willPause) Icons.Rounded.Pause else Icons.Rounded.PlayArrow) to System.currentTimeMillis()
        interaction++
    }

    val skipAnnounced = remember(session.anime.id, index) { mutableSetOf<Double>() }

    val vw = player.videoW.takeIf { it > 0 } ?: 1920
    val vh = player.videoH.takeIf { it > 0 } ?: 1080
    // Keep the SurfaceView buffer fixed to the video's own resolution (capped at 1440p) so PiP, side-panel
    // slide and fill/fit never resize EGLSurface while paused (which otherwise causes double-frame corner
    // artifacts on entering PiP and zoom on exiting PiP). Combined with portraitHold + mediacodec-copy,
    // EGLSurface and android-surface-size stay 1:1 in sync across lockscreen and PiP.
    val (bufW, bufH) = remember(vw, vh) {
        val k = minOf(1f, 2560f / maxOf(vw, vh))
        (vw * k).roundToInt() to (vh * k).roundToInt()
    }
    val aspect = vw.toFloat() / vh
    val rightCutoutDp = with(density) { WindowInsets.displayCutout.getRight(density, layoutDir).toDp() }
    val targetPanelShift = playerPanelWidth(screenWidthDp) + 12.dp + rightCutoutDp
    // With the settings panel open the picture glides into the space left of it, subtitles included.
    val shift by animateDpAsState(
        if (panel != null && !inPip) targetPanelShift else 0.dp,
        spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow), label = "shift",
    )
    val fillNow = player.fill && panel == null && !inPip
    var winW by remember { mutableIntStateOf(0) }
    var winH by remember { mutableIntStateOf(0) }
    val inPortraitTransition = !inPip && winW > 0 && winH > 0 && winW < winH
    SideEffect { player.setPortraitTransition(inPortraitTransition) }
    // Keep the screen pure black for the first ~220 ms while the display finishes rotating from portrait MainActivity
    // into landscape PlayerActivity so controls never flash sideways mid-rotation (loading starts immediately in parallel).
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(220); settled = true }
    val hideForRotation = inPortraitTransition || (!settled && !inPip)
    val fixedBuf = remember { intArrayOf(0, 0) }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onSizeChanged { winW = it.width; winH = it.height }
    ) {
        AndroidView(
            factory = { c ->
                SurfaceView(c).apply {
                    fixedBuf[0] = bufW; fixedBuf[1] = bufH
                    holder.setFixedSize(bufW, bufH)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            if (fixedBuf[0] > 0 && fixedBuf[1] > 0) h.setFixedSize(fixedBuf[0], fixedBuf[1])
                            player.attach(h.surface)
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) = player.resize(w, hh)
                        override fun surfaceDestroyed(h: SurfaceHolder) = player.detach()
                    })
                }
            },
            update = { v ->
                if (fixedBuf[0] != bufW || fixedBuf[1] != bufH) {
                    fixedBuf[0] = bufW; fixedBuf[1] = bufH
                    v.holder.setFixedSize(bufW, bufH)
                }
            },
            modifier = Modifier
                .layout { m, c ->
                    val (fullW, fullH) = resolvePlayerWindowSize(c.maxWidth, c.maxHeight, inPip)
                    val areaW = (fullW - shift.roundToPx()).coerceAtLeast(1)
                    val areaH = fullH
                    val byHeight = areaH * aspect
                    val (w, h) = if (fillNow == (byHeight >= areaW)) byHeight to areaH.toFloat() else areaW.toFloat() to areaW / aspect
                    val pl = m.measure(Constraints.fixed(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)))
                    layout(c.maxWidth, c.maxHeight) { pl.place(((areaW - pl.width) / 2f).roundToInt(), ((areaH - pl.height) / 2f).roundToInt()) }
                }
                .onGloballyPositioned {
                    // Only report PiP bounds when stable in landscape (never during portrait rotation, panel slide or inside PiP).
                    if (!inPip && !inPortraitTransition && panelNow == null) onVideoBounds(it.boundsInWindow())
                },
        )
        // Instant black curtain hides stale SurfaceView buffers during episode switches and transient portrait->landscape rotation.
        val showCurtain = !player.videoReady || resolving || hideForRotation
        val curtainAlpha by animateFloatAsState(
            if (showCurtain) 1f else 0f,
            tween(if (showCurtain) 0 else 180),
            label = "curtain",
        )
        if (curtainAlpha > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = curtainAlpha }.background(Color.Black))
        if (inPip || hideForRotation) return@Box

        // ---- gestures (all disabled while locked) ----
        if (!locked) Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    val topEdge = with(density) { 56.dp.toPx() }
                    val sideEdge = with(density) { 36.dp.toPx() }
                    fun inDeadZone(o: Offset) = o.y < topEdge || o.y > size.height - sideEdge || o.x < sideEdge || o.x > size.width - sideEdge
                    var suppressTapUntil = 0L
                    detectTapGestures(
                        onTap = { o ->
                            if (System.currentTimeMillis() < suppressTapUntil) return@detectTapGestures
                            if (inDeadZone(o) && panelNow == null) return@detectTapGestures
                            if (panelNow != null) panel = null
                            else { controls = !controls; interaction++ }
                        },
                        onDoubleTap = { o ->
                            if (inDeadZone(o) || panelNow != null) return@detectTapGestures
                            when {
                                o.x < size.width / 3f -> seekStep(-1)
                                o.x > size.width * 2 / 3f -> seekStep(1)
                                else -> togglePlay(fromGesture = true)
                            }
                        },
                        // Declaring a long press prevents lifting after a 2× hold from triggering onTap.
                        onLongPress = {},
                        onPress = { offset ->
                            if (inDeadZone(offset) || panelNow != null) return@detectTapGestures
                            val before = player.speed
                            var boosted = false
                            val job = scope.launch {
                                delay(450)
                                if (!dragActive.value && canHoldBoost(holdBoostNow, player.loaded, player.paused, panelNow != null, player.speed)) {
                                    boosted = true
                                    boosting = true
                                    player.changeSpeed(2.0)
                                }
                            }
                            try {
                                tryAwaitRelease()
                            } finally {
                                job.cancel()
                                if (boosted) {
                                    boosting = false
                                    player.changeSpeed(before)
                                    suppressTapUntil = System.currentTimeMillis() + 350L
                                }
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    var mode = 0 // 1 seek, 2 brightness, 3 volume, -1 ignored
                    var acc = 0f
                    var startPos = 0.0
                    var startLevel = 0f
                    val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val topEdge = with(density) { 56.dp.toPx() }
                    val edge = with(density) { 40.dp.toPx() }
                    val window = (ctx as ComponentActivity).window
                    detectDragGestures(
                        onDragStart = { o ->
                            dragActive.value = false
                            // Leave the screen edges (especially top notification shade) to system gestures.
                            mode = if (boostingNow || o.y < topEdge || o.y > size.height - edge || o.x < edge || o.x > size.width - edge || panelNow != null) -1 else 0
                            acc = 0f; startPos = player.position
                        },
                        onDragEnd = {
                            if (mode == 1) scrubState.value?.let { player.seekTo(it, exact = true) }
                            if (mode == 2) levelState.value?.let { l -> scope.launch { ctx.container.prefs.saveBrightness(l.value) } }
                            dragActive.value = false; scrubState.value = null; mode = 0; levelState.value = null
                        },
                        onDragCancel = { dragActive.value = false; scrubState.value = null; mode = 0; levelState.value = null },
                    ) { change, drag ->
                        if (mode == -1 || boostingNow) return@detectDragGestures
                        if (mode == 0) {
                            mode = when {
                                abs(drag.x) > abs(drag.y) -> 1
                                !sideGesturesNow -> -1
                                change.position.x < size.width / 2 -> 2
                                else -> 3
                            }
                            if (mode == -1) return@detectDragGestures
                            dragActive.value = true
                            startLevel = if (mode == 2) window.attributes.screenBrightness.let { if (it < 0) 0.5f else it }
                            else audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVol.toFloat()
                        }
                        when (mode) {
                            1 -> if (player.loaded && player.duration > 0.0) {
                                acc += drag.x
                                // A full-width swipe covers 3 minutes: precise enough to land after an opening.
                                scrubState.value = (startPos + acc / size.width * 180.0).coerceIn(0.0, player.duration)
                            }
                            2 -> {
                                acc -= drag.y
                                val v = (startLevel + acc / (size.height * 0.75f)).coerceIn(0.01f, 1f)
                                window.attributes = window.attributes.apply { screenBrightness = v }
                                levelState.value = Level(false, v)
                            }
                            3 -> {
                                acc -= drag.y
                                val v = (startLevel + acc / (size.height * 0.75f)).coerceIn(0f, 1f)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * maxVol).roundToInt(), 0)
                                levelState.value = Level(true, v)
                            }
                        }
                    }
                }
        ) else Box(
            // Locked: swallow every touch, only reveal the unlock button.
            Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { lockHint++ }) }
        )

        // ---- controls ----
        val showControls = controls && panel == null && failure == null && !locked
        val topGradient = remember { Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)) }
        val bottomGradient = remember { Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))) }
        AnimatedVisibility(showControls, enter = fadeIn(tween(160)), exit = fadeOut(tween(260))) {
            Box(Modifier.fillMaxSize().background(Color(0x59000000))) {
                Box(Modifier.fillMaxWidth().height(130.dp).background(topGradient))
                Box(Modifier.fillMaxWidth().height(190.dp).align(Alignment.BottomCenter).background(bottomGradient))

                // Top bar
                Row(Modifier.fillMaxWidth().displayCutoutPadding().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onClick = onBack)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(session.anime.title, style = Type.Title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val subtitle = remember(ep, episodes.size, stream.current?.author, stream.height, stream.offline) {
                            listOfNotNull(
                                ep?.takeIf { episodes.size > 1 || (ep.number > 0 && ep.episodeFull.contains("сери", ignoreCase = true)) }?.label,
                                stream.current?.author,
                                stream.height.takeIf { it > 0 }?.let { "${it}p" },
                                if (stream.offline) "Офлайн" else null,
                            ).joinToString(" · ")
                        }
                        Text(subtitle, style = Type.Caption, color = Color(0xB3FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(10.dp))
                    SleepTopPill(
                        sleep = sleep,
                        sleepRemainingMs = sleepRemainingMs,
                        menuOpen = menu == QuickMenu.Sleep,
                        onToggle = { menu = if (menu == QuickMenu.Sleep) null else QuickMenu.Sleep; interaction++ },
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(
                        Modifier.height(46.dp).clip(CircleShape).background(Color(0x26FFFFFF)).border(1.dp, Color(0x1AFFFFFF), CircleShape),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CapsuleIcon(Icons.Outlined.PictureInPictureAlt, "Картинка в картинке", onClick = onPip)
                        Box(Modifier.width(1.dp).height(20.dp).background(Color(0x26FFFFFF)))
                        CapsuleIcon(Icons.Outlined.Lock, "Заблокировать экран") { locked = true; lockHint++ }
                    }
                }

                // Center transport: clean YouTube-style trio (Previous — Play/Pause — Next); seeking lives on crisp double-taps.
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    RoundButton(Icons.Rounded.SkipPrevious, "Предыдущая серия", enabled = hasPrev, size = 48.dp) { onEpisode(index - 1) }
                    Box(
                        Modifier.size(78.dp).clip(CircleShape).background(Color(0x38FFFFFF)).border(1.dp, Color(0x1AFFFFFF), CircleShape)
                            .pressable(0.9f) { togglePlay(fromGesture = false) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(34.dp), color = Color.White, strokeWidth = 3.dp)
                        else PlayPauseIcon(player.paused || player.ended)
                    }
                    RoundButton(Icons.Rounded.SkipNext, "Следующая серия", enabled = hasNext, size = 48.dp) { onEpisode(index + 1) }
                }

                // Bottom: timeline + quick settings
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().displayCutoutPadding().padding(horizontal = 22.dp).padding(bottom = 12.dp)) {
                    TimelineRow(
                        player = player,
                        scrubProvider = { scrubState.value },
                        remaining = remaining,
                        onToggleRemaining = { remaining = !remaining; interaction++ },
                        onScrub = { scrubState.value = it; interaction++ },
                        onCommit = { player.seekTo(it, exact = true); scrubState.value = null },
                        onCancel = { scrubState.value = null },
                    )
                    // Left group shrinks gracefully on narrow screens so right-side pills (Speed, FitScreen) never clip off-screen.
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TranslationChip(
                                stream = stream,
                                subsOn = player.tracks.any { it.type == "sub" && it.selected },
                                modifier = Modifier.weight(1f, fill = false),
                            ) { menu = null; panel = PanelTab.Translation }
                            if (episodes.size > 1) Chip(Icons.Rounded.VideoLibrary, "Серии") { menu = null; panel = PanelTab.Episodes }
                        }
                        if (canSkipOpening(player.loaded, player.ended, player.duration)) {
                            SkipOpeningPill(onClick = ::jumpOpening)
                        }
                        if (hasSubTracks) {
                            val on = player.tracks.any { it.type == "sub" && it.selected }
                            IconPill(if (on) Icons.Rounded.Subtitles else Icons.Rounded.SubtitlesOff, "Субтитры", highlight = on) { menu = null; panel = PanelTab.Subs }
                        }
                        if (stream.height > 0) TextPill(qualityLabel(stream.height), highlight = menu == QuickMenu.Quality) {
                            menu = if (menu == QuickMenu.Quality) null else QuickMenu.Quality; interaction++
                        }
                        TextPill(speedLabel(player.speed), highlight = player.speed != 1.0 || menu == QuickMenu.Speed) {
                            menu = if (menu == QuickMenu.Speed) null else QuickMenu.Speed; interaction++
                        }
                        IconPill(if (player.fill) Icons.Rounded.FitScreen else Icons.Rounded.AspectRatio, if (player.fill) "Вписать" else "Заполнить") {
                            player.changeFill(!player.fill); interaction++
                        }
                    }
                }
            }
        }

        // Spinner while the controls are hidden, with a word about what's happening.
        if (busy && !showControls && failure == null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(42.dp), color = Color.White, strokeWidth = 3.dp)
            val msg = when { reconnecting -> "Восстанавливаю поток…"; resolving -> "Загружаю серию…"; else -> null }
            if (msg != null) Text(msg, style = Type.Label, color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 14.dp))
        }
        if ((resolving || reconnecting) && showControls) Text(
            if (reconnecting) "Восстанавливаю поток…" else "Загружаю серию…", style = Type.Label, color = Color(0xCCFFFFFF),
            modifier = Modifier.align(Alignment.Center).padding(top = 130.dp),
        )

        // ---- double-tap seek ripples (above the controls, so they read even when those are shown) ----
        SeekRipple(burst, -1, Modifier.align(Alignment.CenterStart))
        SeekRipple(burst, 1, Modifier.align(Alignment.CenterEnd))

        // ---- play/pause pop ----
        PopOverlay(pop, Modifier.align(Alignment.Center))

        // ---- brightness / volume side bars (isolated so 60–120 FPS drags never recompose PlayerScreen) ----
        LevelBarsOverlay(levelProvider = { levelState.value })

        // ---- swipe-seek preview (isolated + retains last value during fade-out) ----
        ScrubPreviewOverlay(
            scrubProvider = { scrubState.value },
            player = player,
            showControls = showControls,
            modifier = Modifier.align(Alignment.Center),
        )

        AnimatedVisibility(boosting, Modifier.align(Alignment.TopCenter).displayCutoutPadding().padding(top = 22.dp), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            Row(Modifier.clip(CircleShape).background(Color(0xCC0E0E15)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("2× пока держите палец", style = Type.Label, color = Color.White)
            }
        }

        // ---- skip opening / ending (mounted only when the file actually has chapters — zero idle recompositions) ----
        if (failure == null && !locked && panel == null && player.chapters.isNotEmpty()) SkipButton(
            player = player, hasNext = hasNext, controlsShown = showControls, announced = skipAnnounced,
            modifier = Modifier.align(Alignment.BottomEnd),
            onNext = { onEpisode(index + 1) },
        )

        // ---- next episode card (lifts above controls just like SkipButton) ----
        val nextBottom by animateDpAsState(if (showControls) 118.dp else 26.dp, spring(dampingRatio = 0.8f, stiffness = 300f), label = "nextY")
        AnimatedVisibility(
            player.ended && hasNext && failure == null && !autoNextCancelled && !autoNextBlocked && panel == null,
            Modifier.align(Alignment.BottomEnd).displayCutoutPadding().padding(end = 26.dp, bottom = nextBottom),
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut(),
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xF0151520)).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(22.dp)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.clip(RoundedCornerShape(16.dp)).pressable { onEpisode(index + 1) }.padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { countdown.value }, Modifier.size(40.dp), color = Nf.Violet, trackColor = Color(0x33FFFFFF), strokeWidth = 3.dp)
                        Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Следующая серия", style = Type.Caption, color = Color(0xB3FFFFFF))
                        Text(episodes[index + 1].label, style = Type.Title, color = Color.White)
                    }
                }
                Box(Modifier.size(36.dp).clip(CircleShape).pressable(0.85f) { autoNextCancelled = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Отменить автопереход", tint = Color(0xB3FFFFFF), modifier = Modifier.size(18.dp))
                }
            }
        }

        // ---- lock hint / unlock ----
        val lockVisible by produceState(false, lockHint, locked) {
            value = locked && lockHint > 0
            if (value) { delay(2500); value = false }
        }
        AnimatedVisibility(lockVisible, Modifier.align(Alignment.CenterStart).displayCutoutPadding().padding(start = 36.dp), enter = fadeIn() + scaleIn(initialScale = 0.85f), exit = fadeOut()) {
            Row(
                Modifier.clip(CircleShape).background(Color(0xE6151520)).border(1.dp, Color(0x26FFFFFF), CircleShape)
                    .pressable { locked = false; controls = true; interaction++ }.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.LockOpen, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Разблокировать", style = Type.Label, color = Color.White)
            }
        }

        // ---- failure ----
        if (failure == app.neifur.data.a365.SubscriptionRequired.MESSAGE) Paywall(Modifier.align(Alignment.Center), onBack)
        else if (failure != null) Column(Modifier.align(Alignment.Center).padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Не получилось запустить", style = Type.Display, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(failure, style = Type.Body, color = Color(0xB3FFFFFF))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.clip(CircleShape).background(Color.White).pressable(onClick = onRetry).padding(horizontal = 22.dp, vertical = 12.dp)) { Text("Повторить", style = Type.Label, color = Color.Black) }
                Box(Modifier.clip(CircleShape).background(Color(0x33FFFFFF)).pressable(onClick = onBack).padding(horizontal = 22.dp, vertical = 12.dp)) { Text("Назад", style = Type.Label, color = Color.White) }
            }
        }

        // ---- settings panel ----
        QuickMenuHost(
            menu.takeIf { showControls }, player, stream,
            sleep = sleep, sleepRemainingMs = sleepRemainingMs,
            onHeight = onHeight,
            onSleepMinutes = onSleepMinutes, onSleepExtend = onSleepExtend, onSleepEnd = onSleepEnd, onSleepOff = onSleepOff,
            onClose = { menu = null },
        )
        PlayerPanel(
            tab = panel, tabs = tabs, player = player, stream = stream, episodes = episodes, index = index, watched = watched,
            onTab = { panel = it }, onClose = { panel = null },
            onSelectTrack = onSelectTrack, onSubScale = onSubScale,
            onTranslation = { onTranslation(it); panel = null }, onEpisode = { onEpisode(it); panel = null },
        )

        // ---- a word about an automatic change (studio missing for this episode) ----
        var lastNotice by remember { mutableStateOf("") }
        if (notice != null) lastNotice = notice
        AnimatedVisibility(
            notice != null && panel == null,
            Modifier.align(Alignment.TopCenter).displayCutoutPadding().padding(horizontal = 24.dp).padding(top = 84.dp),
            enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 3 },
        ) {
            Text(
                lastNotice, style = Type.Label, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xE6151520)).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun SleepTopPill(sleep: SleepTimer, sleepRemainingMs: () -> Long, menuOpen: Boolean, onToggle: () -> Unit) {
    val sleepBadge = formatSleepBadge(sleep, sleepRemainingMs())
    var lastSleepBadge by remember { mutableStateOf("") }
    if (sleepBadge != null) lastSleepBadge = sleepBadge
    val sleepActive = sleep != SleepTimer.Off
    val sleepLit = sleepActive || menuOpen
    val sleepBg by animateColorAsState(if (sleepLit) Nf.Violet.copy(alpha = 0.24f) else Color(0x26FFFFFF), tween(200), label = "sbg")
    val sleepBr by animateColorAsState(if (sleepLit) Nf.Violet.copy(alpha = 0.55f) else Color(0x1AFFFFFF), tween(200), label = "sbr")
    val sleepIconTint by animateColorAsState(if (sleepActive) Nf.Violet else Color.White, tween(200), label = "sic")
    Row(
        Modifier.height(46.dp).widthIn(min = 46.dp).clip(CircleShape)
            .background(sleepBg).border(1.dp, sleepBr, CircleShape)
            .pressable(0.88f, onClick = onToggle)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (sleepActive) Icons.Rounded.Bedtime else Icons.Outlined.Bedtime,
            "Таймер сна", tint = sleepIconTint,
            modifier = Modifier.size(20.dp),
        )
        AnimatedVisibility(
            sleepBadge != null,
            enter = expandHorizontally(spring(dampingRatio = 0.85f, stiffness = 420f), expandFrom = Alignment.Start) + fadeIn(tween(180, 60)),
            exit = shrinkHorizontally(spring(dampingRatio = 0.9f, stiffness = 480f), shrinkTowards = Alignment.Start) + fadeOut(tween(100)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(6.dp))
                Text(lastSleepBadge, style = Type.Label, color = Color.White, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

@Composable
private fun TimelineRow(
    player: MpvPlayer,
    scrubProvider: () -> Double?,
    remaining: Boolean,
    onToggleRemaining: () -> Unit,
    onScrub: (Double) -> Unit,
    onCommit: (Double) -> Unit,
    onCancel: () -> Unit,
) {
    val dur = player.duration
    val pos = scrubProvider() ?: if (dur > 0.0) player.position else 0.0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(time(pos), style = Type.Label, color = Color.White, modifier = Modifier.widthIn(min = 44.dp))
        SeekBar(
            player = player, scrubProvider = scrubProvider, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            onScrub = onScrub, onCommit = onCommit, onCancel = onCancel,
        )
        Text(
            when {
                dur <= 0.0 -> "—:——"
                remaining -> "−" + time((dur - pos).coerceAtLeast(0.0))
                else -> time(dur)
            },
            style = Type.Label, color = Color(0xCCFFFFFF),
            modifier = Modifier.widthIn(min = 44.dp).clip(RoundedCornerShape(6.dp)).pressable(0.92f, onClick = onToggleRemaining),
        )
    }
}

@Composable
private fun PopOverlay(pop: Pair<ImageVector, Long>?, modifier: Modifier) {
    var lastIcon by remember { mutableStateOf(Icons.Rounded.PlayArrow) }
    if (pop != null) lastIcon = pop.first
    AnimatedVisibility(pop != null, modifier, enter = scaleIn(initialScale = 0.7f) + fadeIn(tween(90)), exit = scaleOut(targetScale = 1.25f) + fadeOut(tween(300))) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(Color(0x66000000)), contentAlignment = Alignment.Center) {
            Icon(lastIcon, null, tint = Color.White, modifier = Modifier.size(46.dp))
        }
    }
}

@Composable
private fun ScrubPreviewOverlay(scrubProvider: () -> Double?, player: MpvPlayer, showControls: Boolean, modifier: Modifier) {
    val scrub = scrubProvider()
    var lastScrub by remember { mutableDoubleStateOf(0.0) }
    var lastBasePos by remember { mutableDoubleStateOf(0.0) }
    if (scrub != null) { lastScrub = scrub; lastBasePos = player.position }
    AnimatedVisibility(scrub != null && !showControls, modifier, enter = fadeIn(tween(100)), exit = fadeOut(tween(200))) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xCC0E0E15)).padding(horizontal = 24.dp, vertical = 14.dp)) {
            Text(time(lastScrub), style = Type.Display, color = Color.White)
            val d = lastScrub - lastBasePos
            Text((if (d >= 0) "+" else "−") + time(abs(d)), style = Type.Label, color = Color(0xB3FFFFFF))
            player.chapterAt(lastScrub)?.first?.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = Type.Caption, color = Nf.Violet, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun BoxScope.LevelBarsOverlay(levelProvider: () -> Level?) {
    val level = levelProvider()
    LevelBar(level?.takeIf { !it.volume }, Icons.Rounded.LightMode, Modifier.align(Alignment.CenterStart).displayCutoutPadding().padding(start = 40.dp))
    LevelBar(level?.takeIf { it.volume }, Icons.AutoMirrored.Rounded.VolumeUp, Modifier.align(Alignment.CenterEnd).displayCutoutPadding().padding(end = 40.dp))
}

@Composable
private fun SeekRipple(burst: SeekBurst?, side: Int, modifier: Modifier) {
    val active = burst?.side == side
    val secs = remember { mutableIntStateOf(10) }
    if (active) secs.intValue = burst!!.seconds
    AnimatedVisibility(active, modifier, enter = fadeIn(tween(120)), exit = fadeOut(tween(340))) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(0.36f)
                .drawWithCache {
                    // Cache the soft half-moon gradient for the current size so fade frames never re-allocate Brush.
                    val r = size.height * 0.95f
                    val cx = if (side < 0) -r * 0.45f else size.width + r * 0.45f
                    val center = Offset(cx, size.height / 2)
                    val brush = Brush.radialGradient(listOf(Color(0x4DFFFFFF), Color(0x1AFFFFFF)), center, r)
                    onDrawBehind { drawCircle(brush, r, center) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (side < 0) Icons.Rounded.KeyboardDoubleArrowLeft else Icons.Rounded.KeyboardDoubleArrowRight, null, tint = Color.White, modifier = Modifier.size(40.dp))
                AnimatedContent(secs.intValue, label = "secs", transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith fadeOut(tween(80)) }) { s ->
                    Text((if (side < 0) "−" else "+") + "$s с", style = Type.Display, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun LevelBar(level: Level?, icon: ImageVector, modifier: Modifier) {
    var last by remember { mutableFloatStateOf(0f) }
    if (level != null) last = level.value
    AnimatedVisibility(level != null, modifier, enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.9f), exit = fadeOut(tween(400))) {
        val v by animateFloatAsState(last, spring(stiffness = Spring.StiffnessHigh), label = "lvl")
        Column(
            Modifier.clip(RoundedCornerShape(26.dp)).background(Color(0xCC0E0E15)).padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("${(v * 100).roundToInt()}", style = Type.Label, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(6.dp).height(150.dp).clip(CircleShape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(v).clip(CircleShape).background(Color.White))
            }
            Spacer(Modifier.height(10.dp))
            Icon(if (icon == Icons.AutoMirrored.Rounded.VolumeUp && v <= 0.001f) Icons.AutoMirrored.Rounded.VolumeOff else icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * "Skip opening" while inside an opening/ending chapter. It shows itself for a few seconds with a draining
 * timer, then steps aside for those who watch openings; it stays available among the controls.
 */
@Composable
private fun SkipButton(
    player: MpvPlayer, hasNext: Boolean, controlsShown: Boolean, announced: MutableSet<Double>, modifier: Modifier, onNext: () -> Unit,
) {
    val now = player.chapterAt(player.position)
    val kind = now?.first?.kind
    val remainingInChapter = now?.let { it.second - player.position } ?: 0.0
    val offer = when {
        now == null || remainingInChapter < 2 -> null
        kind == ChapterKind.Opening -> "Пропустить опенинг"
        kind == ChapterKind.Ending && hasNext && now.second >= player.duration - 120 -> "Следующая серия"
        kind == ChapterKind.Ending -> "Пропустить эндинг"
        kind == ChapterKind.Preview && hasNext -> "Следующая серия"
        else -> null
    }
    val chapterStart = now?.first?.start
    val timer = remember { Animatable(0f) }
    var autoShown by remember { mutableStateOf(false) }
    // Restarts each time playback enters a skippable chapter (including after seeking back into it).
    LaunchedEffect(offer != null, chapterStart) {
        // Leaving the chapter re-arms it, so seeking back into the opening offers the skip again.
        if (offer == null || chapterStart == null) { autoShown = false; announced.clear(); return@LaunchedEffect }
        // Already offered for this pass (e.g. we're back from PiP): don't pop up a second time.
        if (!announced.add(chapterStart)) { autoShown = false; return@LaunchedEffect }
        autoShown = true
        timer.snapTo(1f)
        timer.animateTo(0f, tween(7000, easing = LinearEasing))
        autoShown = false
    }
    val visible = offer != null && (controlsShown || autoShown)
    val bottom by animateDpAsState(if (controlsShown) 118.dp else 34.dp, spring(dampingRatio = 0.8f, stiffness = 300f), label = "skipY")
    var last by remember { mutableStateOf("") }
    var lastTargetEnd by remember { mutableDoubleStateOf(-1.0) }
    if (offer != null && now != null) {
        last = offer
        lastTargetEnd = now.second
    }
    AnimatedVisibility(
        visible, modifier.displayCutoutPadding().padding(end = 26.dp, bottom = bottom),
        enter = fadeIn(tween(220)) + slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 2 } + scaleIn(initialScale = 0.92f),
        exit = fadeOut(tween(260)) + slideOutHorizontally(tween(320)) { it / 3 } + scaleOut(targetScale = 0.94f),
    ) {
        val showTimer = autoShown && !controlsShown
        val timerAlpha by animateFloatAsState(if (showTimer) 1f else 0f, tween(if (showTimer) 0 else 250), label = "drain")
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White)
                // Read timer.value only inside drawBehind so the 7 s countdown causes zero recompositions.
                .drawBehind {
                    val drain = timer.value * timerAlpha
                    if (drain > 0f) drawRect(Color(0xFFE2DCF2), size = Size(size.width * drain, size.height))
                }
                .pressable {
                    if (last == "Следующая серия") onNext()
                    else if (lastTargetEnd > 0.0) player.seekTo(lastTargetEnd + 0.1, exact = true)
                }
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(last, style = Type.Title.copy(fontSize = 15.sp), color = Color.Black)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.SkipNext, null, tint = Color.Black, modifier = Modifier.size(20.dp))
        }
    }
}

/** Standard anime opening length (matches Anime 365's web player). */
internal const val JUMP_SECONDS = 85

internal fun canSkipOpening(loaded: Boolean, ended: Boolean, duration: Double): Boolean =
    loaded && !ended && duration > 180.0

/** Compact round "+85" skip in the bottom bar: skips a standard opening without cluttering the video frame. */
@Composable
private fun SkipOpeningPill(onClick: () -> Unit) {
    Column(
        Modifier.size(42.dp).clip(CircleShape).background(Color(0x1FFFFFFF))
            .semantics { contentDescription = "Пропустить опенинг (+$JUMP_SECONDS секунд)" }
            .pressable(0.9f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.KeyboardDoubleArrowRight, null, tint = Color.White, modifier = Modifier.size(16.dp).offset(y = 1.dp))
        Text(
            "+$JUMP_SECONDS",
            style = Type.Caption.copy(
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            color = Color(0xE6FFFFFF),
            maxLines = 1,
            modifier = Modifier.offset(y = (-1).dp),
        )
    }
}

/** Pause and play morph into each other (rotate + scale + fade) instead of two icons sharing the button. */
@Composable
private fun PlayPauseIcon(paused: Boolean) {
    val t by animateFloatAsState(if (paused) 1f else 0f, spring(dampingRatio = 0.75f, stiffness = 700f), label = "pp")
    Box(contentAlignment = Alignment.Center) {
        Icon(
            Icons.Rounded.Pause, "Пауза", tint = Color.White,
            modifier = Modifier.size(46.dp).graphicsLayer {
                alpha = (1f - t * 1.6f).coerceIn(0f, 1f); val k = 1f - 0.45f * t; scaleX = k; scaleY = k; rotationZ = -90f * t
            },
        )
        Icon(
            Icons.Rounded.PlayArrow, "Играть", tint = Color.White,
            modifier = Modifier.size(48.dp).graphicsLayer {
                alpha = ((t - 0.35f) * 1.6f).coerceIn(0f, 1f); val k = 0.55f + 0.45f * t; scaleX = k; scaleY = k; rotationZ = 90f * (1f - t)
            },
        )
    }
}

@Composable
private fun CapsuleIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(width = 52.dp, height = 46.dp).pressable(0.86f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, enabled: Boolean = true, size: androidx.compose.ui.unit.Dp = 46.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0x26FFFFFF)).border(1.dp, Color(0x14FFFFFF), CircleShape)
            .pressable(0.86f, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (enabled) Color.White else Color(0x4DFFFFFF), modifier = Modifier.size(size * 0.54f)) }
}

/**
 * The translation playing: the player's main switch on Anime 365. Burned-in subtitles say so here,
 * since there is no subtitle track to show for them.
 */
@Composable
private fun TranslationChip(stream: StreamUi, subsOn: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = stream.current
    val tint = kindTint(t?.kind)
    val kindText = when {
        t == null -> null
        stream.isSubs && !stream.externalSubs && !subsOn -> "вшитые субтитры"
        else -> t.kind.inline
    }
    Row(
        modifier.height(42.dp).widthIn(max = 300.dp).clip(CircleShape).background(Color(0x26FFFFFF)).border(1.dp, tint.copy(alpha = 0.35f), CircleShape)
            .pressable(0.95f, onClick = onClick).padding(start = 5.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
            Icon(kindIcon(t?.kind), null, tint = Color.White, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(9.dp))
        Text(t?.author ?: "Перевод", style = Type.Label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (kindText != null) Text("  ·  $kindText", style = Type.Label, color = Color(0x99FFFFFF), maxLines = 1, softWrap = false)
        Icon(Icons.Rounded.UnfoldMore, null, tint = Color(0x99FFFFFF), modifier = Modifier.padding(start = 4.dp).size(16.dp))
    }
}

private fun qualityLabel(h: Int) = "${h}p"

@Composable
private fun TextPill(text: String, highlight: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(42.dp).widthIn(min = 52.dp).clip(CircleShape)
            .background(if (highlight) Nf.Violet.copy(alpha = 0.3f) else Color(0x1FFFFFFF))
            .then(if (highlight) Modifier.border(1.dp, Nf.Violet.copy(alpha = 0.5f), CircleShape) else Modifier)
            .pressable(0.92f, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.Label, color = Color.White, maxLines = 1) }
}

@Composable
private fun IconPill(icon: ImageVector, label: String, highlight: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape)
            .background(if (highlight) Nf.Violet.copy(alpha = 0.3f) else Color(0x1FFFFFFF))
            .then(if (highlight) Modifier.border(1.dp, Nf.Violet.copy(alpha = 0.5f), CircleShape) else Modifier)
            .pressable(0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(19.dp)) }
}

@Composable
private fun Chip(icon: ImageVector, text: String, highlight: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (highlight) Nf.Violet.copy(alpha = 0.28f) else Color(0x1FFFFFFF))
            .then(if (highlight) Modifier.border(1.dp, Nf.Violet.copy(alpha = 0.5f), CircleShape) else Modifier)
            .pressable(0.94f, onClick = onClick).padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(text, style = Type.Label, color = Color.White, maxLines = 1)
    }
}

private val OpPlayed = Color(0xFF6A4FD0)         // a deeper violet: "this stretch is the opening/ending"
private val OpAhead = Nf.Violet.copy(alpha = 0.32f)

/** Russian label for a chapter, or null when the name carries no meaning ("Episode", "Chapter 3"). */
internal fun chapterLabel(c: Chapter): String? = when (c.kind) {
    ChapterKind.Opening -> "Опенинг"
    ChapterKind.Ending -> "Эндинг"
    ChapterKind.Preview -> "Анонс"
    ChapterKind.Other -> c.title.takeIf { it.isNotBlank() && !Regex("""(?i)^(episode|chapter|part|глава|часть)\s*\d*$""").matches(it.trim()) }
}

/**
 * Timeline split into chapter segments (YouTube-style gaps), openings/endings tinted inside the line itself,
 * a thumb that swells under the finger, a light tick when the finger crosses into another chapter, and a
 * time bubble while scrubbing.
 */
@Composable
private fun SeekBar(
    player: MpvPlayer,
    scrubProvider: () -> Double?,
    modifier: Modifier,
    onScrub: (Double) -> Unit,
    onCommit: (Double) -> Unit,
    onCancel: () -> Unit,
) {
    val view = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    var last by remember { mutableDoubleStateOf(0.0) }
    var barWidth by remember { mutableIntStateOf(1) }
    val grow by animateFloatAsState(if (dragging) 1f else 0f, label = "thumb")
    val duration = player.duration
    val chapters = player.chapters
    val dur by rememberUpdatedState(duration)
    val chaptersNow by rememberUpdatedState(chapters)
    var lastSegment by remember { mutableIntStateOf(-1) }

    // Precompute normalized chapter split fractions and kinds once per episode instead of allocating lists on every Canvas frame.
    val chapterSplits = remember(chapters, duration) {
        if (duration <= 0.0 || chapters.isEmpty()) floatArrayOf(0f, 1f) to listOf(ChapterKind.Other)
        else {
            val splits = chapters.filter { it.start > 1.0 && it.start < duration - 1.0 }
            val fracs = FloatArray(splits.size + 2).apply {
                this[0] = 0f
                splits.forEachIndexed { i, c -> this[i + 1] = (c.start / duration).toFloat().coerceIn(0f, 1f) }
                this[splits.size + 1] = 1f
            }
            val kinds = buildList(splits.size + 1) {
                add(chapters.firstOrNull()?.takeIf { it.start <= 1.0 }?.kind ?: ChapterKind.Other)
                splits.forEach { add(it.kind) }
            }
            fracs to kinds
        }
    }

    fun scrubTo(x: Float, w: Int) {
        if (dur <= 0.0 || w <= 0) return
        last = (x / w).coerceIn(0f, 1f) * dur
        val seg = chaptersNow.indexOfLast { it.start <= last }
        if (lastSegment != -1 && seg != lastSegment) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        lastSegment = seg
        onScrub(last)
    }

    Box(modifier.height(40.dp).onSizeChanged { barWidth = it.width }) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { o -> if (dur > 0.0 && size.width > 0) onCommit((o.x / size.width).coerceIn(0f, 1f) * dur) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o ->
                            if (dur <= 0.0) return@detectHorizontalDragGestures
                            dragging = true; lastSegment = -1; scrubTo(o.x, size.width)
                        },
                        onDragEnd = {
                            if (!dragging) return@detectHorizontalDragGestures
                            dragging = false; onCommit(last)
                        },
                        onDragCancel = {
                            if (!dragging) return@detectHorizontalDragGestures
                            dragging = false; onCancel()
                        },
                    ) { change, _ -> if (dragging) scrubTo(change.position.x, size.width) }
                }
        ) {
            val base = (4 + 2 * grow).dp.toPx()
            val gap = 3.dp.toPx()
            val position = scrubProvider() ?: if (duration > 0.0) player.position else 0.0
            val px = if (duration > 0.0) (position / duration).toFloat().coerceIn(0f, 1f) * size.width else 0f
            val bx = if (duration > 0.0) (player.buffered / duration).toFloat().coerceIn(0f, 1f) * size.width else 0f
            val w = size.width
            val (fracs, kinds) = chapterSplits
            for (k in 0 until fracs.size - 1) {
                val x0 = fracs[k] * w + if (k > 0) gap / 2 else 0f
                val x1 = fracs[k + 1] * w - if (k < fracs.size - 2) gap / 2 else 0f
                if (x1 <= x0) continue
                val special = kinds.getOrNull(k).let { it == ChapterKind.Opening || it == ChapterKind.Ending }
                // The chapter under the finger grows a little, like on YouTube.
                val active = dragging && px >= fracs[k] * w && px <= fracs[k + 1] * w
                val h = base + if (active) 2.dp.toPx() else 0f
                val y = (size.height - h) / 2
                val r = CornerRadius(h / 2)
                drawRoundRect(if (special) OpAhead else Color(0x40FFFFFF), Offset(x0, y), Size(x1 - x0, h), r)
                val b = minOf(bx, x1)
                if (b > x0 && !special) drawRoundRect(Color(0x59FFFFFF), Offset(x0, y), Size(b - x0, h), r)
                val p = minOf(px, x1)
                if (p > x0) drawRoundRect(if (special) OpPlayed else Nf.Violet, Offset(x0, y), Size(p - x0, h), r)
            }
            drawCircle(Color.White, (7 + 4 * grow).dp.toPx(), Offset(px, size.height / 2))
        }
        if (dragging && duration > 0.0) {
            val bubblePos = scrubProvider() ?: last
            val frac = (bubblePos / duration).toFloat().coerceIn(0f, 1f)
            val chapter = player.chapterAt(bubblePos)?.first?.let(::chapterLabel)
            Column(
                Modifier
                    // Zero-size layout node: the bubble floats above the bar without being squeezed into its 40dp.
                    .layout { m, c ->
                        val p = m.measure(c.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity))
                        layout(0, 0) {
                            val x = (frac * barWidth - p.width / 2f).roundToInt().coerceIn(0, (barWidth - p.width).coerceAtLeast(0))
                            p.place(x, -p.height + 2.dp.roundToPx())
                        }
                    }
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xF2151520)).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(time(bubblePos), style = Type.Title.copy(fontSize = 16.sp), color = Color.White)
                if (chapter != null) Text(chapter, style = Type.Caption, color = Color(0xFFC9B8FF), maxLines = 1)
            }
        }
    }
}


/**
 * The account has no subscription: say so plainly and lead to the site's subscription page. Nothing to retry
 * here; once it's paid, the same episode simply plays.
 */
@Composable
private fun Paywall(modifier: Modifier, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    Column(modifier.padding(40.dp).widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Nf.Amber.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Lock, null, tint = Nf.Amber, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(16.dp))
        Text("Нужна подписка Anime 365", style = Type.Display, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Видео на Anime 365 доступно по подписке, а у этого аккаунта её нет. Оформите её на сайте и возвращайтесь: серия запустится как обычно.",
            style = Type.Body, color = Color(0xB3FFFFFF), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.clip(CircleShape).background(Nf.Amber).pressable {
                    scope.launch { openSubscription(ctx) }
                }.padding(horizontal = 22.dp, vertical = 12.dp),
            ) { Text("Оформить на сайте", style = Type.Label, color = Color.Black) }
            Box(Modifier.clip(CircleShape).background(Color(0x33FFFFFF)).pressable(onClick = onBack).padding(horizontal = 22.dp, vertical = 12.dp)) {
                Text("Назад", style = Type.Label, color = Color.White)
            }
        }
    }
}

/** The site's subscription page, on the mirror the app uses, in the browser. */
suspend fun openSubscription(ctx: android.content.Context) {
    val base = runCatching { ctx.container.a365.baseUrl() }.getOrDefault("https://anime365.ru")
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("$base/support/index"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
