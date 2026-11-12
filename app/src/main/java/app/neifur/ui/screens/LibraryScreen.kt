package app.neifur.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import app.neifur.container
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.neifur.AnimeStub
import app.neifur.Container
import app.neifur.OpenAnime
import app.neifur.core.userMessage
import app.neifur.data.a365.AccountState
import app.neifur.data.a365.ListEntry
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.Series
import app.neifur.nfViewModel
import app.neifur.stub
import app.neifur.ui.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One title of a site list with its card data (the list page itself has only names and counters). */
data class ListItem(val entry: ListEntry, val series: Series?) {
    val stub: AnimeStub get() = series?.stub() ?: AnimeStub(entry.seriesId.toString(), entry.title, null)
}

class LibraryVm(private val c: Container) : ViewModel() {
    val account = c.account.state
    val history = c.prefs.history
    val favorites = c.prefs.favorites
    var status by mutableStateOf(ListStatus.Watching)
        private set
    var items by mutableStateOf<Load<List<ListItem>>>(Load.Loading)
        private set
    private val cache = HashMap<ListStatus, List<ListItem>>()
    private var job: Job? = null

    init {
        viewModelScope.launch { c.account.state.collect { if (it is AccountState.SignedIn) { cache.clear(); load(status) } } }
    }

    fun select(s: ListStatus) { status = s; load(s) }

    fun refresh() { cache.remove(status); load(status) }

    private fun load(s: ListStatus) {
        val user = c.account.profile ?: return
        cache[s]?.let { items = Load.Ready(it); return }
        items = Load.Loading
        job?.cancel()
        job = viewModelScope.launch {
            items = runCatching {
                val entries = c.a365.list(user.id, s)
                val cards = runCatching { c.a365.cards(entries.map { it.seriesId }) }.getOrDefault(emptyList()).associateBy { it.id }
                entries.map { ListItem(it, cards[it.seriesId]) }.also { cache[s] = it }
            }.fold({ Load.Ready(it) }, { Load.Error(it.userMessage()) })
        }
    }
}

