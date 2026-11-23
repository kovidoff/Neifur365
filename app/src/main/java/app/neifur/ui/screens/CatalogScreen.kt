@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.neifur.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.neifur.Container
import app.neifur.OpenAnime
import app.neifur.core.userMessage
import app.neifur.data.a365.A365Genres
import app.neifur.data.a365.ListProgress
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.Series
import app.neifur.data.shikimori.kindLabel
import app.neifur.nfViewModel
import app.neifur.stub
import app.neifur.ui.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Filters the Anime 365 catalog understands. It has no sorting: results come ranked by the site. */
data class CatalogQuery(
    val search: String = "",
    val kind: String? = null,
    val airing: Boolean = false,
    val genreId: Int? = null,
    val year: Int? = null,
)

private const val PAGE = 30

class CatalogVm(private val c: Container) : ViewModel() {
    val lists = c.lists.state
    val recentSearches = c.prefs.recentSearches
    var query by mutableStateOf(CatalogQuery())
        private set
    var queryVersion by mutableIntStateOf(0)
        private set
    var items by mutableStateOf<List<Series>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var moreError by mutableStateOf<String?>(null)
        private set

    // "Случайное аниме" roulette state (respects active filters)
    var randomOpen by mutableStateOf(false)
        private set
    var randomPick by mutableStateOf<Series?>(null)
        private set
    var randomLoading by mutableStateOf(false)
        private set
    var randomError by mutableStateOf<String?>(null)
        private set
    var randomHideListed by mutableStateOf(true)
        private set
    var randomSpinCount by mutableIntStateOf(0)
        private set

    private var end = false
    private var job: Job? = null
    private var randomJob: Job? = null
    private var randomPoolQuery: CatalogQuery? = null
    private val randomPool = ArrayList<Series>()
    private val randomSeen = HashSet<Long>()
    private val triedOffsets = HashSet<Int>()

    init { reload(0) }

    private suspend fun fetch(offset: Int, q: CatalogQuery = query) = c.a365.catalog(
        query = q.search, offset = offset, limit = PAGE, type = q.kind, airing = q.airing,
        year = q.year, genre = q.genreId,
    )

    fun update(q: CatalogQuery) {
        if (q == query) return
        val typing = q.search != query.search
        query = q
        queryVersion++
        resetRandomPool()
        reload(if (typing) 380 else 0)
    }

    fun rememberSearch(raw: String = query.search) {
        val trimmed = raw.trim()
        if (trimmed.length >= 2) viewModelScope.launch { c.prefs.addRecentSearch(trimmed) }
    }

    fun removeRecentSearch(term: String) = viewModelScope.launch { c.prefs.removeRecentSearch(term) }
    fun clearRecentSearches() = viewModelScope.launch { c.prefs.clearRecentSearches() }

    fun retry() = reload(0)

    private fun resetRandomPool() {
        randomJob?.cancel()
        randomPoolQuery = null
        randomPool.clear()
        randomSeen.clear()
        triedOffsets.clear()
        randomPick = null
        randomError = null
        randomLoading = false
    }

    fun dismissRandom() {
        randomOpen = false
        randomJob?.cancel()
        randomLoading = false
    }

    fun toggleRandomHideListed(hide: Boolean) {
        if (randomHideListed == hide) return
        randomHideListed = hide
        val current = randomPick
        if (hide && current != null && current.id in lists.value.titles) rollRandom()
    }

