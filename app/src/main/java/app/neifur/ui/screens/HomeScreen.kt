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
import androidx.compose.foundation.border
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.neifur.AnimeStub
import app.neifur.Container
import app.neifur.OpenAnime
import app.neifur.core.userMessage
import app.neifur.data.WatchEntry
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.ListedTitle
import app.neifur.data.a365.Series
import app.neifur.nfViewModel
import app.neifur.stub
import app.neifur.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
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
    /** The viewer's Anime 365 lists: what's new in "Смотрю", and which posters are already in a list. */
    val lists = c.lists.state
    val history = c.prefs.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        load()
        // The catalog failed for lack of a network: try again by itself once it's back.
        viewModelScope.launch { c.online.collect { if (it && feed.value is Load.Error) load() } }
    }

    /** New episodes come out while the app sits in the background: reread the lists when Home shows up again. */
    fun refreshLists() = c.lists.refreshIfStale()

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
    val history by vm.history.collectAsStateWithLifecycle()
    val cont = remember(history) { history.distinctBy { it.animeId }.take(12) }
    val lists by vm.lists.collectAsStateWithLifecycle()
    // Only what the viewer is watching: a planned title's new episode isn't news to them.
    val fresh = remember(lists, history) { lists.watching(history).filter { it.behind > 0 && it.series != null } }
    val marks = remember(lists) { lists.titles.mapValues { it.value.status } }
    LifecycleResumeEffect(Unit) { vm.refreshLists(); onPauseOrDispose {} }
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        when (val f = feed) {
            // Nothing to show at all: the full message. With something watched on this phone, it stays reachable.
            is Load.Error -> if (cont.isEmpty()) EmptyState(
                "Не получилось загрузить каталог", f.message, Icons.Rounded.WifiOff,
                Modifier.align(Alignment.Center), action = "Повторить", onAction = vm::load,
            ) else LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), modifier = Modifier.statusBarsPadding().padding(top = 60.dp)) {
                item(key = "offline") { OfflineCard(f.message, vm::load) }
                item(key = "continue") { ContinueRow(cont, lists.titles, open) }
            }
            else -> {
                val data = (f as? Load.Ready)?.value
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 120.dp)) {
                    item(key = "hero", contentType = "hero") {
                        if (data == null) HeroSkeleton() else Hero(data.ongoing.take(7), open)
                    }
                    if (cont.isNotEmpty()) item(key = "continue", contentType = "continue") {
                        Appear("continue", 0, vm.appeared) { ContinueRow(cont, lists.titles, open) }
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
                            Appear(key, i + 2, vm.appeared, ready = list != null) { AnimeRow(title, key, list, marks, open) }
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

/** The catalog didn't load, but what's watched here is still below: a compact note instead of a full-screen error. */
@Composable
private fun OfflineCard(message: String, retry: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Nf.Surface).border(1.dp, Nf.Line, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Nf.Ink), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.WifiOff, null, tint = Nf.TextDim, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Каталог не загрузился", style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text)
            Text(message, style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "Повторить", style = Type.Label, color = Nf.Text,
            modifier = Modifier.clip(CircleShape).background(Nf.SurfaceHi).pressable(0.95f, onClick = retry).padding(horizontal = 14.dp, vertical = 9.dp),
        )
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
private fun AnimeRow(title: String, key: String, list: List<Series>?, marks: Map<Long, ListStatus?>, open: OpenAnime) {
    if (list != null && list.isEmpty()) return
    Column(Modifier.padding(top = 26.dp)) {
        SectionTitle(title)
        Spacer(Modifier.height(14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (list == null) items(6) { PosterCardSkeleton() }
            else items(list, key = { it.id }) { a -> PosterCard(a.stub(), key, { open(a.stub(), key) }, listStatus = marks[a.id]) }
        }
    }
}

@Composable
private fun ContinueRow(entries: List<WatchEntry>, listed: Map<Long, ListedTitle>, open: OpenAnime) {
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
                        val (line, progress) = continueLine(e, listed[e.animeId.toLongOrNull()])
                        Text(line, style = Type.Caption, color = Nf.TextMute)
                        Spacer(Modifier.height(9.dp))
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Nf.Line)) {
                            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(CircleShape).background(Nf.Violet))
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Новые серии": titles in "Смотрю" with episodes out that the viewer hasn't watched yet. The badge counts them,
 * the caption names the one to watch next.
 */
@Composable
private fun FreshRow(items: List<ListedTitle>, open: OpenAnime) {
    Column(Modifier.padding(top = 22.dp)) {
        SectionTitle("Новые серии")
        Spacer(Modifier.height(14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.entry.seriesId }) { t ->
                val series = t.series ?: return@items
                val stub = series.stub()
                Column(Modifier.width(128.dp).pressable { open(stub, "fresh") }) {
                    // The badge lives inside the shared poster, so it travels with it instead of popping in after.
                    Box(Modifier.sharedPoster("poster/fresh/${series.id}").fillMaxWidth().aspectRatio(0.7f).clip(PosterShape)) {
                        PosterImage(series.posterUrl ?: series.posterUrlSmall, Modifier.fillMaxSize())
                        Text(
                            if (t.behind == 1) "Новая" else "+${t.behind}", style = Type.Caption, color = Nf.Ink,
                            modifier = Modifier.align(Alignment.BottomStart).padding(7.dp).clip(RoundedCornerShape(8.dp))
                                .background(Nf.Mint).padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.height(9.dp))
                    Text(series.title, style = Type.Label, color = Nf.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Дальше ${t.entry.watched + 1} серия", style = Type.Caption, color = Nf.Mint, maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * What a "Продолжить просмотр" card says, and its bar. The phone remembers where playback stopped; the list on the
 * site may be further along (episodes marked by hand, or watched elsewhere), and then it wins.
 */
fun continueLine(e: WatchEntry, listed: ListedTitle?): Pair<String, Float> {
    val w = listed?.entry?.watched ?: 0
    if (w >= e.episode && w > 0) {
        val total = listed?.total
        return if (total != null && w >= total) "Все серии просмотрены" to 1f else "Дальше серия ${w + 1}" to 0f
    }
    return if (e.finished) "Серия ${e.episode} просмотрена" to 1f else "Серия ${e.episode} · ${remaining(e)}" to e.progress
}

fun remaining(e: WatchEntry): String {
    val left = ((e.durationSec - e.positionSec) / 60).toInt().coerceAtLeast(0)
    return if (left <= 0) "почти досмотрено" else "осталось $left мин"
}
