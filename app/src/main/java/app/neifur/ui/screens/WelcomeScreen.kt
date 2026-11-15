package app.neifur.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.neifur.Container
import app.neifur.data.a365.Series
import app.neifur.nfViewModel
import app.neifur.ui.Nf
import app.neifur.ui.PosterImage
import app.neifur.ui.Type
import app.neifur.ui.formatScore
import app.neifur.ui.pressable
import app.neifur.ui.shimmer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.sin

class WelcomeVm(private val c: Container) : ViewModel() {
    /** What's airing now on Anime 365: the fan of cards on the first page. */
    var airing by mutableStateOf<List<Series>>(emptyList())
        private set
    val kind = c.prefs.preferredKind

    init {
        viewModelScope.launch {
            c.prefs.beginOnboarding()
            runCatching { c.a365.catalog(airing = true, limit = 12) }.onSuccess { list -> airing = list.filter { it.posterUrl != null } }
        }
    }

    fun setKind(k: String) = viewModelScope.launch { c.prefs.setPreferredKind(k) }
    fun finish() = viewModelScope.launch { c.prefs.setOnboarded() }
}

private const val PAGES = 3

/**
 * First run of Neifur 365: what's airing on Anime 365, how the viewer likes to watch (voice-over, subtitles or
 * the original — saved as the default), and signing in, which watching needs. The catalog works without an
 * account, so the last step can be put off.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WelcomeScreen() {
    val vm = nfViewModel { WelcomeVm(it) }
    val signIn = nfViewModel(key = "signin-welcome") { SignInVm(it) }
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    BackHandler(pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
    val progress by remember { derivedStateOf { (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, PAGES - 1f) } }

    Box(Modifier.fillMaxSize().background(Nf.Ink)) {
        Aurora(progress)
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Wordmark(Modifier.graphicsLayer { alpha = progress.coerceIn(0f, 1f) })
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(pager.currentPage < PAGES - 1, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        "Пропустить", style = Type.Label, color = Nf.TextDim,
                        modifier = Modifier.clip(CircleShape).clickable { scope.launch { pager.animateScrollToPage(PAGES - 1) } }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { page ->
                val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - offset * 0.8f }) {
                    when (page) {
                        0 -> IntroPage(vm.airing)
                        1 -> KindPage(vm, visible = pager.settledPage == 1 || pager.targetPage == 1)
                        else -> AccountPage(signIn, visible = pager.settledPage == 2 || pager.targetPage == 2)
                    }
                }
            }
            // While typing in the sign-in form the keyboard takes this space: the dots and "Позже" step aside
            // instead of riding up on top of it and covering the fields.
            val typing = WindowInsets.isImeVisible
            AnimatedVisibility(!typing, enter = expandVertically(tween(220)) + fadeIn(tween(220)), exit = shrinkVertically(tween(180)) + fadeOut(tween(120))) {
            Column(Modifier.padding(horizontal = 24.dp).padding(top = 10.dp, bottom = 14.dp)) {
                Dots(pager, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 18.dp))
                val last = pager.currentPage == PAGES - 1
                val signedIn = signIn.profile != null
                // On the sign-in page the form has its own button; this one is the way out (or in, once signed in).
                AnimatedContent(Pair(last, signedIn), label = "wb", transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }) { (l, si) ->
                    if (l && !si) Box(
                        Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).pressable(0.97f) { focus.clearFocus(); vm.finish() },
                        contentAlignment = Alignment.Center,
                    ) { Text("Позже — сначала посмотрю каталог", style = Type.Title, color = Nf.TextDim) }
                    else PrimaryButton(
                        text = if (l) "Смотреть аниме" else if (pager.currentPage == 0) "Начать" else "Дальше",
                        color = if (l) Nf.Mint else Nf.Violet,
                    ) {
                        if (l) vm.finish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }
                }
            }
            }
        }
    }
}

// --- pages -----------------------------------------------------------------------------------------

/** What's airing now, dealt from a deck, and what Neifur 365 is. */
@Composable
private fun IntroPage(airing: List<Series>) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { PosterDeck(airing) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Rise(0) { Wordmark(big = true) }
            Rise(1) {
                Text(
                    "Аниме в любимой озвучке", style = Type.Display.copy(fontSize = 22.sp), color = Nf.Text,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp),
                )
            }
            Rise(2) {
                Text(
                    "Каталог Anime 365 с плеером Neifur: он помнит вашу студию, серию и место, где вы остановились.",
                    style = Type.Body.copy(fontSize = 15.5.sp, lineHeight = 23.sp), color = Nf.TextDim,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * A deck of what's airing now. Every few seconds the top card is dealt away to the left (it turns and fades as it
 * goes) while the cards behind rise one place and a new one fades in at the back. The drawing order never changes
 * mid-move, so no card ever jumps over another.
 */
@Composable
private fun PosterDeck(items: List<Series>) {
    var head by remember { mutableIntStateOf(0) }
    val t = remember { Animatable(0f) }
    LaunchedEffect(items.size) {
        if (items.size < 2) return@LaunchedEffect
        while (true) {
            delay(2800)
            t.animateTo(1f, tween(950, easing = CubicBezierEasing(0.33f, 0f, 0.2f, 1f)))
            head = (head + 1) % items.size
            t.snapTo(0f)
        }
    }
    val float = rememberInfiniteTransition(label = "float")
    val wave = float.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "w")
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val cardW = (maxHeight * 0.66f * 0.7f).coerceAtMost(maxWidth * 0.56f)
        val cardH = cardW / 0.7f
        // One soft light behind the deck, painted rather than cast: it stays put while the pages slide.
        Box(
            Modifier.size(cardW * 2.1f, cardH * 1.5f).drawBehind {
                drawOval(Brush.radialGradient(listOf(Nf.Violet.copy(alpha = 0.34f), Nf.Pink.copy(alpha = 0.10f), Color.Transparent)))
            }
        )
        if (items.isEmpty()) {
            Box(Modifier.size(cardW, cardH).clip(RoundedCornerShape(26.dp)).shimmer())
            return@BoxWithConstraints
        }
        val n = items.size
        // Back to front: slots 3..0. The card on slot 3 is invisible and fades in as it moves up to slot 2.
        for (k in minOf(3, n - 1) downTo 0) {
            val a = items[(head + k) % n]
            key(a.id) {
                DeckCard(a, k, t, wave, Modifier.size(cardW, cardH))
            }
        }
    }
}

