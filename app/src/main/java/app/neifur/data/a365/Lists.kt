package app.neifur.data.a365

import app.neifur.core.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A title in the viewer's lists, with what the list screen needs to show about it. */
data class ListedTitle(
    val entry: ListEntry,
    val series: Series?,
    /** Episodes out so far, for airing titles (null when not known yet or not airing). */
    val aired: Int? = null,
) {
    val status: ListStatus? get() = entry.status
    val airing: Boolean get() = series?.airing == true
    /** The full count: the site's, or the catalog's when the site writes "?". */
    val total: Int? get() = entry.total ?: series?.numberOfEpisodes?.takeIf { it > 0 }
    /** New episodes out that the viewer hasn't marked yet (airing titles). */
    val behind: Int get() = ((aired ?: 0) - entry.watched).coerceAtLeast(0)
}

/** Ways to order a list in the Library tab. */
enum class ListSort(val label: String, val chip: String) {
    Default("По умолчанию", "Обычная"),
    Score("По оценке", "Оценка"),
    Year("По году (сначала новые)", "Год"),
    Title("По названию (А–Я)", "А–Я"),
    Progress("По просмотренным сериям", "Серии"),
}

data class ListsState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val titles: Map<Long, ListedTitle> = emptyMap(),
    val updatedAt: Long = 0,
) {
    fun of(status: ListStatus): List<ListedTitle> = titles.values.filter { it.status == status }

    /**
     * "Смотрю" the way people use it: what they watched last on this phone on top, then titles with new
     * episodes out, then the ones still airing. The site hands the list out alphabetically, which stays as the
     * tie-break, so a long list doesn't reshuffle itself on every refresh.
     */
    fun watching(history: List<app.neifur.data.WatchEntry>): List<ListedTitle> {
        val last = HashMap<Long, Long>()
        history.forEach { e -> e.animeId.toLongOrNull()?.let { id -> if (e.updatedAt > (last[id] ?: 0)) last[id] = e.updatedAt } }
        return of(ListStatus.Watching).sortedWith(
            compareByDescending<ListedTitle> { last[it.entry.seriesId] ?: 0L }
                .thenByDescending { it.behind > 0 }
                .thenByDescending { it.airing }
        )
    }

    fun matches(t: ListedTitle, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val s = t.series
        return t.entry.title.contains(q, ignoreCase = true) ||
            s?.title?.contains(q, ignoreCase = true) == true ||
            s?.titles?.romaji?.contains(q, ignoreCase = true) == true ||
            s?.titles?.en?.contains(q, ignoreCase = true) == true
    }

    /** Titles of [status], filtered by [query] and ordered according to [sort]. */
    fun display(
        status: ListStatus,
        history: List<app.neifur.data.WatchEntry>,
        query: String = "",
        sort: ListSort = ListSort.Default,
    ): List<ListedTitle> {
        val base = if (status == ListStatus.Watching) watching(history) else of(status)
        val filtered = if (query.isBlank()) base else base.filter { matches(it, query) }
        return when (sort) {
            ListSort.Default -> filtered
            ListSort.Score -> filtered.sortedWith(
                compareByDescending<ListedTitle> { it.entry.score ?: 0 }
                    .thenByDescending { it.series?.score ?: 0.0 }
            )
            ListSort.Year -> filtered.sortedByDescending { it.series?.year ?: 0 }
            ListSort.Title -> filtered.sortedBy { (it.series?.title ?: it.entry.title).lowercase() }
            ListSort.Progress -> filtered.sortedWith(
                compareByDescending<ListedTitle> { it.entry.watched }
                    .thenByDescending { it.behind }
            )
        }
    }

    /** Other tabs that contain matches for [query], so an empty tab can offer a one-tap jump. */
    fun matchesInOtherTabs(current: ListStatus, query: String): List<Pair<ListStatus, Int>> {
        if (query.isBlank()) return emptyList()
        return ListStatus.entries.filter { it != current }.mapNotNull { s ->
            val n = of(s).count { matches(it, query) }
            if (n > 0) s to n else null
        }
    }

    fun count(status: ListStatus): Int = titles.values.count { it.status == status }
}

