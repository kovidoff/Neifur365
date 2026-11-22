@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package app.neifur.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
import app.neifur.data.a365.ListProgress
import app.neifur.data.a365.ListRecord
import app.neifur.data.a365.ListSort
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.ListedTitle
import app.neifur.nfViewModel
import app.neifur.stub
import app.neifur.ui.*
import kotlinx.coroutines.launch

class LibraryVm(private val c: Container) : ViewModel() {
    val account = c.account.state
    val expired = c.account.expired
    val lists = c.lists.state
    val history = c.prefs.history
    val favorites = c.prefs.favorites
    val alertsAsked = c.prefs.alertsAsked
    val alertsOn = c.prefs.alerts
    var status by mutableStateOf(ListStatus.Watching)
    var query by mutableStateOf("")
    var sort by mutableStateOf(ListSort.Default)
    var notice by mutableStateOf<String?>(null)

    fun refresh() = c.lists.refresh()
    fun refreshIfStale() = c.lists.refreshIfStale()

    fun bump(t: ListedTitle) = viewModelScope.launch {
        c.lists.bump(t.entry.seriesId).onFailure { notice = it.userMessage() }
    }

    fun change(t: ListedTitle, s: ListStatus?, watched: Int, score: Int?) = viewModelScope.launch {
        c.lists.set(t.entry.seriesId, s, watched, score).onFailure { notice = it.userMessage() }
    }

    fun forget(animeId: String) = viewModelScope.launch { c.prefs.removeFromHistory(animeId) }
}

/**
 * The viewer's lists from Anime 365, the way people keep them: what they watch (with how far along and what's
 * new), what they plan, what they finished (with their score), what they paused or dropped. Changes made anywhere
 * show here at once; a long press edits an entry in place.
 */
