package app.neifur.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.neifur.AnimeStub
import app.neifur.Container
import app.neifur.OpenAnime
import app.neifur.core.userMessage
import app.neifur.data.WatchEntry
import app.neifur.data.a365.AccountState
import app.neifur.data.a365.NewEpisode
import app.neifur.data.a365.Series
import app.neifur.nfViewModel
import app.neifur.stub
import app.neifur.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Error(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

/** The Home rows, all from the Anime 365 catalog. */
data class HomeFeed(val ongoing: List<Series>, val popular: List<Series>, val thisYear: List<Series>, val movies: List<Series>)

class HomeVm(private val c: Container) : ViewModel() {
    val feed = MutableStateFlow<Load<HomeFeed>>(Load.Loading)
    /** Rows that already played their entrance: scrolling back to them must not replay it. */
    val appeared = HashSet<String>()
    /** New episodes of what the viewer follows on the site; empty when signed out. */
    val fresh = MutableStateFlow<List<NewEpisode>>(emptyList())
    val continueWatching = c.prefs.history
        .map { h -> h.distinctBy { it.animeId }.take(12) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        load()
        viewModelScope.launch {
            c.account.state.collect { s ->
                fresh.value = if (s is AccountState.SignedIn) runCatching { c.a365.newEpisodes() }.getOrDefault(emptyList()) else emptyList()
            }
        }
    }

    fun load() {
        feed.value = Load.Loading
        viewModelScope.launch {
            feed.value = runCatching {
                coroutineScope {
                    val year = java.time.LocalDate.now().year
                    val ongoing = async { c.a365.catalog(airing = true, limit = 30) }
                    val popular = async { c.a365.catalog(limit = 24) }
                    val thisYear = async { c.a365.catalog(year = year, limit = 24) }
                    val movies = async { c.a365.catalog(type = "movie", limit = 24) }
                    HomeFeed(ongoing.await(), popular.await(), thisYear.await().filterNot { it.airing }, movies.await())
                }
            }.fold({ Load.Ready(it) }, { Load.Error(it.userMessage()) })
        }
    }
}

@Composable
fun HomeScreen(open: OpenAnime, onSearch: () -> Unit) {
    val vm = nfViewModel { HomeVm(it) }
    val feed by vm.feed.collectAsStateWithLifecycle()
    val cont by vm.continueWatching.collectAsStateWithLifecycle()
    val fresh by vm.fresh.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        when (val f = feed) {
            is Load.Error -> EmptyState(
                "Не получилось загрузить каталог", f.message, Icons.Rounded.WifiOff,
                Modifier.align(Alignment.Center), action = "Повторить", onAction = vm::load,
            )
            else -> {
                val data = (f as? Load.Ready)?.value
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 120.dp)) {
                    item(key = "hero", contentType = "hero") {
                        if (data == null) HeroSkeleton() else Hero(data.ongoing.take(7), open)
                    }
                    if (cont.isNotEmpty()) item(key = "continue", contentType = "continue") {
                        Appear("continue", 0, vm.appeared) { ContinueRow(cont, open) }
                    }
                    if (fresh.isNotEmpty()) item(key = "fresh", contentType = "fresh") {
                        Appear("fresh", 1, vm.appeared) { FreshRow(fresh, open) }
                    }
                    val rows = listOf(
                        "ongoing" to "Сейчас выходит", "year" to "Новинки года", "popular" to "Самое популярное", "movies" to "Полнометражные",
                    )
                    rows.forEachIndexed { i, (key, title) ->
                        item(key = key, contentType = "row") {
                            val list = when (key) {
                                "ongoing" -> data?.ongoing?.drop(7); "year" -> data?.thisYear; "popular" -> data?.popular
                                else -> data?.movies
                            }
                            Appear(key, i + 2, vm.appeared, ready = list != null) { AnimeRow(title, key, list, open) }
                        }
                    }
                }
            }
        }

        // Top bar: fades to a solid backdrop once the hero scrolls away.
        val barAlpha by remember {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) 1f
                else ((listState.firstVisibleItemScrollOffset - 250f) / 500f).coerceIn(0f, 1f)
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                // Read while drawing: the bar's fade follows the scroll without recomposing on every frame.
                .drawBehind { drawRect(Nf.Ink.copy(alpha = barAlpha * 0.96f)) }
                .statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Wordmark(Modifier.align(Alignment.CenterStart))
            Box(
                Modifier.align(Alignment.CenterEnd).size(40.dp).clip(CircleShape).background(Color(0x33000000))
                    .pressable(0.9f, onClick = onSearch),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Search, "Поиск", tint = Nf.Text, modifier = Modifier.size(22.dp)) }
        }
    }
}