    /** Picks a random title matching the active catalog filters, fetching extra random pages when available. */
    fun rollRandom() {
        val snap = query
        randomOpen = true
        randomError = null
        randomSpinCount++
        randomJob?.cancel()
        randomJob = viewModelScope.launch {
            if (randomPoolQuery != snap) {
                randomPool.clear()
                randomSeen.clear()
                triedOffsets.clear()
                randomPoolQuery = snap
                if (items.isNotEmpty() && !loading) {
                    randomPool.addAll(items)
                    triedOffsets.add(0)
                }
            }
            val listedIds = lists.value.titles.keys
            val hasFreshInPool = pickRandomSeries(randomPool, randomSeen, listedIds, randomHideListed) != null
            val wideCatalog = snap == CatalogQuery() || (items.size >= PAGE && !end)
            val shouldFetchExtra = randomPool.isEmpty() || !hasFreshInPool || (wideCatalog && randomPool.size <= PAGE)
            if (shouldFetchExtra) {
                randomLoading = true
                if (randomPool.isEmpty()) {
                    runCatching { fetch(0, snap) }
                        .onSuccess { first ->
                            triedOffsets.add(0)
                            mergeRandomPool(first)
                        }
                        .onFailure {
                            if (it is kotlinx.coroutines.CancellationException) throw it
                            randomError = it.userMessage()
                            randomLoading = false
                            return@launch
                        }
                }
                if (randomPool.size >= PAGE) {
                    val candidates = (if (snap == CatalogQuery()) (1..20) else (1..6))
                        .map { it * PAGE }.filterNot { it in triedOffsets }
                    if (candidates.isNotEmpty()) {
                        val off = candidates[Random.nextInt(candidates.size)]
                        triedOffsets.add(off)
                        runCatching { fetch(off, snap) }
                            .onSuccess { extra -> mergeRandomPool(extra) }
                    }
                }
                randomLoading = false
            }

            if (randomPool.isEmpty()) {
                randomPick = null
                randomError = "По выбранным фильтрам ничего не нашлось"
                return@launch
            }
            var chosen = pickRandomSeries(randomPool, randomSeen, listedIds, randomHideListed)
            if (chosen == null) {
                // Exhausted unseen titles in the pool: start a fresh cycle, keeping only the current one out.
                val keepOut = randomPick?.id?.let { setOf(it) } ?: emptySet()
                randomSeen.clear()
                randomSeen.addAll(keepOut)
                chosen = pickRandomSeries(randomPool, randomSeen, listedIds, randomHideListed)
                    ?: pickRandomSeries(randomPool, emptySet(), listedIds, randomHideListed)
            }
            if (chosen != null) {
                randomSeen.add(chosen.id)
                randomPick = chosen
            } else {
                randomError = "По выбранным фильтрам ничего не нашлось"
            }
        }
    }

    private fun mergeRandomPool(batch: List<Series>) {
        val known = randomPool.mapTo(HashSet()) { it.id }
        batch.forEach { if (known.add(it.id)) randomPool.add(it) }
    }

    private fun reload(debounce: Long) {
        job?.cancel()
        end = false; loading = true; error = null; moreError = null
        job = viewModelScope.launch {
            if (debounce > 0) delay(debounce)
            runCatching { fetch(0) }
                .onSuccess { items = it; end = it.size < PAGE }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    error = it.userMessage(); items = emptyList()
                }
            loading = false
        }
    }

    fun loadMore() {
        if (loading || end || job?.isActive == true) return
        moreError = null
        job = viewModelScope.launch {
            loading = true
            runCatching { fetch(items.size) }
                .onSuccess { more ->
                    val known = items.mapTo(HashSet()) { it.id }
                    items = items + more.filter { it.id !in known }
                    end = more.size < PAGE
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    moreError = it.userMessage()
                }
            loading = false
        }
    }
}

/**
 * Picks a random title from [pool], avoiding [excludeIds] and (when [hideListed] is on) titles already in
 * [listedIds]. Falls back to listed titles only when every unseen candidate in the pool is already listed.
 */
internal fun pickRandomSeries(
    pool: List<Series>,
    excludeIds: Set<Long>,
    listedIds: Set<Long>,
    hideListed: Boolean,
    random: Random = Random.Default,
): Series? {
    if (pool.isEmpty()) return null
    val unseen = pool.filterNot { it.id in excludeIds }
    if (unseen.isEmpty()) return null
    val primary = if (hideListed) unseen.filterNot { it.id in listedIds } else unseen
    val source = primary.ifEmpty { unseen }
    return source[random.nextInt(source.size)]
}

