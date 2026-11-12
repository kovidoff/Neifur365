package app.neifur.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.neifur.Container
import app.neifur.nfViewModel
import app.neifur.ui.Nf
import app.neifur.ui.PosterImage
import app.neifur.ui.Type
import app.neifur.ui.pressable
import app.neifur.ui.shimmer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

class WelcomeVm(private val c: Container) : ViewModel() {
    /** Posters for the moving wall on the first page; empty until Anime 365 answers (the wall shimmers meanwhile). */
    var posters by mutableStateOf<List<String>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            c.prefs.beginOnboarding()
            runCatching { c.a365.catalog(airing = true, limit = 40) }.onSuccess { list ->
                posters = list.mapNotNull { it.posterUrl ?: it.posterUrlSmall }.distinct().take(36)
            }
        }
    }

    fun finish() = viewModelScope.launch { c.prefs.setOnboarded() }
}

private const val PAGES = 3

/**
 * First run: what Neifur 365 is, how it works, and signing in to Anime 365 — the one thing a viewer must bring
 * to watch. The catalog works without an account, so the last step can be skipped.
 */
@Composable
fun WelcomeScreen() {
    val vm = nfViewModel { WelcomeVm(it) }
    val signIn = nfViewModel(key = "signin-welcome") { SignInVm(it) }
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    BackHandler(pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }

    // 0 on the first page, 1 once it has been swiped away: drives the poster wall's fade and drift.
    val away by remember { derivedStateOf { (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f) } }

    Box(Modifier.fillMaxSize().background(Nf.Ink)) {
        PosterWall(vm.posters, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - away * 0.82f })
        // Scrim: posters stay vivid at the top and sink into ink where the text sits.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Nf.Ink.copy(alpha = 0.55f), 0.22f to Nf.Ink.copy(alpha = 0.05f), 0.5f to Nf.Ink.copy(alpha = 0.55f),
                    0.68f to Nf.Ink.copy(alpha = 0.96f), 1f to Nf.Ink,
                )
            )
        )
        Glow(away)

        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - offset * 0.7f }) {
                    when (page) {
                        0 -> IntroPage()
                        1 -> HowPage(visible = pager.settledPage == 1 || pager.targetPage == 1)
                        else -> AccountPage(signIn)
                    }
                }
            }
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
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

// --- pages -----------------------------------------------------------------------------------------

@Composable
private fun IntroPage() {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.Bottom) {
        Rise(0) { Text("ДОБРО ПОЖАЛОВАТЬ В", style = Type.Overline, color = Nf.Violet) }
        Rise(1) {
            Text(
                buildAnnotatedString {
                    append("neifur")
                    withStyle(SpanStyle(color = Nf.Violet)) { append(" 365") }
                },
                style = Type.Hero.copy(fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-1.5).sp), color = Nf.Text,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Rise(2) {
            Text(
                "Аниме в любимой озвучке — с каталогом Anime 365 и плеером, который не мешает смотреть.",
                style = Type.Body.copy(fontSize = 16.sp, lineHeight = 24.sp), color = Nf.TextDim, modifier = Modifier.padding(top = 14.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HowPage(visible: Boolean) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.Bottom) {
        Text("Как это работает", style = Type.Hero, color = Nf.Text)
        Text(
            "Neifur 365 — приложение для просмотра каталога Anime 365",
            style = Type.Body, color = Nf.TextDim, modifier = Modifier.padding(top = 8.dp, bottom = 26.dp),
        )
        Step(0, visible, Icons.Rounded.Explore, Nf.Violet, "Каталог Anime 365", "Онгоинги, все серии и поиск. Описания, персонажи и связанные сезоны — с Shikimori")
        Step(1, visible, Icons.Rounded.Mic, Nf.Pink, "Озвучки и субтитры", "Выберите студию один раз — следующие серии включатся в ней же")
        Step(2, visible, Icons.Rounded.Sync, Nf.Mint, "Ваш аккаунт", "Списки и отметки о просмотре синхронизируются с Anime 365", last = true)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AccountPage(vm: SignInVm) {
    // Scrolls when the keyboard is up; otherwise sits at the bottom like the other pages.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text("Войдите в Anime 365", style = Type.Hero, color = Nf.Text, modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                "Видео доступно после входа в аккаунт Anime 365. Каталог работает и без него — войти можно позже.",
                style = Type.Body, color = Nf.TextDim, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 22.dp),
            )
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

// --- pieces ----------------------------------------------------------------------------------------

/** Text that rises into place on first show, one beat after the line above it. */
@Composable
private fun Rise(order: Int, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(180L + order * 110L); p.animateTo(1f, tween(620, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 46f }) { content() }
}

@Composable
private fun Step(i: Int, visible: Boolean, icon: ImageVector, tint: Color, title: String, text: String, last: Boolean = false) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(visible) { if (visible) { delay(120L + i * 130L); p.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 260f)) } }
    Row(Modifier.graphicsLayer { alpha = p.value.coerceIn(0f, 1f); translationX = (1f - p.value) * 60f }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
            }
            if (!last) Box(
                Modifier.padding(vertical = 6.dp).width(2.dp).height(30.dp).clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.45f), Color.Transparent)))
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.padding(top = 3.dp)) {
            Text(title, style = Type.Title, color = Nf.Text)
            Text(text, style = Type.Body, color = Nf.TextDim, modifier = Modifier.padding(top = 2.dp))
        }
    }
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

/** Soft violet light behind the text; it drifts to the right as the pages turn. */
@Composable
private fun Glow(away: Float) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            val c = Offset(lerp(size.width * 0.15f, size.width * 0.95f, away), size.height * 0.78f)
            drawCircle(
                Brush.radialGradient(listOf(Nf.Violet.copy(alpha = 0.20f), Color.Transparent), c, size.width * 0.9f),
                size.width * 0.9f, c,
            )
        }
    )
}

/**
 * Three tilted columns of posters scrolling forever, neighbours in opposite directions. Each column is laid out
 * twice so the loop is seamless. Until posters arrive the tiles shimmer.
 */
@Composable
private fun PosterWall(posters: List<String>, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "wall")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), label = "p")
    BoxWithConstraints(modifier.clip(RoundedCornerShape(0.dp))) {
        val cols = 3
        val gap = 12.dp
        val w: Dp = (maxWidth * 1.3f - gap * (cols - 1)) / cols
        val h = w / 0.7f
        val perCol = 8
        val colH = (h + gap) * perCol
        Row(
            Modifier.requiredWidth(maxWidth * 1.3f).fillMaxHeight().align(Alignment.Center)
                .graphicsLayer { rotationZ = -9f; scaleX = 1.12f; scaleY = 1.12f },
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            repeat(cols) { c ->
                val dir = if (c % 2 == 0) 1f else -1f
                // Columns start at different heights so the rows never line up.
                val phase = (p + c * 0.37f) % 1f
                Column(
                    Modifier.width(w).wrapContentHeight(Alignment.Top, unbounded = true)
                        .graphicsLayer {
                            val travel = colH.toPx()
                            translationY = if (dir > 0) -phase * travel else -(1f - phase) * travel
                        },
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    repeat(perCol * 2) { k ->
                        val url = posters.getOrNull((c * perCol + k % perCol) % posters.size.coerceAtLeast(1))
                        val shape = RoundedCornerShape(16.dp)
                        AnimatedContent(url, label = "wp", transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(300)) }) { u ->
                            if (u == null) Box(Modifier.width(w).height(h).clip(shape).shimmer())
                            else PosterImage(u, Modifier.width(w).height(h).clip(shape))
                        }
                    }
                }
            }
        }
    }
}