/**
 * One-shot entrance: sections rise into place with a small stagger, once per screen. A row scrolled away and
 * back (the list disposes it) shows up already in place, instead of fading in again under the finger.
 */
@Composable
private fun Appear(key: String, index: Int, played: MutableSet<String>, ready: Boolean = true, content: @Composable () -> Unit) {
    val a = remember { Animatable(if (key in played) 1f else 0f) }
    LaunchedEffect(ready) {
        if (!ready || key in played) return@LaunchedEffect
        played += key
        delay(60L * index.coerceAtMost(5))
        a.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
    }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 36.dp.toPx() }) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hero(items: List<Series>, open: OpenAnime) {
    if (items.isEmpty()) return
    val height = (LocalConfiguration.current.screenHeightDp * 0.66f).dp
    val pager = rememberPagerState { items.size }
    LaunchedEffect(pager.settledPage) {
        delay(6500)
        if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.settledPage + 1) % items.size, animationSpec = tween(900, easing = FastOutSlowInEasing))
    }
    Box(Modifier.fillMaxWidth().height(height)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            val a = items[page]
            val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
            Box(Modifier.fillMaxSize().clipToBounds().pressable(0.985f) { open(a.stub(), "hero") }) {
                PosterImage(
                    a.posterUrl, cacheKey = a.posterUrl,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        // Parallax: the artwork lags behind the page, the text doesn't.
                        translationX = offset * size.width * 0.32f
                        val s = 1.08f + offset.absoluteValue * 0.06f
                        scaleX = s; scaleY = s
                    },
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Transparent, 0.38f to Color.Transparent,
                            0.72f to Nf.Ink.copy(alpha = 0.78f), 1f to Nf.Ink,
                        )
                    )
                )
                Column(
                    Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp).padding(bottom = 44.dp)
                        .graphicsLayer { alpha = 1f - (offset.absoluteValue * 1.6f).coerceIn(0f, 1f); translationX = -offset * 60.dp.toPx() },
                ) {
                    Text("СЕЙЧАС ВЫХОДИТ", style = Type.Overline, color = Nf.Violet)
                    Spacer(Modifier.height(8.dp))
                    Text(a.title, style = Type.Hero, color = Nf.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    val genres = a.genres.orEmpty().take(3).joinToString(" · ") { it.title }
                    val eps = if (a.numberOfEpisodes > 0) "${a.numberOfEpisodes} эп." else null
                    Text(listOfNotNull(genres.takeIf { it.isNotBlank() }, eps).joinToString("  •  "), style = Type.Label, color = Nf.TextDim, maxLines = 1)
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.clip(CircleShape).background(Nf.Text).pressable { open(a.stub(), "hero") }
                                .padding(start = 14.dp, end = 20.dp, top = 11.dp, bottom = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = Nf.Ink, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Смотреть", style = Type.Title.copy(fontSize = 15.sp), color = Nf.Ink)
                        }
                        formatScore(a.score)?.let { s ->
                            Spacer(Modifier.width(14.dp))
                            Icon(Icons.Rounded.Star, null, tint = Nf.Amber, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(s, style = Type.Title, color = Nf.Text)
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(items.size) { i ->
                val w by animateDpAsState(if (i == pager.currentPage) 22.dp else 6.dp, label = "dot")
                Box(Modifier.height(4.dp).width(w).clip(CircleShape).background(if (i == pager.currentPage) Nf.Text else Color(0x4DFFFFFF)))
            }
        }
    }
}

@Composable
private fun HeroSkeleton() {
    val height = (LocalConfiguration.current.screenHeightDp * 0.66f).dp
    Box(Modifier.fillMaxWidth().height(height)) {
        Box(Modifier.fillMaxSize().shimmer())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Nf.Ink)))
    }
}