@Composable
private fun DeckCard(a: Series, k: Int, t: Animatable<Float, *>, wave: State<Float>, modifier: Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier
            .graphicsLayer {
                val pos = k - t.value          // 0 = top card; below 0 it is leaving
                val bob = kotlin.math.sin(wave.value) * 5.dp.toPx()
                if (pos < 0f) {
                    translationX = pos * size.width * 1.25f
                    translationY = -pos * 26.dp.toPx() + bob
                    rotationZ = pos * 18f
                    alpha = (1f + pos * 1.3f).coerceIn(0f, 1f)
                } else {
                    val sc = 1f - 0.075f * pos
                    scaleX = sc; scaleY = sc
                    translationX = pos * 18.dp.toPx()
                    translationY = -pos * 24.dp.toPx() + bob * (1f - pos * 0.3f)
                    rotationZ = pos * 4f
                    alpha = (3f - pos).coerceIn(0f, 1f)
                }
                shape.let { this.shape = it; clip = true }
            }
            .background(Nf.Surface),
    ) {
        PosterImage(a.posterUrl, Modifier.fillMaxSize())
        // Cards further back sit in shade; the top one is clear.
        Box(Modifier.fillMaxSize().drawBehind {
            val pos = (k - t.value).coerceAtLeast(0f)
            drawRect(Nf.Ink.copy(alpha = (pos * 0.28f).coerceAtMost(0.6f)))
        })
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xD908080C))))
        Column(
            Modifier.align(Alignment.BottomStart).padding(14.dp).graphicsLayer {
                alpha = (1f - kotlin.math.abs(k - t.value) * 1.6f).coerceIn(0f, 1f)
            }
        ) {
            Text("СЕЙЧАС ВЫХОДИТ", style = Type.Overline.copy(fontSize = 9.5.sp), color = Nf.Mint)
            Text(a.title, style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text, maxLines = 2, modifier = Modifier.padding(top = 3.dp))
        }
        formatScore(a.score)?.let {
            Text(
                "★ $it", style = Type.Caption, color = Nf.Text,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(9.dp))
                    .background(Color(0xB8090910)).padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        Box(Modifier.fillMaxSize().border(1.dp, Color(0x1FFFFFFF), shape))
    }
}

private data class KindOption(val key: String, val icon: ImageVector, val tint: Color, val title: String, val text: String)

private val kindOptions = listOf(
    KindOption("VoiceRu", Icons.Rounded.Mic, Nf.Pink, "В озвучке", "Русские голоса от десятков студий"),
    KindOption("SubRu", Icons.Rounded.ClosedCaption, Nf.Violet, "С субтитрами", "Японская речь и русский перевод внизу"),
    KindOption("Raw", Icons.Rounded.Translate, Nf.Mint, "В оригинале", "Только японский звук, без перевода"),
)