@Composable
fun LibraryScreen(open: OpenAnime) {
    val vm = nfViewModel { LibraryVm(it) }
    val account by vm.account.collectAsStateWithLifecycle()
    val expired by vm.expired.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle(emptyList())
    val favorites by vm.favorites.collectAsStateWithLifecycle(emptyList())
    val alertsAsked by vm.alertsAsked.collectAsStateWithLifecycle(true)
    val alertsOn by vm.alertsOn.collectAsStateWithLifecycle(true)
    val recent = history.distinctBy { it.animeId }.take(12)
    var editing by remember { mutableStateOf<ListedTitle?>(null) }
    var signIn by remember { mutableStateOf(false) }
    var sortSheet by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val signedIn = account is AccountState.SignedIn
    LaunchedEffect(signedIn) { if (signedIn) vm.refreshIfStale() }
    LaunchedEffect(vm.notice) { if (vm.notice != null) { kotlinx.coroutines.delay(3500); vm.notice = null } }

    val items = remember(lists, vm.status, history, vm.query, vm.sort) {
        lists.display(vm.status, history, vm.query, vm.sort)
    }
    val otherMatches = remember(lists, vm.status, vm.query, items.isEmpty()) {
        if (items.isEmpty() && vm.query.isNotBlank()) lists.matchesInOtherTabs(vm.status, vm.query) else emptyList()
    }
    val showTools = signedIn && lists.loaded && (lists.count(vm.status) > 0 || vm.query.isNotBlank() || vm.sort != ListSort.Default)
    val wide = vm.status != ListStatus.Planned && vm.status != ListStatus.Completed

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = signedIn && lists.loading && lists.loaded, onRefresh = { if (signedIn) vm.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyVerticalGrid(
                GridCells.Fixed(3), modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(span = { GridItemSpan(3) }, key = "head") { Header(account) }

                if (recent.isNotEmpty() && vm.query.isBlank()) item(span = { GridItemSpan(3) }, key = "continue") {
                    ContinueStrip(recent, lists.titles, open, onForget = { vm.forget(it) })
                }

                if (signedIn) {
                    item(span = { GridItemSpan(3) }, key = "tabs") {
                        StatusTabs(vm.status, { lists.count(it) }, lists.loaded) { vm.status = it }
                    }
                    if (showTools) item(span = { GridItemSpan(3) }, key = "tools") {
                        ListToolsRow(
                            status = vm.status,
                            query = vm.query,
                            sort = vm.sort,
                            onQuery = { vm.query = it },
                            onDone = { focus.clearFocus() },
                            onSort = { focus.clearFocus(); sortSheet = true },
                        )
                    }
                    // Asked once, where it makes sense: above what the viewer is watching.
                    if (vm.status == ListStatus.Watching && vm.query.isBlank() && lists.loaded && lists.count(ListStatus.Watching) > 0 && !alertsAsked && !alertsOn) {
                        item(span = { GridItemSpan(3) }, key = "alerts-offer") { AlertsOffer(Modifier.animateItem()) }
                    }
                    when {
                        !lists.loaded && lists.error != null -> item(span = { GridItemSpan(3) }) {
                            EmptyState("Не получилось загрузить списки", lists.error, Icons.Rounded.WifiOff, action = "Повторить", onAction = vm::refresh)
                        }
                        !lists.loaded -> items(6, span = { GridItemSpan(if (wide) 3 else 1) }) {
                            if (wide) Box(Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(20.dp)).shimmer())
                            else PosterCardSkeleton(width = null)
                        }
                        items.isEmpty() && vm.query.isNotBlank() -> item(span = { GridItemSpan(3) }, key = "empty-search") {
                            EmptySearch(vm.status, vm.query, otherMatches, onSwitch = { vm.status = it }, onClear = { vm.query = "" })
                        }
                        items.isEmpty() -> item(span = { GridItemSpan(3) }) { EmptyList(vm.status) }
                        wide -> items(items, key = { "w${it.entry.seriesId}" }, span = { GridItemSpan(3) }, contentType = { "wide" }) { t ->
                            WideRow(t, Modifier.animateItem(), onOpen = { focus.clearFocus(); open(stubOf(t), "lists") }, onEdit = { editing = t }, onBump = { vm.bump(t) })
                        }
                        else -> items(items, key = { "g${it.entry.seriesId}" }, contentType = { "card" }) { t ->
                            GridCard(t, Modifier.animateItem(), onOpen = { focus.clearFocus(); open(stubOf(t), "lists") }, onEdit = { editing = t })
                        }
                    }
                } else {
                    if (account == AccountState.SignedOut) item(span = { GridItemSpan(3) }, key = "signin") { SignInCard(expired) { signIn = true } }
                    if (favorites.isNotEmpty()) {
                        item(span = { GridItemSpan(3) }) { Text("Избранное на этом телефоне", style = Type.Section, color = Nf.Text, modifier = Modifier.padding(top = 10.dp)) }
                        items(favorites, key = { "f-${it.id}" }) { f ->
                            val stub = AnimeStub(f.id, f.title, f.poster, f.kind, f.year, f.score)
                            PosterCard(stub, "library-f", { open(stub, "library-f") }, Modifier.animateItem(), width = null)
                        }
                    }
                }
            }
        }

        var lastNotice by remember { mutableStateOf("") }
        if (vm.notice != null) lastNotice = vm.notice!!
        AnimatedVisibility(
            vm.notice != null, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            Text(
                lastNotice, style = Type.Label, color = Nf.Text,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Nf.SurfaceHi).border(1.dp, Nf.Line, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    editing?.let { t ->
        // Read the live entry: the editor's own changes land in the shared lists and come back here.
        val live = lists.titles[t.entry.seriesId] ?: t
        ListEditor(
            title = live.series?.title ?: live.entry.title.substringBefore(" / "),
            record = if (lists.titles.containsKey(t.entry.seriesId)) ListRecord(live.status, live.entry.watched, live.entry.score) else ListRecord(null, 0, null),
            total = live.total, aired = live.aired, isMovie = live.series?.isMovie == true,
            onDismiss = { editing = null },
            onChange = { s, w, sc -> vm.change(live, s, w, sc) },
        )
    }
    if (sortSheet) OptionSheet(
        title = "Сортировка",
        options = ListSort.entries.map { s ->
            val label = if (s == ListSort.Default && vm.status == ListStatus.Watching) "Умная (недавние и новые)" else s.label
            s to label
        },
        selected = vm.sort,
        onDismiss = { sortSheet = false },
        onPick = { vm.sort = it },
    )
    if (signIn) SignInSheet(onDismiss = { signIn = false }, onDone = { signIn = false })
}

/** Compact search + sort bar tucked directly under the status pills. */
@Composable
private fun ListToolsRow(
    status: ListStatus,
    query: String,
    sort: ListSort,
    onQuery: (String) -> Unit,
    onDone: () -> Unit,
    onSort: () -> Unit,
) {
    val searchActive = query.isNotBlank()
    val sortActive = sort != ListSort.Default
    val searchBorder by animateColorAsState(if (searchActive) Nf.Violet.copy(alpha = 0.5f) else Nf.Line, label = "lsb")
    val sortBg by animateColorAsState(if (sortActive) Nf.Violet.copy(alpha = 0.16f) else Nf.Surface, label = "lsbg")
    val sortBorder by animateColorAsState(if (sortActive) Nf.Violet.copy(alpha = 0.5f) else Nf.Line, label = "lsbr")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(42.dp).clip(CircleShape).background(Nf.Surface)
                .border(1.dp, searchBorder, CircleShape).padding(start = 13.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = if (searchActive) Nf.Violet else Nf.TextMute, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(
                    "Поиск в «${status.title}»", style = Type.Label, color = Nf.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                BasicTextField(
                    query, onQuery, singleLine = true,
                    textStyle = Type.Label.copy(color = Nf.Text), cursorBrush = SolidColor(Nf.Violet),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onDone() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(query.isNotEmpty(), enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
                Icon(
                    Icons.Rounded.Close, "Очистить поиск", tint = Nf.TextDim,
                    modifier = Modifier.size(30.dp).clip(CircleShape).clickable { onQuery("") }.padding(6.dp),
                )
            }
        }
        Row(
            Modifier.height(42.dp).clip(CircleShape).background(sortBg).border(1.dp, sortBorder, CircleShape)
                .pressable(0.94f, onClick = onSort).padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.Sort, "Сортировка", tint = if (sortActive) Nf.Violet else Nf.TextDim, modifier = Modifier.size(18.dp))
            if (sortActive) {
                Spacer(Modifier.width(6.dp))
                Text(sort.chip, style = Type.Label, color = Nf.Violet, maxLines = 1)
            }
        }
    }
}

/** Empty state when filtering inside a tab, with one-tap jumps if another tab has matches. */
@Composable
private fun EmptySearch(
    current: ListStatus,
    query: String,
    others: List<Pair<ListStatus, Int>>,
    onSwitch: (ListStatus) -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, start = 20.dp, end = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Nf.Surface), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.SearchOff, null, tint = Nf.TextMute, modifier = Modifier.size(26.dp))
        }
        Text("В «${current.title}» ничего не нашлось", style = Type.Title, color = Nf.Text, modifier = Modifier.padding(top = 14.dp), textAlign = TextAlign.Center)
        Text("По запросу «${query.trim()}»", style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 4.dp))
        if (others.isNotEmpty()) {
            Text("Нашлось в других списках:", style = Type.Caption, color = Nf.TextDim, modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                others.forEach { (s, n) ->
                    val c = statusColor(s)
                    Row(
                        Modifier.clip(CircleShape).background(c.copy(alpha = 0.14f)).border(1.dp, c.copy(alpha = 0.45f), CircleShape)
                            .pressable(0.94f) { onSwitch(s) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(statusIcon(s), null, tint = c, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("${s.title} · $n", style = Type.Label, color = Nf.Text)
                    }
                }
            }
        }
        Text(
            "Сбросить поиск", style = Type.Label, color = Nf.Violet,
            modifier = Modifier.padding(top = 14.dp).clip(CircleShape).clickable(onClick = onClear).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

private fun stubOf(t: ListedTitle): AnimeStub = t.series?.stub() ?: AnimeStub(t.entry.seriesId.toString(), t.entry.title, null)

@Composable
private fun Header(account: AccountState) {
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Списки", style = Type.Display, color = Nf.Text, modifier = Modifier.weight(1f))
        (account as? AccountState.SignedIn)?.profile?.let { p ->
            Row(
                Modifier.clip(CircleShape).background(Nf.Surface).padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PosterImage(p.avatarUrl, Modifier.size(28.dp).clip(CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(p.name, style = Type.Label, color = Nf.TextDim, maxLines = 1, modifier = Modifier.widthIn(max = 140.dp), overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Status pills with counts; the open one takes its list's colour. */
@Composable
private fun StatusTabs(current: ListStatus, count: (ListStatus) -> Int, loaded: Boolean, onPick: (ListStatus) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        items(ListOrder) { s ->
            val on = s == current
            val c = statusColor(s)
            val bg by animateColorAsState(if (on) c.copy(alpha = 0.18f) else Nf.Surface, label = "tb")
            Row(
                Modifier.clip(CircleShape).background(bg).border(1.dp, if (on) c.copy(alpha = 0.55f) else Nf.Line, CircleShape)
                    .pressable(0.95f) { onPick(s) }.padding(start = 12.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(statusIcon(s), null, tint = if (on) c else Nf.TextMute, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text(s.title, style = Type.Label, color = if (on) Nf.Text else Nf.TextDim)
                if (loaded) Text("  ${count(s)}", style = Type.Label, color = if (on) c else Nf.TextMute)
            }
        }
    }
}

/**
 * A title in progress: poster, how far along, a bar in its list's colour, a "new episodes" mark, and for titles
 * being watched a +1 button that moves the counter on Anime 365.
 */
@Composable
private fun WideRow(t: ListedTitle, modifier: Modifier, onOpen: () -> Unit, onEdit: () -> Unit, onBump: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val c = statusColor(t.status)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Nf.Surface)
            .combinedClickable(onClick = onOpen, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEdit() })
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            PosterImage(t.series?.posterUrlSmall ?: t.series?.posterUrl, Modifier.width(62.dp).aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)))
            if (t.status == ListStatus.Watching && t.behind > 0) Text(
                "+${t.behind}", style = Type.Caption.copy(fontSize = 11.sp), color = Nf.Ink,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(7.dp)).background(Nf.Mint).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.series?.title ?: t.entry.title.substringBefore(" / "), style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (t.status == ListStatus.Watching && t.behind > 0) Text(
                if (t.behind == 1) "Новая серия" else "Новых серий: ${t.behind}",
                style = Type.Caption, color = Nf.Mint, modifier = Modifier.padding(top = 3.dp),
            )
            Text(ListProgress.line(t), style = Type.Caption, color = Nf.TextMute, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
            ListProgress.fraction(t)?.let { f ->
                Box(Modifier.padding(top = 9.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Nf.Line)) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(c))
                }
            }
        }
        if (t.status == ListStatus.Watching && t.series?.isMovie != true) {
            val canBump = t.total?.let { t.entry.watched < it } ?: true
            Spacer(Modifier.width(10.dp))
            // Marks the next episode watched: "+1 серия", in a rounded square like the app's other tiles.
            val tint = if (canBump) c else Nf.TextMute
            Column(
                Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(if (canBump) c.copy(alpha = 0.14f) else Nf.SurfaceHi)
                    .border(1.dp, if (canBump) c.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(16.dp))
                    .pressable(0.9f, enabled = canBump) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onBump() }
                    .semantics(mergeDescendants = true) { contentDescription = "Отметить серию просмотренной" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text("+1", style = Type.Title.copy(fontSize = 17.sp, lineHeight = 19.sp), color = tint, textAlign = TextAlign.Center)
                Text("серия", style = Type.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp), color = tint.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            }
        }
    }
}

/** Planned and finished titles: a poster grid. Finished ones carry the viewer's own score. */
@Composable
private fun GridCard(t: ListedTitle, modifier: Modifier, onOpen: () -> Unit, onEdit: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(modifier.combinedClickable(onClick = onOpen, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEdit() })) {
        Box {
            PosterImage(t.series?.posterUrl ?: t.series?.posterUrlSmall, Modifier.fillMaxWidth().aspectRatio(0.7f).clip(PosterShape))
            val mine = t.entry.score
            if (t.status == ListStatus.Completed && mine != null) Row(
                Modifier.align(Alignment.TopStart).padding(7.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xD9090910))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Star, null, tint = Nf.Amber, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(3.dp))
                Text("$mine", style = Type.Caption, color = Nf.Amber)
            } else ScoreBadge(t.series?.score, Modifier.align(Alignment.TopStart).padding(7.dp))
            if (t.airing) Text(
                "Онгоинг", style = Type.Caption.copy(fontSize = 10.sp), color = Nf.Ink,
                modifier = Modifier.align(Alignment.BottomStart).padding(7.dp).clip(RoundedCornerShape(7.dp)).background(Nf.Mint).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(t.series?.title ?: t.entry.title.substringBefore(" / "), style = Type.Label, color = Nf.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val line = ListProgress.line(t)
        if (line.isNotBlank()) Text(line, style = Type.Caption, color = Nf.TextMute, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun EmptyList(s: ListStatus) {
    val (title, text) = when (s) {
        ListStatus.Watching -> "Пока ничего не смотрите" to "Начните серию — тайтл сам попадёт сюда, когда вы её досмотрите"
        ListStatus.Planned -> "Список планов пуст" to "Добавляйте тайтлы кнопкой закладки на их странице"
        ListStatus.Completed -> "Пока ничего не досмотрено" to "Здесь будут досмотренные тайтлы и ваши оценки"
        ListStatus.OnHold -> "Ничего не отложено" to "Отложите тайтл, если хотите вернуться к нему позже"
        ListStatus.Dropped -> "Ничего не брошено" to "Сюда попадут тайтлы, которые не зашли"
    }
    Column(Modifier.fillMaxWidth().padding(top = 36.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(statusColor(s).copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(statusIcon(s), null, tint = statusColor(s), modifier = Modifier.size(28.dp))
        }
        Text(title, style = Type.Title, color = Nf.Text, modifier = Modifier.padding(top = 16.dp))
        Text(text, style = Type.Body, color = Nf.TextDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Episodes started on this phone, newest first. The cross forgets a title entirely (progress and studio). */
@Composable
private fun ContinueStrip(recent: List<WatchEntry>, listed: Map<Long, ListedTitle>, open: OpenAnime, onForget: (String) -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Text("Продолжить просмотр", style = Type.Section, color = Nf.Text, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 4.dp)) {
            items(recent, key = { it.animeId }) { e ->
                val stub = AnimeStub(e.animeId, e.title, e.poster)
                Row(
                    Modifier.animateItem().width(260.dp).clip(RoundedCornerShape(20.dp)).background(Nf.Surface)
                        .pressable(0.98f) { open(stub, "library-h") }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PosterImage(e.poster, Modifier.sharedPoster("poster/library-h/${e.animeId}", RoundedCornerShape(12.dp)).width(50.dp).aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = Type.Label, color = Nf.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        val (line, progress) = continueLine(e, listed[e.animeId.toLongOrNull()])
                        Text(line, style = Type.Caption, color = Nf.TextMute)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Nf.Line)) {
                            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(CircleShape).background(Nf.Violet))
                        }
                    }
                    Icon(
                        Icons.Rounded.Close, "Забыть тайтл", tint = Nf.TextMute,
                        modifier = Modifier.padding(start = 4.dp).size(32.dp).clip(CircleShape).pressable(0.85f) { onForget(e.animeId) }.padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SignInCard(expired: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Nf.Violet.copy(alpha = 0.16f), Nf.Pink.copy(alpha = 0.07f), Nf.Surface)))
            .border(1.dp, Nf.Violet.copy(alpha = 0.22f), RoundedCornerShape(24.dp)).padding(18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ListOrder.forEach { s ->
                Box(Modifier.size(34.dp).clip(CircleShape).background(statusColor(s).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(statusIcon(s), null, tint = statusColor(s), modifier = Modifier.size(18.dp))
                }
            }
        }
        Text(if (expired) "Вход на Anime 365 закончился" else "Ваши списки Anime 365", style = Type.Title, color = Nf.Text, modifier = Modifier.padding(top = 14.dp))
        Text(
            if (expired) "Сайт завершил сессию — например, после смены пароля или выхода на другом устройстве. Войдите снова, списки вернутся."
            else "Смотрю, запланировано, просмотрено — с прогрессом по сериям и оценками. Войдите, и они появятся здесь.",
            style = Type.Caption, color = Nf.TextDim, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        PrimaryButton(if (expired) "Войти снова" else "Войти в Anime 365", onClick = onClick)
    }
}