@Composable
private fun AnimeRow(title: String, key: String, list: List<Series>?, open: OpenAnime) {
    if (list != null && list.isEmpty()) return
    Column(Modifier.padding(top = 26.dp)) {
        SectionTitle(title)
        Spacer(Modifier.height(14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (list == null) items(6) { PosterCardSkeleton() }
            else items(list, key = { it.id }) { a -> PosterCard(a.stub(), key, { open(a.stub(), key) }) }
        }
    }
}

@Composable
private fun ContinueRow(entries: List<WatchEntry>, open: OpenAnime) {
    Column(Modifier.padding(top = 22.dp)) {
        SectionTitle("Продолжить просмотр")
        Spacer(Modifier.height(14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { it.animeId }) { e ->
                val stub = AnimeStub(e.animeId, e.title, e.poster)
                Row(
                    Modifier.width(272.dp).clip(RoundedCornerShape(20.dp)).background(Nf.Surface)
                        .pressable { open(stub, "continue") }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PosterImage(e.poster, Modifier.sharedPoster("poster/continue/${e.animeId}", RoundedCornerShape(12.dp)).width(58.dp).aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = Type.Label, color = Nf.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(if (e.finished) "Серия ${e.episode} просмотрена" else "Серия ${e.episode} · ${remaining(e)}", style = Type.Caption, color = Nf.TextMute)
                        Spacer(Modifier.height(9.dp))
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Nf.Line)) {
                            Box(Modifier.fillMaxWidth(e.progress).fillMaxHeight().clip(CircleShape).background(Nf.Violet))
                        }
                    }
                }
            }
        }
    }
}

/** "Новые серии": episodes that came out for titles in the viewer's Anime 365 list. */
@Composable
private fun FreshRow(items: List<NewEpisode>, open: OpenAnime) {
    Column(Modifier.padding(top = 22.dp)) {
        SectionTitle("Новые серии в ваших списках")
        Spacer(Modifier.height(14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { "${it.seriesId}-${it.episodeId}" }) { e ->
                val stub = AnimeStub(e.seriesId.toString(), e.title, e.poster)
                Column(Modifier.width(128.dp).pressable { open(stub, "fresh") }) {
                    Box {
                        PosterImage(e.poster, Modifier.sharedPoster("poster/fresh/${e.seriesId}").fillMaxWidth().aspectRatio(0.7f).clip(PosterShape))
                        Text(
                            e.episodeLabel.ifBlank { "Новая серия" }, style = Type.Caption, color = Nf.Ink,
                            modifier = Modifier.align(Alignment.BottomStart).padding(7.dp).clip(RoundedCornerShape(8.dp))
                                .background(Nf.Mint).padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.height(9.dp))
                    Text(e.title, style = Type.Label, color = Nf.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    e.note?.let { Text(it, style = Type.Caption, color = Nf.TextMute, maxLines = 1, modifier = Modifier.padding(top = 2.dp)) }
                }
            }
        }
    }
}

fun remaining(e: WatchEntry): String {
    val left = ((e.durationSec - e.positionSec) / 60).toInt().coerceAtLeast(0)
    return if (left <= 0) "почти досмотрено" else "осталось $left мин"
}