/**
 * The viewer's Anime 365 lists, shared by every screen: the list tab, the title page's bookmark, the player.
 * Changes show everywhere at once (applied locally first) and are sent to the site; a failed send puts the old
 * entry back.
 */
class Lists(private val api: Anime365, private val account: Account, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ListsState())
    val state: StateFlow<ListsState> = _state
    private var job: Job? = null
    private val gate = Semaphore(3)

    init {
        scope.launch {
            account.state.collect { s ->
                when (s) {
                    is AccountState.SignedIn -> refresh()
                    AccountState.SignedOut -> { job?.cancel(); _state.value = ListsState() }
                    AccountState.Unknown -> {}
                }
            }
        }
    }

    /** Reloads every list in one request, then the cards and, for airing titles, how many episodes are out. */
    fun refresh() {
        val user = account.profile ?: return
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            runCatching {
                val entries = api.allLists(user.id)
                val cards = runCatching { api.cards(entries.map { it.seriesId }) }.getOrDefault(emptyList()).associateBy { it.id }
                val known = _state.value.titles
                entries.associate { e -> e.seriesId to ListedTitle(e, cards[e.seriesId], known[e.seriesId]?.aired) }
            }.onSuccess { titles ->
                _state.value = ListsState(loaded = true, titles = titles, updatedAt = System.currentTimeMillis())
                titles.values.filter { it.series == null }.forEach { fill(it.entry.seriesId) }
                countAired(titles.values.filter { it.airing && (it.status == ListStatus.Watching || it.status == ListStatus.Planned) })
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    /** Refreshes when the lists are older than [maxAgeMs] (opening the tab doesn't hit the site every time). */
    fun refreshIfStale(maxAgeMs: Long = 120_000) {
        val s = _state.value
        if (!s.loading && System.currentTimeMillis() - s.updatedAt > maxAgeMs) refresh()
    }

    private suspend fun countAired(list: List<ListedTitle>) = coroutineScope {
        list.map { t ->
            async {
                gate.withPermit {
                    runCatching { api.series(t.entry.seriesId).playable.count { it.episodeType != "preview" } }.getOrNull()
                }?.let { n -> _state.update { s -> s.copy(titles = s.titles + (t.entry.seriesId to (s.titles[t.entry.seriesId] ?: t).copy(aired = n))) } }
            }
        }.awaitAll()
    }

    fun get(seriesId: Long): ListedTitle? = _state.value.titles[seriesId]

    /**
     * Puts a title in a list (or out of all of them with [status] null), with [watched] episodes and [score].
     * [series] fills the card when the title wasn't listed before.
     */
    suspend fun set(seriesId: Long, status: ListStatus?, watched: Int, score: Int?, series: Series? = null, title: String = ""): Result<Unit> {
        val before = _state.value.titles[seriesId]
        _state.update { s ->
            val titles = if (status == null) s.titles - seriesId else {
                val prev = s.titles[seriesId]
                val entry = (prev?.entry ?: ListEntry(seriesId, series?.title ?: title, 0, series?.numberOfEpisodes?.takeIf { it > 0 }, null))
                    .copy(status = status, watched = watched, score = score)
                s.titles + (seriesId to ListedTitle(entry, prev?.series ?: series, prev?.aired))
            }
            s.copy(titles = titles)
        }
        if (status != null && (before?.series == null || (series?.airing == true && before.aired == null))) fill(seriesId)
        return runCatching { api.setListRecord(seriesId, status, watched, score) }.onFailure {
            _state.update { s -> s.copy(titles = if (before == null) s.titles - seriesId else s.titles + (seriesId to before)) }
        }
    }

    /**
     * Loads the card (poster, title, episode count) and, for airing titles, how many episodes are out, for a title
     * that got into the lists without them: added from the title page, or by the site itself when the player marked
     * an episode. Without it the row would show up blank.
     */
    private fun fill(seriesId: Long) = scope.launch {
        val full = runCatching { api.series(seriesId) }.getOrNull() ?: return@launch
        val aired = if (full.airing) full.playable.count { it.episodeType != "preview" } else null
        _state.update { s ->
            val t = s.titles[seriesId] ?: return@update s
            val entry = if (t.entry.title.isBlank()) t.entry.copy(title = full.title) else t.entry
            s.copy(titles = s.titles + (seriesId to t.copy(entry = entry, series = full, aired = aired ?: t.aired)))
        }
    }

    /** One more episode watched; reaching the last one of a finished title moves it to "Просмотрено". */
    suspend fun bump(seriesId: Long): Result<Unit> {
        val t = get(seriesId) ?: return Result.failure(IllegalStateException("not listed"))
        val next = t.entry.watched + 1
        val total = t.total
        val done = total != null && !t.airing && next >= total
        return set(seriesId, if (done) ListStatus.Completed else t.status ?: ListStatus.Watching, if (total != null) minOf(next, total) else next, t.entry.score)
    }

    /** After the site marked an episode (the player does that itself), read the title's record back. */
    fun reload(seriesId: Long, series: Series? = null) = scope.launch {
        val r = runCatching { api.listRecord(seriesId) }.getOrNull() ?: return@launch
        _state.update { s ->
            val prev = s.titles[seriesId]
            val titles = if (r.status == null) s.titles - seriesId else {
                val entry = (prev?.entry ?: ListEntry(seriesId, series?.title.orEmpty(), 0, series?.numberOfEpisodes?.takeIf { it > 0 }, null))
                    .copy(status = r.status, watched = r.watched, score = r.score)
                s.titles + (seriesId to ListedTitle(entry, prev?.series ?: series, prev?.aired))
            }
            s.copy(titles = titles)
        }
        val now = _state.value.titles[seriesId]
        if (now != null && (now.series == null || (now.airing && now.aired == null))) fill(seriesId)
    }

    /** The title's record: from the loaded lists, or asked from the site when they aren't loaded yet. */
    suspend fun record(seriesId: Long): ListRecord? {
        val s = _state.value
        if (s.loaded) return s.titles[seriesId]?.let { ListRecord(it.status, it.entry.watched, it.entry.score) } ?: ListRecord(null, 0, null)
        return runCatching { api.listRecord(seriesId) }.getOrNull()
    }
}

/** How far along a title is, in words that suit its list. */
object ListProgress {
    fun line(t: ListedTitle): String {
        val w = t.entry.watched
        val total = t.total
        val aired = t.aired
        val isMovie = t.series?.isMovie == true || total == 1
        return when (t.status) {
            ListStatus.Planned -> when {
                isMovie -> "Фильм"
                t.airing && aired != null -> "Вышло $aired из ${total ?: "?"}"
                t.airing -> "Выходит" + (total?.let { " · $it ${plural(it)}" } ?: "")
                total != null -> "$total ${plural(total)}"
                else -> ""
            }
            ListStatus.Completed -> when {
                isMovie -> "Фильм"
                total != null -> "$total ${plural(total)}"
                else -> "$w ${plural(w)}"
            }
            ListStatus.OnHold, ListStatus.Dropped -> if (isMovie) "Не досмотрен" else "Остановились на $w из ${total ?: "?"}"
            else -> when {
                isMovie -> if (w > 0) "Просмотрен" else "Фильм"
                t.airing && aired != null -> "Просмотрено $w из $aired" + if (total != null && total != aired) " · всего $total" else " · выходит"
                else -> "Просмотрено $w из ${total ?: "?"}"
            }
        }
    }

    /** Fill of the progress bar, or null where a bar means nothing (planned, completed, unknown totals). */
    fun fraction(t: ListedTitle): Float? {
        if (t.status == ListStatus.Planned || t.status == ListStatus.Completed) return null
        val of = (if (t.airing) t.aired else null) ?: t.total ?: return null
        return if (of <= 0) null else (t.entry.watched / of.toFloat()).coerceIn(0f, 1f)
    }

    fun plural(n: Int): String {
        val m10 = n % 10; val m100 = n % 100
        return when { m10 == 1 && m100 != 11 -> "серия"; m10 in 2..4 && m100 !in 12..14 -> "серии"; else -> "серий" }
    }
}