/** "How do you watch?": the answer becomes the default translation for every new title. */
@Composable
private fun KindPage(vm: WelcomeVm, visible: Boolean) {
    val kind by vm.kind.collectAsStateWithLifecycle("VoiceRu")
    Column(Modifier.fillMaxSize()) {
        StudioMarquee(Modifier.weight(1f).fillMaxWidth())
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Как вы смотрите?", style = Type.Hero, color = Nf.Text)
            Text(
                "Neifur 365 будет сразу включать то, что вам нужно. Студию можно выбрать на странице тайтла — она запомнится",
                style = Type.Body, color = Nf.TextDim, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
            )
            kindOptions.forEachIndexed { i, o ->
                Appear(i, visible) { KindCard(o, o.key == kind) { vm.setKind(o.key) } }
                if (i != kindOptions.lastIndex) Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun KindCard(o: KindOption, selected: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) o.tint.copy(alpha = 0.7f) else Nf.Line, label = "kb")
    val bg by animateColorAsState(if (selected) o.tint.copy(alpha = 0.12f) else Nf.Surface.copy(alpha = 0.85f), label = "kbg")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg).border(1.5.dp, border, RoundedCornerShape(20.dp))
            .pressable(0.98f, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(o.tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(o.icon, null, tint = o.tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(o.title, style = Type.Title, color = Nf.Text)
            Text(o.text, style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 2.dp))
        }
        val check by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "ck")
        Box(
            Modifier.size(26.dp).clip(CircleShape).border(2.dp, if (selected) o.tint else Nf.Line, CircleShape)
                .background(o.tint.copy(alpha = check)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Check, null, tint = Nf.Ink, modifier = Modifier.size(16.dp).graphicsLayer { scaleX = check; scaleY = check }) }
    }
}

/**
 * Studio names drifting by: Anime 365 is about the choice of voices and translations. Rows run level at their own
 * speeds, moved by whole pixels so the letters stay crisp, and loop seamlessly. The block melts into the background
 * on every edge through an alpha mask, whatever colour is behind it.
 */
@Composable
private fun StudioMarquee(modifier: Modifier) {
    val rows = listOf(
        Triple(listOf("AniLibria", "AniDUB", "Dream Cast", "StudioBand", "AniStar", "SHIZA Project", "Animaunt"), 26f, false),
        Triple(listOf("Субтитры", "Kazoku Project", "AniRise", "JAM CLUB", "AnimeVost", "Crunchyroll", "Оригинал"), 19f, true),
        Triple(listOf("Озвучка", "Freedub", "AniMedia", "OnWave", "SovetRomantica", "AniJoy", "1080p"), 23f, false),
        Triple(listOf("AniMaunt", "Anilibria.TV", "Zendos", "BD", "Субтитры RU", "TV-версия", "Persona99"), 17f, true),
    )
    Column(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.22f to Color.Black, 0.72f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.12f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        rows.forEachIndexed { r, (names, speed, reverse) -> MarqueeRow(names, speed, reverse, r) }
    }
}