/** Human-readable summary of active catalog filters for the Random Anime sheet subtitle. */
internal fun catalogFilterSummary(q: CatalogQuery): String {
    val parts = buildList {
        q.search.trim().takeIf { it.isNotEmpty() }?.let { add("«$it»") }
        q.kind?.let { k -> kinds.firstOrNull { it.first == k }?.second?.let(::add) }
        if (q.airing) add("Онгоинг")
        q.genreId?.let { g -> A365Genres.firstOrNull { it.first == g }?.second?.let(::add) }
        q.year?.let { add(it.toString()) }
    }
    return if (parts.isEmpty()) "По всему каталогу" else parts.joinToString(" · ")
}

private val kinds = listOf(null to "Любой тип", "tv" to "TV сериал", "movie" to "Фильм", "ova" to "OVA", "ona" to "ONA", "special" to "Спешл")
private val statuses = listOf(false to "Любой статус", true to "Онгоинг")
private val years: List<Pair<Int?, String>> = listOf<Pair<Int?, String>>(null to "Любой год") +
    // Next year too: announcements are listed under it well before it starts.
    (java.time.LocalDate.now().year + 1 downTo 1980).map { it to it.toString() }

@Composable
fun CatalogScreen(open: OpenAnime, onSchedule: () -> Unit = {}) {
    val vm = nfViewModel { CatalogVm(it) }
    val lists by vm.lists.collectAsStateWithLifecycle()
    val recentSearches by vm.recentSearches.collectAsStateWithLifecycle(emptyList())
    val marks = remember(lists) { lists.titles.mapValues { it.value.status } }
    val q = vm.query
    var sheet by remember { mutableStateOf<String?>(null) }
    var quick by remember { mutableStateOf<Series?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var searchFocused by remember { mutableStateOf(false) }
    LaunchedEffect(notice) { if (notice != null) { delay(2800); notice = null } }
    val layout = rememberScreenLayout()
    val cols = layout.posterColumns
    val padH = layout.horizontalPadding
    val grid = rememberLazyGridState()
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    var handledQueryVersion by rememberSaveable { mutableIntStateOf(vm.queryVersion) }

    val nearEnd by remember(cols) {
        derivedStateOf {
            val info = grid.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - cols * 3
        }
    }
    LaunchedEffect(nearEnd, vm.items.size, vm.moreError) { if (nearEnd && vm.items.isNotEmpty() && vm.moreError == null) vm.loadMore() }
    LaunchedEffect(vm.queryVersion) {
        if (handledQueryVersion != vm.queryVersion) {
            handledQueryVersion = vm.queryVersion
            grid.scrollToItem(0)
        }
    }

    val showRecent = searchFocused && q.search.isEmpty() && recentSearches.isNotEmpty()

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = padH, end = padH, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Каталог", style = Type.Display, color = Nf.Text, modifier = Modifier.weight(1f))
            Row(
                Modifier.height(36.dp).clip(CircleShape)
                    .background(Nf.Violet.copy(alpha = 0.14f))
                    .border(1.dp, Nf.Violet.copy(alpha = 0.42f), CircleShape)
                    .pressable(0.92f) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        focus.clearFocus()
                        vm.rollRandom()
                    }
                    .padding(horizontal = if (layout.isTablet) 12.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Casino, "Случайное аниме", tint = Nf.Violet, modifier = Modifier.size(17.dp))
                if (layout.isTablet) {
                    Spacer(Modifier.width(6.dp))
                    Text("Случайное", style = Type.Label, color = Nf.Text)
                }
            }
            Row(
                Modifier.height(36.dp).clip(CircleShape).background(Nf.Surface).border(1.dp, Nf.Line, CircleShape)
                    .pressable(0.93f) { focus.clearFocus(); onSchedule() }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CalendarMonth, null, tint = Nf.Violet, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Расписание", style = Type.Label, color = Nf.Text)
            }
        }

        if (layout.isExpanded) {
            Row(
                Modifier.fillMaxWidth().padding(start = padH, end = padH, bottom = if (showRecent) 6.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CatalogSearchBox(
                    value = q.search,
                    focused = searchFocused,
                    onFocusChange = { searchFocused = it },
                    onValue = { vm.update(q.copy(search = it)) },
                    onSearch = { vm.rememberSearch(); focus.clearFocus() },
                    modifier = Modifier.width(360.dp),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    item { FilterPill(kinds.first { it.first == q.kind }.second, q.kind != null) { sheet = "kind" } }
                    item { FilterPill(statuses.first { it.first == q.airing }.second, q.airing) { sheet = "status" } }
                    item { FilterPill(A365Genres.firstOrNull { it.first == q.genreId }?.second ?: "Жанр", q.genreId != null) { sheet = "genre" } }
                    item { FilterPill(q.year?.toString() ?: "Год", q.year != null) { sheet = "year" } }
                }
            }
            AnimatedVisibility(showRecent, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                RecentSearchesBar(
                    terms = recentSearches,
                    padH = padH,
                    onPick = { term -> vm.update(q.copy(search = term)); vm.rememberSearch(term); focus.clearFocus() },
                    onRemove = vm::removeRecentSearch,
                    onClearAll = vm::clearRecentSearches,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        } else {
            CatalogSearchBox(
                value = q.search,
                focused = searchFocused,
                onFocusChange = { searchFocused = it },
                onValue = { vm.update(q.copy(search = it)) },
                onSearch = { vm.rememberSearch(); focus.clearFocus() },
                modifier = Modifier.padding(horizontal = padH).fillMaxWidth(),
            )

            AnimatedVisibility(showRecent, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                RecentSearchesBar(
                    terms = recentSearches,
                    padH = padH,
                    onPick = { term -> vm.update(q.copy(search = term)); vm.rememberSearch(term); focus.clearFocus() },
                    onRemove = vm::removeRecentSearch,
                    onClearAll = vm::clearRecentSearches,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = padH), horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                item { FilterPill(kinds.first { it.first == q.kind }.second, q.kind != null) { sheet = "kind" } }
                item { FilterPill(statuses.first { it.first == q.airing }.second, q.airing) { sheet = "status" } }
                item { FilterPill(A365Genres.firstOrNull { it.first == q.genreId }?.second ?: "Жанр", q.genreId != null) { sheet = "genre" } }
                item { FilterPill(q.year?.toString() ?: "Год", q.year != null) { sheet = "year" } }
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                vm.error != null -> EmptyState("Не получилось загрузить", vm.error, Icons.Rounded.WifiOff, Modifier.padding(top = 40.dp), "Повторить", vm::retry)
                !vm.loading && vm.items.isEmpty() -> EmptyState("Ничего не нашлось", "Попробуйте другое название или уберите фильтры", Icons.Rounded.SearchOff, Modifier.padding(top = 40.dp))
                else -> LazyVerticalGrid(
                    GridCells.Fixed(cols), state = grid,
                    contentPadding = PaddingValues(start = padH, end = padH, top = 4.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (layout.isTablet) 14.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (vm.items.isEmpty()) items(cols * 4) { PosterCardSkeleton(width = null) }
                    else {
                        items(vm.items, key = { it.id }, contentType = { "card" }) { a ->
                            PosterCard(
                                a.stub(), "catalog",
                                onClick = {
                                    if (q.search.isNotBlank()) vm.rememberSearch()
                                    focus.clearFocus()
                                    open(a.stub(), "catalog")
                                },
                                modifier = Modifier.animateItem(), width = null, listStatus = marks[a.id],
                                onLongClick = {
                                    if (q.search.isNotBlank()) vm.rememberSearch()
                                    focus.clearFocus()
                                    quick = a
                                },
                            )
                        }
                        if (vm.loading) item(span = { GridItemSpan(cols) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), color = Nf.Violet, strokeWidth = 2.5.dp)
                            }
                        } else if (vm.moreError != null) item(span = { GridItemSpan(cols) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(18.dp))
                                    .background(Nf.Surface).border(1.dp, Nf.Line, RoundedCornerShape(18.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.WifiOff, null, tint = Nf.TextMute, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(vm.moreError.orEmpty(), style = Type.Caption, color = Nf.TextDim, modifier = Modifier.weight(1f), maxLines = 2)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Повторить", style = Type.Label, color = Nf.Text,
                                    modifier = Modifier.clip(CircleShape).background(Nf.SurfaceHi).pressable(0.94f) { vm.loadMore() }.padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

        var lastNotice by remember { mutableStateOf("") }
        if (notice != null) lastNotice = notice!!
        AnimatedVisibility(
            notice != null, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 96.dp),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Text(
                lastNotice, style = Type.Label, color = Nf.Text, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Nf.SurfaceHi).border(1.dp, Nf.Line, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    quick?.let { a ->
        QuickAnimeSheet(
            stub = a.stub(),
            series = a,
            onDismiss = { quick = null },
            onOpen = { open(a.stub(), "catalog") },
            onNotice = { notice = it },
        )
    }

    if (vm.randomOpen) {
        RandomAnimeSheet(
            filterSummary = catalogFilterSummary(q),
            pick = vm.randomPick,
            loading = vm.randomLoading,
            error = vm.randomError,
            spinCount = vm.randomSpinCount,
            hasLists = lists.titles.isNotEmpty(),
            hideListed = vm.randomHideListed,
            listStatus = vm.randomPick?.let { marks[it.id] },
            onToggleHideListed = vm::toggleRandomHideListed,
            onRollAgain = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                vm.rollRandom()
            },
            onOpen = { s ->
                vm.dismissRandom()
                open(s.stub(), "random")
            },
            onDismiss = vm::dismissRandom,
        )
    }

    when (sheet) {
        "kind" -> OptionSheet("Тип", kinds.map { it.first to it.second }, q.kind, { sheet = null }) { vm.update(q.copy(kind = it)) }
        "status" -> OptionSheet("Статус", statuses, q.airing, { sheet = null }) { vm.update(q.copy(airing = it)) }
        "year" -> OptionSheet("Год", years, q.year, { sheet = null }) { vm.update(q.copy(year = it)) }
        "genre" -> OptionSheet("Жанр", listOf<Pair<Int?, String>>(null to "Любой жанр") + A365Genres.map { it.first to it.second }, q.genreId, { sheet = null }) { vm.update(q.copy(genreId = it)) }
    }
}

@Composable
private fun CatalogSearchBox(
    value: String,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onValue: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border by animateColorAsState(if (focused || value.isNotEmpty()) Nf.Violet.copy(alpha = 0.55f) else Nf.Line, label = "csb")
    Row(
        modifier.height(50.dp).clip(CircleShape).background(Nf.Surface)
            .border(1.dp, border, CircleShape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = if (focused || value.isNotEmpty()) Nf.Violet else Nf.TextMute, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Поиск по каталогу Anime 365", style = Type.Body, color = Nf.TextMute, maxLines = 1)
            BasicTextField(
                value, onValue, singleLine = true,
                textStyle = Type.Body.copy(color = Nf.Text), cursorBrush = SolidColor(Nf.Violet),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChange(it.isFocused) },
            )
        }
        if (value.isNotEmpty()) Icon(
            Icons.Rounded.Close, "Очистить", tint = Nf.TextDim,
            modifier = Modifier.size(30.dp).clip(CircleShape).clickable { onValue("") }.padding(5.dp),
        )
    }
}

@Composable
private fun RecentSearchesBar(
    terms: List<String>,
    padH: androidx.compose.ui.unit.Dp,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = padH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("НЕДАВНИЕ ЗАПРОСЫ", style = Type.Overline, color = Nf.TextMute, modifier = Modifier.weight(1f))
            Text(
                "Очистить",
                style = Type.Caption,
                color = Nf.TextDim,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClearAll).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = padH),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(terms, key = { it.lowercase() }) { term ->
                Row(
                    Modifier.clip(CircleShape).background(Nf.Surface).border(1.dp, Nf.Line, CircleShape)
                        .pressable(0.95f) { onPick(term) }
                        .padding(start = 11.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.History, null, tint = Nf.Violet.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        term,
                        style = Type.Label,
                        color = Nf.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 170.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Rounded.Close, "Удалить запрос", tint = Nf.TextMute,
                        modifier = Modifier.size(22.dp).clip(CircleShape).clickable { onRemove(term) }.padding(4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RandomAnimeSheet(
    filterSummary: String,
    pick: Series?,
    loading: Boolean,
    error: String?,
    spinCount: Int,
    hasLists: Boolean,
    hideListed: Boolean,
    listStatus: ListStatus?,
    onToggleHideListed: (Boolean) -> Unit,
    onRollAgain: () -> Unit,
    onOpen: (Series) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val diceAngle by animateFloatAsState(
        targetValue = spinCount * 360f,
        animationSpec = tween(durationMillis = 420, easing = FluidEasing),
        label = "dice",
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Nf.Surface,
        contentColor = Nf.Text,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(40.dp, 4.dp).clip(CircleShape).background(Nf.Line)) },
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 22.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Nf.Violet.copy(alpha = 0.28f), Nf.Pink.copy(alpha = 0.18f))))
                            .border(1.dp, Nf.Violet.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Casino, null, tint = Nf.Violet,
                            modifier = Modifier.size(21.dp).graphicsLayer { rotationZ = diceAngle },
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Случайное аниме", style = Type.Section, color = Nf.Text)
                        Text(filterSummary, style = Type.Caption, color = Nf.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                if (hasLists) {
                    Spacer(Modifier.height(12.dp))
                    val bg by animateColorAsState(if (hideListed) Nf.Mint.copy(alpha = 0.14f) else Nf.Ink, label = "hl-bg")
                    val br by animateColorAsState(if (hideListed) Nf.Mint.copy(alpha = 0.42f) else Nf.Line, label = "hl-br")
                    Row(
                        Modifier.clip(CircleShape).background(bg).border(1.dp, br, CircleShape)
                            .pressable(0.95f) { onToggleHideListed(!hideListed) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (hideListed) Icons.Rounded.Check else Icons.Rounded.VisibilityOff,
                            null,
                            tint = if (hideListed) Nf.Mint else Nf.TextMute,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "Скрывать тайтлы из моих списков",
                            style = Type.Caption,
                            color = if (hideListed) Nf.Text else Nf.TextDim,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                AnimatedContent(
                    targetState = Triple(pick, loading && pick == null, error),
                    transitionSpec = {
                        (fadeIn(tween(240)) + scaleIn(initialScale = 0.96f, animationSpec = tween(240))) togetherWith
                            fadeOut(tween(140))
                    },
                    label = "random-card",
                ) { (series, skeleton, err) ->
                    when {
                        skeleton -> Box(
                            Modifier.fillMaxWidth().height(156.dp).clip(RoundedCornerShape(22.dp)).shimmer(),
                        )
                        err != null -> Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Nf.Ink)
                                .border(1.dp, Nf.Line, RoundedCornerShape(22.dp)).padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Rounded.SearchOff, null, tint = Nf.TextMute, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(err, style = Type.Label, color = Nf.TextDim)
                        }
                        series != null -> {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                                    .background(Brush.linearGradient(listOf(Nf.Violet.copy(alpha = 0.14f), Nf.Ink, Nf.Ink)))
                                    .border(1.dp, Nf.Violet.copy(alpha = 0.25f), RoundedCornerShape(22.dp))
                                    .pressable(0.98f) { onOpen(series) }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                PosterImage(
                                    series.posterUrl ?: series.posterUrlSmall,
                                    Modifier.width(96.dp).aspectRatio(0.7f).clip(RoundedCornerShape(14.dp)),
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        series.title,
                                        style = Type.Title.copy(fontSize = 16.sp),
                                        color = Nf.Text,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    series.original?.let { orig ->
                                        Text(
                                            orig,
                                            style = Type.Caption,
                                            color = Nf.TextMute,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                    Row(
                                        Modifier.padding(top = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        formatScore(series.score)?.let { sc ->
                                            Icon(Icons.Rounded.Star, null, tint = Nf.Amber, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(3.dp))
                                            Text(sc, style = Type.Label, color = Nf.Amber)
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        val meta = buildList {
                                            if (series.year > 0) add(series.year.toString())
                                            kindLabel(series.type).takeIf { it.isNotBlank() }?.let(::add)
                                            when {
                                                series.airing -> add("Онгоинг")
                                                series.numberOfEpisodes > 1 -> add("${series.numberOfEpisodes} ${ListProgress.plural(series.numberOfEpisodes)}")
                                            }
                                        }.joinToString(" · ")
                                        if (meta.isNotBlank()) {
                                            Text(meta, style = Type.Caption, color = Nf.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    if (listStatus != null) {
                                        val sc = statusColor(listStatus)
                                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(7.dp).clip(CircleShape).background(sc))
                                            Spacer(Modifier.width(6.dp))
                                            Text("В списке «${listStatus.title}»", style = Type.Caption, color = sc, maxLines = 1)
                                        }
                                    }
                                    val genres = series.genres.orEmpty().mapNotNull { g -> g.title.trim().takeIf { it.isNotEmpty() } }.take(3)
                                    if (genres.isNotEmpty()) {
                                        FlowRow(
                                            Modifier.padding(top = 10.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            genres.forEach { g ->
                                                Text(
                                                    g,
                                                    style = Type.Caption.copy(fontSize = 11.sp),
                                                    color = Nf.TextDim,
                                                    modifier = Modifier.clip(CircleShape).background(Nf.SurfaceHi)
                                                        .border(1.dp, Nf.Line, CircleShape)
                                                        .padding(horizontal = 9.dp, vertical = 3.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(16.dp))
                            .background(Nf.SurfaceHi).border(1.dp, Nf.Line, RoundedCornerShape(16.dp))
                            .pressable(0.95f, enabled = !loading, onClick = onRollAgain)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Nf.Violet, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Casino, null, tint = Nf.Violet, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Ещё раз", style = Type.Label.copy(fontSize = 14.sp), color = Nf.Text)
                    }
                    Row(
                        Modifier.weight(1.2f).height(50.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (pick != null) Nf.Violet else Nf.SurfaceHi)
                            .pressable(0.95f, enabled = pick != null) { pick?.let(onOpen) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "Открыть",
                            style = Type.Title.copy(fontSize = 14.5.sp),
                            color = if (pick != null) Color(0xFF14102A) else Nf.TextMute,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            null,
                            tint = if (pick != null) Color(0xFF14102A) else Nf.TextMute,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPill(text: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (active) Nf.Violet.copy(alpha = 0.18f) else Nf.Surface)
            .border(1.dp, if (active) Nf.Violet.copy(alpha = 0.45f) else Nf.Line, CircleShape)
            .clickable(onClick = onClick).padding(start = 14.dp, end = 9.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.Label, color = if (active) Nf.Violet else Nf.TextDim)
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = if (active) Nf.Violet else Nf.TextMute, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun <T> OptionSheet(title: String, options: List<Pair<T, String>>, selected: T, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Nf.Surface, contentColor = Nf.Text) {
        Text(title, style = Type.Section, color = Nf.Text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp, top = 6.dp)) {
            items(options) { (value, label) ->
                val on = value == selected
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(value); onDismiss() }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = Type.Title, color = if (on) Nf.Violet else Nf.Text, modifier = Modifier.weight(1f))
                    if (on) Icon(Icons.Rounded.Check, null, tint = Nf.Violet, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