@Composable
fun LibraryScreen(open: OpenAnime) {
    val vm = nfViewModel { LibraryVm(it) }
    val account by vm.account.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle(emptyList())
    val favorites by vm.favorites.collectAsStateWithLifecycle(emptyList())
    val recent = history.distinctBy { it.animeId }.take(12)
    val prefs = LocalContext.current.container.prefs
    val scope = rememberCoroutineScope()
    var signIn by remember { mutableStateOf(false) }
    val signedIn = account is AccountState.SignedIn

    LazyVerticalGrid(
        GridCells.Fixed(3), modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(3) }) {
            Text("Моё", style = Type.Display, color = Nf.Text, modifier = Modifier.padding(top = 14.dp))
        }

        if (recent.isNotEmpty()) item(span = { GridItemSpan(3) }) {
            Column {
                Text("Продолжить просмотр", style = Type.Section, color = Nf.Text, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 4.dp)) {
                    items(recent, key = { it.animeId }) { e ->
                        val stub = AnimeStub(e.animeId, e.title, e.poster)
                        Row(
                            Modifier.width(260.dp).clip(RoundedCornerShape(20.dp)).background(Nf.Surface)
                                .pressable(0.98f) { open(stub, "library-h") }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PosterImage(e.poster, Modifier.sharedPoster("poster/library-h/${e.animeId}", RoundedCornerShape(12.dp)).width(50.dp).aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.title, style = Type.Label, color = Nf.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(3.dp))
                                Text(if (e.finished) "Серия ${e.episode} просмотрена" else "Серия ${e.episode} · ${remaining(e)}", style = Type.Caption, color = Nf.TextMute)
                                Spacer(Modifier.height(8.dp))
                                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Nf.Line)) {
                                    Box(Modifier.fillMaxWidth(e.progress).fillMaxHeight().clip(CircleShape).background(Nf.Violet))
                                }
                            }
                            Icon(
                                Icons.Rounded.Close, "Убрать из истории", tint = Nf.TextMute,
                                modifier = Modifier.padding(start = 4.dp).size(32.dp).clip(CircleShape)
                                    .pressable(0.85f) { scope.launch { prefs.removeFromHistory(e.animeId) } }.padding(8.dp),
                            )
                        }
                    }
                }
            }
        }

        if (signedIn) {
            item(span = { GridItemSpan(3) }) { StatusTabs(vm.status, vm::select) }
            when (val l = vm.items) {
                Load.Loading -> items(9) { PosterCardSkeleton(width = null) }
                is Load.Error -> item(span = { GridItemSpan(3) }) {
                    EmptyState("Не получилось загрузить список", l.message, Icons.Rounded.WifiOff, action = "Повторить", onAction = vm::refresh)
                }
                is Load.Ready -> if (l.value.isEmpty()) item(span = { GridItemSpan(3) }) {
                    EmptyState("Здесь пусто", "Добавляйте тайтлы в «${vm.status.title}» кнопкой закладки на их странице", Icons.Rounded.BookmarkBorder, Modifier.padding(top = 10.dp))
                } else items(l.value, key = { "l-${it.entry.seriesId}" }) { item ->
                    Column(Modifier.animateItem()) {
                        PosterCard(item.stub, "library-l", { open(item.stub, "library-l") }, width = null)
                        val e = item.entry
                        // Ongoing titles have no total on the site ("3 / ?"): show the count alone.
                        val total = e.total ?: item.series?.numberOfEpisodes?.takeIf { it > 0 && item.series.airing.not() }
                        if (total != null && total > 0) {
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Nf.Line)) {
                                Box(Modifier.fillMaxWidth((e.watched / total.toFloat()).coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Nf.Violet))
                            }
                            Text("${e.watched} из $total", style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 3.dp))
                        } else if (e.watched > 0) {
                            Text("Просмотрено: ${e.watched}", style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        } else {
            if (account == AccountState.SignedOut) item(span = { GridItemSpan(3) }) {
                Row(
                    Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Nf.Violet.copy(alpha = 0.1f))
                        .border(1.dp, Nf.Violet.copy(alpha = 0.25f), RoundedCornerShape(22.dp)).pressable(0.98f) { signIn = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(Nf.Violet.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.Login, null, tint = Nf.Violet, modifier = Modifier.size(21.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Войдите в Anime 365", style = Type.Title, color = Nf.Text)
                        Text("Здесь появятся ваши списки с сайта, а серии начнут отмечаться сами", style = Type.Caption, color = Nf.TextDim)
                    }
                }
            }
            if (favorites.isEmpty() && recent.isEmpty()) item(span = { GridItemSpan(3) }) {
                EmptyState(
                    "Здесь будет ваша полка", "Начатые тайтлы и избранное появятся на этом экране",
                    Icons.Rounded.BookmarkBorder, Modifier.padding(top = 40.dp),
                )
            }
            if (favorites.isNotEmpty()) {
                item(span = { GridItemSpan(3) }) { Text("Избранное", style = Type.Section, color = Nf.Text, modifier = Modifier.padding(top = 14.dp)) }
                items(favorites, key = { "f-${it.id}" }) { f ->
                    val stub = AnimeStub(f.id, f.title, f.poster, f.kind, f.year, f.score)
                    PosterCard(stub, "library-f", { open(stub, "library-f") }, Modifier.animateItem(), width = null)
                }
            }
        }
    }
    if (signIn) SignInSheet(onDismiss = { signIn = false }, onDone = { signIn = false })
}

@Composable
private fun StatusTabs(current: ListStatus, onPick: (ListStatus) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        items(ListStatus.entries) { s ->
            val on = s == current
            val bg by animateColorAsState(if (on) Nf.Violet.copy(alpha = 0.2f) else Nf.Surface, label = "st")
            Text(
                s.title, style = Type.Label, color = if (on) Nf.Violet else Nf.TextDim,
                modifier = Modifier.clip(CircleShape).background(bg)
                    .border(1.dp, if (on) Nf.Violet.copy(alpha = 0.45f) else Nf.Line, CircleShape)
                    .clickable { onPick(s) }.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}
