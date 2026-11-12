@file:OptIn(ExperimentalMaterial3Api::class)

package app.neifur.ui.screens

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.neifur.Container
import app.neifur.OpenAnime
import app.neifur.core.userMessage
import app.neifur.data.a365.A365Genres
import app.neifur.data.a365.Series
import app.neifur.nfViewModel
import app.neifur.stub
import app.neifur.ui.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var query by mutableStateOf(CatalogQuery())
        private set
    var items by mutableStateOf<List<Series>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var end = false
    private var job: Job? = null

    init { reload(0) }

    private suspend fun fetch(offset: Int) = c.a365.catalog(
        query = query.search, offset = offset, limit = PAGE, type = query.kind, airing = query.airing,
        year = query.year, genre = query.genreId,
    )

    fun update(q: CatalogQuery) {
        val typing = q.search != query.search
        query = q
        reload(if (typing) 380 else 0)
    }

    fun retry() = reload(0)

    private fun reload(debounce: Long) {
        job?.cancel()
        end = false; loading = true; error = null
        job = viewModelScope.launch {
            if (debounce > 0) delay(debounce)
            runCatching { fetch(0) }
                .onSuccess { items = it; end = it.size < PAGE }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) { error = it.userMessage(); items = emptyList() } }
            loading = false
        }
    }

    fun loadMore() {
        if (loading || end || job?.isActive == true) return
        job = viewModelScope.launch {
            loading = true
            runCatching { fetch(items.size) }.onSuccess { more ->
                val known = items.mapTo(HashSet()) { it.id }
                items = items + more.filter { it.id !in known }
                end = more.size < PAGE
            }
            loading = false
        }
    }
}

private val kinds = listOf(null to "Любой тип", "tv" to "TV сериал", "movie" to "Фильм", "ova" to "OVA", "ona" to "ONA", "special" to "Спешл")
private val statuses = listOf(false to "Любой статус", true to "Онгоинг")
private val years: List<Pair<Int?, String>> = listOf<Pair<Int?, String>>(null to "Любой год") +
    (java.time.LocalDate.now().year downTo 1980).map { it to it.toString() }

@Composable
fun CatalogScreen(open: OpenAnime) {
    val vm = nfViewModel { CatalogVm(it) }
    val q = vm.query
    var sheet by remember { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    val focus = LocalFocusManager.current

    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 9 } }
    LaunchedEffect(nearEnd, vm.items.size) { if (nearEnd && vm.items.isNotEmpty()) vm.loadMore() }
    LaunchedEffect(q) { grid.scrollToItem(0) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Каталог", style = Type.Display, color = Nf.Text, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 14.dp))

        Row(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(50.dp).clip(CircleShape).background(Nf.Surface)
                .border(1.dp, Nf.Line, CircleShape).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = Nf.TextMute, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (q.search.isEmpty()) Text("Поиск по каталогу Anime 365", style = Type.Body, color = Nf.TextMute, maxLines = 1)
                BasicTextField(
                    q.search, { vm.update(q.copy(search = it)) }, singleLine = true,
                    textStyle = Type.Body.copy(color = Nf.Text), cursorBrush = SolidColor(Nf.Violet),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (q.search.isNotEmpty()) Icon(
                Icons.Rounded.Close, "Очистить", tint = Nf.TextDim,
                modifier = Modifier.size(30.dp).clip(CircleShape).clickable { vm.update(q.copy(search = "")) }.padding(5.dp),
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            item { FilterPill(kinds.first { it.first == q.kind }.second, q.kind != null) { sheet = "kind" } }
            item { FilterPill(statuses.first { it.first == q.airing }.second, q.airing) { sheet = "status" } }
            item { FilterPill(A365Genres.firstOrNull { it.first == q.genreId }?.second ?: "Жанр", q.genreId != null) { sheet = "genre" } }
            item { FilterPill(q.year?.toString() ?: "Год", q.year != null) { sheet = "year" } }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                vm.error != null -> EmptyState("Не получилось загрузить", vm.error, Icons.Rounded.WifiOff, Modifier.padding(top = 40.dp), "Повторить", vm::retry)
                !vm.loading && vm.items.isEmpty() -> EmptyState("Ничего не нашлось", "Попробуйте другое название или уберите фильтры", Icons.Rounded.SearchOff, Modifier.padding(top = 40.dp))
                else -> LazyVerticalGrid(
                    GridCells.Fixed(3), state = grid,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (vm.items.isEmpty()) items(12) { PosterCardSkeleton(width = null) }
                    else {
                        items(vm.items, key = { it.id }, contentType = { "card" }) { a ->
                            PosterCard(a.stub(), "catalog", { focus.clearFocus(); open(a.stub(), "catalog") }, Modifier.animateItem(), width = null)
                        }
                        if (vm.loading) item(span = { GridItemSpan(3) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), color = Nf.Violet, strokeWidth = 2.5.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    when (sheet) {
        "kind" -> OptionSheet("Тип", kinds.map { it.first to it.second }, q.kind, { sheet = null }) { vm.update(q.copy(kind = it)) }
        "status" -> OptionSheet("Статус", statuses, q.airing, { sheet = null }) { vm.update(q.copy(airing = it)) }
        "year" -> OptionSheet("Год", years, q.year, { sheet = null }) { vm.update(q.copy(year = it)) }
        "genre" -> OptionSheet("Жанр", listOf<Pair<Int?, String>>(null to "Любой жанр") + A365Genres.map { it.first to it.second }, q.genreId, { sheet = null }) { vm.update(q.copy(genreId = it)) }
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