@Composable
private fun MarqueeRow(names: List<String>, dpPerSecond: Float, reverse: Boolean, row: Int) {
    var width by remember { mutableIntStateOf(0) }
    val offset = remember { mutableFloatStateOf(0f) }
    val gap = with(LocalDensity.current) { 10.dp.toPx() }
    val speed = with(LocalDensity.current) { dpPerSecond.dp.toPx() }
    // Two copies of the names side by side: one period is half the row plus one gap.
    val period = (width + gap) / 2f
    LaunchedEffect(period) {
        if (period <= 0f) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            offset.floatValue = (offset.floatValue + (now - last) / 1e9f * speed) % period
            last = now
        }
    }
    Row(
        Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start, unbounded = true)
            .onSizeChanged { width = it.width }
            .graphicsLayer {
                val x = if (reverse) offset.floatValue - period else -offset.floatValue
                translationX = kotlin.math.round(x)
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        (names + names).forEachIndexed { i, n ->
            val accent = listOf(Nf.Violet, Nf.Pink, Nf.Mint)[(i + row) % 3]
            Text(
                n, style = Type.Title.copy(fontSize = 16.sp), color = Nf.Text.copy(alpha = 0.88f), maxLines = 1, softWrap = false,
                modifier = Modifier.clip(CircleShape).background(accent.copy(alpha = 0.13f))
                    .border(1.dp, accent.copy(alpha = 0.28f), CircleShape).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AccountPage(vm: SignInVm, visible: Boolean) {
    // Scrolls when the keyboard is up; otherwise sits at the bottom like the other pages.
    val scroll = rememberScrollState()
    val typing = WindowInsets.isImeVisible
    // Keyboard up: bring the fields and the sign-in button into view above it.
    LaunchedEffect(typing) { if (typing) { kotlinx.coroutines.delay(120); scroll.animateScrollTo(scroll.maxValue) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Box(
                Modifier.padding(start = 4.dp, bottom = 16.dp).size(54.dp).clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(Nf.Violet, Nf.Pink))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Person, null, tint = Nf.Ink, modifier = Modifier.size(28.dp)) }
            Text("Аккаунт Anime 365", style = Type.Hero, color = Nf.Text, modifier = Modifier.padding(horizontal = 4.dp))
            Column(Modifier.padding(top = 14.dp, bottom = 20.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Appear(0, visible) { Perk(Icons.Rounded.HighQuality, Nf.Violet, "Видео до 1080p — после входа") }
                Appear(1, visible) { Perk(Icons.Rounded.Sync, Nf.Pink, "Списки «Смотрю» и «Запланировано» общие с сайтом") }
                Appear(2, visible) { Perk(Icons.Rounded.TaskAlt, Nf.Mint, "Досмотренные серии отмечаются сами") }
            }
            SignInForm(vm)
            Row(Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, tint = Nf.TextMute, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("Пароль уходит только на сайт Anime 365, приложение его не хранит", style = Type.Caption, color = Nf.TextMute)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Perk(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.Label, color = Nf.TextDim)
    }
}

// --- pieces ----------------------------------------------------------------------------------------

/** "neifur" with 365 in a violet-to-pink pill: the same family as Neifur, with its own badge. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, big: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "neifur", color = Nf.Text,
            style = Type.Hero.copy(fontSize = if (big) 44.sp else 20.sp, lineHeight = if (big) 50.sp else 24.sp, letterSpacing = (if (big) -1.2 else -0.3).sp),
        )
        Text(
            "365", style = Type.Hero.copy(fontSize = if (big) 24.sp else 12.sp, lineHeight = if (big) 28.sp else 15.sp), color = Nf.Ink,
            modifier = Modifier.padding(start = if (big) 10.dp else 6.dp).clip(RoundedCornerShape(if (big) 12.dp else 7.dp))
                .background(Brush.linearGradient(listOf(Nf.Violet, Nf.Pink)))
                .padding(horizontal = if (big) 10.dp else 6.dp, vertical = if (big) 3.dp else 1.dp),
        )
    }
}

/** Text that rises into place on first show, one beat after the line above it. */
@Composable
private fun Rise(order: Int, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(220L + order * 110L); p.animateTo(1f, tween(620, easing = FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 46f }) { content() }
}

/** Slides in from the right once its page is reached, in turn with its neighbours. */
@Composable
private fun Appear(i: Int, visible: Boolean, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(visible) { if (visible) { delay(100L + i * 110L); p.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 260f)) } }
    Box(Modifier.graphicsLayer { alpha = p.value.coerceIn(0f, 1f); translationX = (1f - p.value) * 80f }) { content() }
}

@Composable
private fun Dots(pager: PagerState, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(PAGES) { i ->
            val on = pager.currentPage == i
            val w by animateDpAsState(if (on) 22.dp else 6.dp, spring(dampingRatio = 0.7f, stiffness = 500f), label = "dot")
            Box(Modifier.height(6.dp).width(w).clip(CircleShape).background(if (on) Nf.Violet else Nf.Line))
        }
    }
}

/** Two slow coloured lights behind everything; they trade places as the pages turn. */
@Composable
private fun Aurora(progress: Float) {
    val t = rememberInfiniteTransition(label = "aurora")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "d")
    Box(
        Modifier.fillMaxSize().drawBehind {
            val k = progress / (PAGES - 1)
            val a = Offset(size.width * (0.15f + 0.7f * k + 0.08f * drift), size.height * (0.22f + 0.1f * drift))
            val b = Offset(size.width * (0.9f - 0.75f * k), size.height * (0.62f - 0.08f * drift))
            drawCircle(Brush.radialGradient(listOf(Nf.Violet.copy(alpha = 0.30f), Color.Transparent), a, size.width * 0.85f), size.width * 0.85f, a)
            drawCircle(Brush.radialGradient(listOf(Nf.Pink.copy(alpha = 0.16f), Color.Transparent), b, size.width * 0.75f), size.width * 0.75f, b)
        }
    )
}
