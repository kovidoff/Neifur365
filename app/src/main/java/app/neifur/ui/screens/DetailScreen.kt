@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.neifur.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.StarHalf
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import app.neifur.AnimeStub
import app.neifur.Container
import app.neifur.OpenAnime
import app.neifur.PlaySession
import app.neifur.core.userMessage
import app.neifur.container
import app.neifur.core.ApiError
import app.neifur.data.WatchEntry
import app.neifur.data.a365.AccountState
import app.neifur.data.a365.Episode
import app.neifur.data.a365.ListRecord
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.Studio
import app.neifur.data.a365.Studios
import app.neifur.data.a365.Series
import app.neifur.data.a365.Translation
import app.neifur.data.a365.TranslationKind
import app.neifur.data.shikimori.Anime
import app.neifur.data.shikimori.Genre
import app.neifur.data.shikimori.IncDate
import app.neifur.data.shikimori.Poster
import androidx.compose.material.icons.automirrored.rounded.Login
import kotlinx.coroutines.Job
import app.neifur.data.shikimori.AnimeCast
import app.neifur.data.shikimori.CharacterRole
import app.neifur.data.shikimori.Video
import app.neifur.data.shikimori.cleanDescription
import app.neifur.data.shikimori.kindLabel
import app.neifur.data.shikimori.statusLabel
import app.neifur.nfViewModel
import app.neifur.player.PlayerActivity
import app.neifur.stub
import app.neifur.ui.*
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Episodes of the title and the studio that plays them. [studios] (grouped by kind) is null while they load;
 * [scope] is the episodes they were counted over: all of them, or the loaded ranges of a very long show.
 */
sealed interface PlayState {
    data object Loading : PlayState
    data class Error(val message: String) : PlayState
    data class Ready(
        val episodes: List<Episode>,
        val target: Int,
        /** Episodes marked watched in the viewer's Anime 365 list. */
        val watchedOnSite: Int,
        val studios: Map<TranslationKind, List<Studio>>?,
        val studio: Studio?,
        val scope: List<Episode> = episodes,
        val trError: String? = null,
        /**
         * The viewer has a studio for this title (picked, or watched it before). Until then nothing is chosen for
         * them: the first play asks what to watch in.
         */
        val started: Boolean = false,
        /** Number of studios per kind, for the "what's here" line of a title not started yet. */
        val kinds: Map<TranslationKind, Int> = emptyMap(),
    ) : PlayState {
        val all: List<Studio> get() = studios?.values?.flatten().orEmpty()

        /** What plays an episode the chosen studio doesn't have: the closest other studio, same kind first. */
        fun fallbackFor(ep: Episode): Studio? {
            val have = all.filter { it.has(ep.id) }
            return have.filter { it.kind == studio?.kind }.maxByOrNull { it.priority } ?: have.maxByOrNull { it.priority }
        }
    }
}

/** Title page data when Shikimori has nothing (or isn't reachable): built from Anime 365 alone. */
fun Series.asAnime() = Anime(
    id = myAnimeListId.toString(), name = titles.romaji ?: title, russian = title, english = titles.en, japanese = titles.ja,
    kind = type, score = score, status = if (airing) "ongoing" else "released",
    episodes = numberOfEpisodes, episodesAired = playable.size, airedOn = IncDate(year.takeIf { it > 0 }),
    poster = Poster(originalUrl = posterUrl, mainUrl = posterUrlSmall),
    genres = genres.orEmpty().map { Genre(it.id.toString(), russian = it.title, kind = "genre") },
    description = description,
)

/**
 * [requestedId] is an Anime 365 id, or "mal:<id>" for titles reached from Shikimori data (related seasons),
 * which is resolved to the Anime 365 one first.
 */
private const val LONG_SHOW = 120
private const val RANGE = 50

class DetailVm(private val c: Container, private val requestedId: String) : ViewModel() {
    val stub: AnimeStub? = c.stubs[requestedId]
    var seriesId by mutableStateOf(requestedId.takeIf { !it.startsWith("mal:") })
        private set
    var series by mutableStateOf<Series?>(null)
        private set
    var anime by mutableStateOf<Anime?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var play by mutableStateOf<PlayState>(PlayState.Loading)
        private set
    var cast by mutableStateOf<AnimeCast?>(null)
        private set
    /** The viewer's Anime 365 list record for this title (signed in only). */
    var record by mutableStateOf<ListRecord?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
    var askSignIn by mutableStateOf(false)

    private var pickKey: String? = null
    private var trJob: Job? = null

    val signedIn: Boolean get() = c.account.state.value is AccountState.SignedIn
    val account = c.account.state

    val isFavorite = c.prefs.favorites.map { l -> l.any { it.id == seriesId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        load()
        viewModelScope.launch { c.account.state.drop(1).collect { if (it is AccountState.SignedIn) loadRecord() else record = null } }
        // The bookmark follows the shared lists: a change made anywhere (here, the list tab, the player) shows here.
        viewModelScope.launch {
            c.lists.state.collect { s ->
                val sid = seriesId?.toLongOrNull() ?: return@collect
                if (!s.loaded) return@collect
                val t = s.titles[sid]
                val r = ListRecord(t?.status, t?.entry?.watched ?: 0, t?.entry?.score)
                if (r != record) {
                    record = r
                    (play as? PlayState.Ready)?.let { p ->
                        play = p.copy(watchedOnSite = r.watched)
                        // The counter moved: start from the right episode, and a title back at zero with no studio
                        // of its own is new again.
                        retarget()
                        if (p.studios != null) applyStudios(keepChoice = true)
                    }
                }
            }
        }
        // Back from the player: the next episode becomes the target.
        viewModelScope.launch { c.prefs.history.drop(1).collect { retarget() } }
    }

    /**
     * Whether this title is under way, known from the phone alone (a saved studio, or history) before anything
     * loads, so the page reserves the right block from the first frame. Null until checked.
     */
    var startedHint by mutableStateOf<Boolean?>(null)
        private set

    /** Shikimori's details (duration, age rating, next episode) are still on their way. */
    var extrasPending by mutableStateOf(true)
        private set

    private suspend fun localStarted(id: String): Boolean =
        c.prefs.pick(id) != null || c.prefs.history.first().any { it.animeId == id }

    fun load() {
        error = null
        viewModelScope.launch {
            seriesId?.let { startedHint = localStarted(it) }
            runCatching {
                val sid = seriesId?.toLongOrNull()
                    ?: c.a365.byMyAnimeList(requestedId.removePrefix("mal:").toLong())?.id
                    ?: throw ApiError("Этого тайтла нет на Anime 365")
                seriesId = sid.toString()
                if (startedHint == null) startedHint = localStarted(sid.toString())
                c.a365.series(sid)
            }.onSuccess { s ->
                series = s
                if (anime == null) anime = s.asAnime()
                if (s.myAnimeListId <= 0) extrasPending = false
                if (s.myAnimeListId > 0) {
                    launch {
                        runCatching { c.shikimori.detail(s.myAnimeListId.toString()) }.onSuccess { anime = it.copy(russian = s.title) }
                        extrasPending = false
                    }
                    launch { cast = runCatching { c.shikimori.cast(s.myAnimeListId.toString()) }.getOrNull() }
                }
                pickKey = c.prefs.pick(s.id.toString())
                loadRecord()
                setupEpisodes(s)
            }.onFailure { error = it.userMessage(); play = PlayState.Error(it.userMessage()) }
        }
    }

    private suspend fun setupEpisodes(s: Series) {
        val eps = s.playable
        play = PlayState.Ready(
            eps, targetIndex(eps), record?.watched ?: 0, if (eps.isEmpty()) emptyMap() else null, null,
            started = startedHint == true || (record?.watched ?: 0) > 0,
        )
        if (eps.isNotEmpty()) loadTranslations()
    }

    /**
     * Where to resume: the unfinished episode or the one after the last finished, unless the site's counter is
     * further along (episodes marked by hand in the list, or watched elsewhere): then the one after it.
     */
    private suspend fun targetIndex(eps: List<Episode>): Int {
        if (eps.isEmpty()) return -1
        val h = c.prefs.history.first().filter { it.animeId == seriesId }
        val last = h.maxByOrNull { it.updatedAt }
        val site = record?.watched ?: 0
        val local = when {
            last == null -> -1
            !last.finished -> eps.indexOfFirst { it.number == last.episode }
            else -> eps.indexOfFirst { it.number > last.episode }.takeIf { it >= 0 } ?: eps.lastIndex
        }
        val fromSite = if (site > 0) eps.indexOfFirst { it.number > site }.takeIf { it >= 0 } ?: eps.lastIndex else -1
        return maxOf(local, fromSite, 0)
    }

    private suspend fun retarget() {
        val r = play as? PlayState.Ready ?: return
        val t = targetIndex(r.episodes)
        if (t == r.target) return
        play = r.copy(target = t)
        // A long show may have moved into a range that isn't counted yet.
        if (r.episodes.size > LONG_SHOW) ensureRange(rangeOf(r.episodes, t))
    }

    /** Studios of the whole title (or of the loaded ranges of a long one): they fill the picker and the card. */
    fun loadTranslations() {
        val r = play as? PlayState.Ready ?: return
        if (r.episodes.isEmpty()) return
        trJob?.cancel()
        play = r.copy(studios = null, trError = null)
        trJob = viewModelScope.launch {
            runCatching {
                // A long show (One Piece) is counted around the episode you're at; other ranges load on demand.
                val wanted = if (r.episodes.size <= LONG_SHOW) r.episodes else rangeOf(r.episodes, r.target)
                fetch(wanted)
            }.onSuccess { applyStudios(keepChoice = false) }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    play = (play as PlayState.Ready).copy(studios = emptyMap(), trError = e.userMessage())
                }
        }
    }

    /** Brings in the translations of another range of a long show (the sheet asks when you open that range). */
    fun ensureRange(range: List<Episode>) {
        if (range.all { it.id in loaded }) return
        viewModelScope.launch {
            runCatching { fetch(range) }.onSuccess { applyStudios(keepChoice = true) }
        }
    }

    private val loaded = HashSet<Long>()
    private val translations = ArrayList<Translation>()

    private suspend fun fetch(eps: List<Episode>) {
        val missing = eps.map { it.id }.filterNot { it in loaded }
        if (missing.isEmpty()) return
        val got = c.a365.translations(missing)
        synchronized(translations) { translations += got; loaded += missing }
    }

    private suspend fun applyStudios(keepChoice: Boolean) {
        val r = play as? PlayState.Ready ?: return
        val scope = r.episodes.filter { it.id in loaded }
        val studios = Studios.build(synchronized(translations) { translations.toList() })
        val kind = runCatching { TranslationKind.valueOf(c.prefs.preferredKind.first()) }.getOrDefault(TranslationKind.VoiceRu)
        val keep = r.studio?.key?.takeIf { keepChoice }
        val started = pickKey != null || r.watchedOnSite > 0 ||
            c.prefs.history.first().any { it.animeId == seriesId }
        // A title the viewer hasn't started gets no studio yet: they choose one when they press play.
        val chosen = if (!started) null else studios.firstOrNull { it.key == keep }
            ?: Studios.choose(studios, pickKey, kind, c.prefs.favouriteAuthors(), scope, scope.indexOfFirst { it.id == r.episodes.getOrNull(r.target)?.id })
        val grouped = Studios.grouped(studios, scope)
        play = r.copy(
            studios = grouped, studio = chosen, scope = scope, trError = null, started = started,
            kinds = grouped.mapValues { it.value.size },
        )
    }

    private fun rangeOf(eps: List<Episode>, index: Int): List<Episode> {
        val start = (index.coerceAtLeast(0) / RANGE) * RANGE
        return eps.subList(start, minOf(eps.size, start + RANGE))
    }

    fun select(s: Studio) {
        pickKey = s.key
        val r = play as? PlayState.Ready ?: return
        play = r.copy(studio = s, started = true)
        viewModelScope.launch { c.prefs.setPick(seriesId ?: return@launch, s.key, s.author) }
    }

    private suspend fun loadRecord() {
        val sid = seriesId?.toLongOrNull() ?: return
        if (!signedIn) return
        record = c.lists.record(sid)
        (play as? PlayState.Ready)?.let { r ->
            play = r.copy(watchedOnSite = record?.watched ?: 0)
            retarget()
            if (r.studios != null && !r.started && (record?.watched ?: 0) > 0) applyStudios(keepChoice = true)
        }
    }

    /** Changes the title's place in the viewer's Anime 365 lists ([status] null takes it out). */
    fun setList(status: ListStatus?, watched: Int = record?.watched ?: 0, score: Int? = record?.score) {
        val sid = seriesId?.toLongOrNull() ?: return
        val wasIn = record?.status != null
        viewModelScope.launch {
            c.lists.set(sid, status, watched, score, series).onSuccess {
                if (status == null) notice = "Убрано из списков"
                else if (!wasIn) notice = "Добавлено в «${status.title}»"
            }.onFailure { notice = it.userMessage() }
        }
    }

    fun toggleFavorite() {
        val s = series?.stub() ?: stub ?: return
        viewModelScope.launch { c.prefs.toggleFavorite(s.saved()) }
    }

    /** The kind a new title's picker opens on: the viewer's setting. */
    val preferredKind = c.prefs.preferredKind

    /** First play of a title: the studio chosen in the picker becomes the title's, and episode [index] starts. */
    fun start(context: Context, s: Studio, index: Int) {
        select(s)
        play(context, index)
    }

    fun play(context: Context, index: Int) {
        val r = play as? PlayState.Ready ?: return
        if (!signedIn) { askSignIn = true; return }
        val s = series?.stub() ?: stub ?: return
        c.session = PlaySession(s, r.episodes, index, pickKey ?: r.studio?.key, r.studio?.author)
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }
}

/** Pulls a readable accent out of the artwork: vivid enough to tint, light enough for dark surfaces. */
@Composable
fun rememberPosterAccent(url: String?): Color {
    val ctx = LocalContext.current
    // Cached per poster for the whole process: a rotation (which recreates the screen) must not replay the
    // fade from the default violet while the colour is extracted again.
    val cached = url?.let { AccentCache[it] }
    var picked by remember(url) { mutableStateOf(cached) }
    LaunchedEffect(url) {
        if (url == null || picked != null) return@LaunchedEffect
        picked = withContext(Dispatchers.Default) {
            runCatching {
                val res = SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(url).size(112).allowHardware(false).build())
                val p = Palette.from(res.image!!.toBitmap()).generate()
                val sw = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch ?: return@runCatching null
                val hsl = FloatArray(3).also { ColorUtils.colorToHSL(sw.rgb, it) }
                hsl[1] = hsl[1].coerceIn(0.5f, 0.92f); hsl[2] = hsl[2].coerceIn(0.66f, 0.76f)
                Color(ColorUtils.HSLToColor(hsl))
            }.getOrNull()
        }?.also { AccentCache[url] = it }
    }
    // Already known → start on it; only a freshly extracted colour animates in.
    val anim = remember(url) { Animatable(cached ?: Nf.Violet) }
    LaunchedEffect(picked) { picked?.let { if (anim.value != it) anim.animateTo(it, tween(700)) } }
    return anim.value
}

private val AccentCache = java.util.concurrent.ConcurrentHashMap<String, Color>()

// ===================================================================================================
// Title page
// ===================================================================================================

private val PosterW = 124.dp
private val PosterH = 178.dp
private val HeroShape = RoundedCornerShape(20.dp)

/**
 * Content that follows the flying poster: it waits until the poster has mostly landed, then rises into place.
 * Without this the title and buttons sat in their final spots from frame one and the poster slid over them.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun Modifier.reveal(order: Int): Modifier {
    val scope = LocalNavAnimScope.current ?: return this
    val rise = with(LocalDensity.current) { 22.dp.roundToPx() }
    val delayMs = 150 + order * 45
    return with(scope) {
        this@reveal.animateEnterExit(
            enter = fadeIn(tween(340, delayMs, LinearOutSlowInEasing)) +
                slideInVertically(tween(480, delayMs, FastOutSlowInEasing)) { rise },
            exit = fadeOut(tween(120)),
        )
    }
}

@Composable
fun DetailScreen(id: String, origin: String, open: OpenAnime, onBack: () -> Unit) {
    val vm = nfViewModel(key = "detail-$id") { DetailVm(it, id) }
    val ctx = LocalContext.current
    val anime = vm.anime
    val stub = vm.series?.stub()?.copy(poster = vm.stub?.poster ?: vm.series?.posterUrl) ?: vm.stub
    val favorite by vm.isFavorite.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val signedIn = account is AccountState.SignedIn
    val allHistory by LocalContext.current.container.prefs.history.collectAsStateWithLifecycle(emptyList())
    val history = remember(allHistory, vm.seriesId) { allHistory.filter { it.animeId == vm.seriesId } }
    val accent = rememberPosterAccent(stub?.poster)
    var sheet by remember { mutableStateOf<SheetPage?>(null) }
    /** Episode the "В чём смотреть?" picker starts, when a title not started yet is played. */
    var startFor by remember { mutableStateOf<Int?>(null) }
    val preferred by vm.preferredKind.collectAsStateWithLifecycle("VoiceRu")
    var listSheet by remember { mutableStateOf(false) }
    val play = vm.play
    val listState = rememberLazyListState()
    val isMovie = vm.series?.isMovie == true || (play as? PlayState.Ready)?.episodes?.size == 1

    LaunchedEffect(vm.notice) { if (vm.notice != null) { delay(3800); vm.notice = null } }

    CompositionLocalProvider(LocalAccent provides accent) {
        Box(Modifier.fillMaxSize().background(Nf.Ink)) {
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 56.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "hero") { Hero(stub, anime, origin, accent, listState) }

                item(key = "meta") { MetaChips(anime, vm.extrasPending, Modifier.reveal(1)) }

                item(key = "actions") {
                    Column(Modifier.reveal(2).padding(horizontal = 20.dp).padding(top = 18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WatchButton(play, history, anime, isMovie, accent, signedIn, Modifier.weight(1f), { vm.askSignIn = true }) { idx ->
                                if ((play as? PlayState.Ready)?.started == false) { startFor = idx; sheet = SheetPage.Translations } else vm.play(ctx, idx)
                            }
                            if (!isMovie) {
                                Spacer(Modifier.width(10.dp))
                                SquareAction(Icons.AutoMirrored.Rounded.FormatListBulleted, "Серии") { sheet = SheetPage.Episodes }
                            }
                            Spacer(Modifier.width(10.dp))
                            // Signed in, the bookmark is the title's place in the Anime 365 lists; otherwise a local favourite.
                            if (signedIn) {
                                val inList = vm.record?.status != null
                                SquareAction(if (inList) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Список", tint = if (inList) statusColor(vm.record?.status) else Nf.Text) { listSheet = true }
                            } else {
                                SquareAction(if (favorite) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Избранное", tint = if (favorite) accent else Nf.Text, onClick = vm::toggleFavorite)
                            }
                        }
                        StudioCard(play, vm.startedHint, vm.record?.status, accent) { startFor = null; sheet = SheetPage.Translations }
                    }
                }

                if (vm.error != null && anime == null) item {
                    EmptyState("Не получилось загрузить тайтл", vm.error, modifier = Modifier.padding(top = 20.dp), action = "Повторить", onAction = vm::load)
                }

                if (anime == null && vm.error == null) item(key = "skeleton") { SectionsSkeleton(Modifier.reveal(3)) }

                if (anime != null) {
                    val desc = cleanDescription(anime.description)
                    if (desc.isNotBlank()) item(key = "desc") { Description(desc, Modifier.animateItem().reveal(3)) }

                    val genres = anime.genres.filter { it.kind != "theme" || anime.genres.size < 6 }
                    if (genres.isNotEmpty()) item(key = "genres") {
                        FlowRow(
                            Modifier.animateItem().reveal(4).padding(horizontal = 20.dp).padding(top = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) { genres.forEach { Pill(it.label) } }
                    }

                    item(key = "facts") { Facts(anime, vm.cast, Modifier.animateItem()) }

                    if (anime.scoresStats.sumOf { it.count } > 20) item(key = "scores") { Scores(anime, accent, Modifier.animateItem()) }

                    val chars = vm.cast?.characters.orEmpty()
                    if (chars.isNotEmpty()) item(key = "chars") { Characters(chars, Modifier.animateItem()) }

                    val videos = anime.videos.filter { it.url != null && it.kind != "episode_preview" }
                        .sortedBy { listOf("pv", "op", "ed", "op_ed_clip", "character_trailer").indexOf(it.kind).let { i -> if (i < 0) 9 else i } }
                    if (videos.isNotEmpty()) item(key = "videos") { Videos(videos, Modifier.animateItem()) { url -> openUrl(ctx, url) } }

                    if (anime.screenshots.isNotEmpty()) item(key = "shots") {
                        Column(Modifier.animateItem()) {
                            SectionTitle("Кадры", Modifier.padding(top = 30.dp, bottom = 14.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(anime.screenshots.take(14)) { s ->
                                    PosterImage(s.x332Url ?: s.originalUrl, Modifier.height(124.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)))
                                }
                            }
                        }
                    }

                    // Related seasons come from Shikimori; opening one finds its Anime 365 page by the shared MyAnimeList id.
                    val related = anime.related.filter { it.anime != null && it.anime.kind != "music" }
                        .sortedWith(compareBy({ relationOrder(it.relationKind) }, { it.anime?.airedOn?.year ?: 9999 }))
                    if (related.isNotEmpty()) item(key = "related") {
                        Column(Modifier.animateItem()) {
                            SectionTitle("Связанное", Modifier.padding(top = 30.dp, bottom = 14.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(related, key = { it.anime!!.id }) { r ->
                                    val ra = r.anime!!
                                    val s = AnimeStub("mal:${ra.id}", ra.russian?.takeIf { it.isNotBlank() } ?: ra.name, ra.poster?.card, ra.kind, ra.airedOn?.year, ra.score)
                                    Column(Modifier.width(118.dp)) {
                                        PosterCard(s, "rel-$id", { open(s, "rel-$id") }, width = 118.dp)
                                        r.relationText?.let { Text(it, style = Type.Caption, color = accent, maxLines = 1, modifier = Modifier.padding(top = 3.dp)) }
                                    }
                                }
                            }
                        }
                    }

                    item(key = "credit") {
                        Text(
                            "Каталог и видео — Anime 365" + if (vm.series?.myAnimeListId?.let { it > 0 } == true) " · Описание и персонажи — Shikimori" else "",
                            style = Type.Caption, color = Nf.TextMute, modifier = Modifier.animateItem().padding(horizontal = 20.dp).padding(top = 30.dp),
                        )
                    }
                }
            }

            CollapsingBar(stub?.title.orEmpty(), listState, onBack)

            AnimatedVisibility(
                vm.notice != null, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            ) {
                Text(
                    vm.notice.orEmpty(), style = Type.Label, color = Nf.Text,
                    modifier = Modifier.shadow(20.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                        .background(Nf.SurfaceHi).border(1.dp, Nf.Line, RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }

        sheet?.let { page ->
            EpisodesSheet(
                state = play, history = history, isMovie = isMovie, accent = accent, startPage = page,
                onDismiss = { sheet = null; startFor = null }, onPlay = { idx -> vm.play(ctx, idx) },
                onPick = vm::select, onRetry = vm::load, onRange = vm::ensureRange,
                startFor = startFor,
                preferred = runCatching { TranslationKind.valueOf(preferred) }.getOrDefault(TranslationKind.VoiceRu),
                onStart = { s, i -> sheet = null; startFor = null; vm.start(ctx, s, i) },
            )
        }
        if (listSheet) ListEditor(
            title = stub?.title.orEmpty(), record = vm.record, total = vm.series?.numberOfEpisodes?.takeIf { it > 0 },
            aired = (play as? PlayState.Ready)?.episodes?.size, isMovie = isMovie,
            onDismiss = { listSheet = false },
            onChange = { st, w, sc -> vm.setList(st, w, sc) },
        )
        if (vm.askSignIn) SignInSheet(onDismiss = { vm.askSignIn = false }, onDone = { vm.askSignIn = false })
    }
}

private fun relationOrder(kind: String?) = when (kind) {
    "prequel" -> 0; "sequel" -> 1; "parent_story" -> 2; "side_story" -> 3; "spin_off" -> 4; "alternative_version" -> 5
    "summary" -> 6; "full_story" -> 7; else -> 8
}

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// --- hero ------------------------------------------------------------------------------------------

@Composable
private fun Hero(stub: AnimeStub?, anime: Anime?, origin: String, accent: Color, listState: LazyListState) {
    val ctx = LocalContext.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val backdropH = 250.dp + top
    val shot = anime?.screenshots?.firstOrNull()?.originalUrl
    Box(Modifier.fillMaxWidth()) {
        // Backdrop: the blurred poster at once (it's already in memory), a real frame from the show fading in over it.
        Box(
            Modifier.fillMaxWidth().height(backdropH).reveal(0).clipToBounds().graphicsLayer {
                val first = listState.firstVisibleItemIndex == 0
                translationY = if (first) listState.firstVisibleItemScrollOffset * 0.45f else 0f
            }
        ) {
            PosterImage(stub?.poster, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }.blur(40.dp).alpha(0.7f))
            if (shot != null) {
                var loaded by remember(shot) { mutableStateOf(false) }
                val a by animateFloatAsState(if (loaded) 1f else 0f, tween(600), label = "shot")
                AsyncImage(
                    model = remember(shot) { ImageRequest.Builder(ctx).data(shot).build() },
                    contentDescription = null, contentScale = ContentScale.Crop,
                    onSuccess = { loaded = true },
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = a },
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Nf.Ink.copy(alpha = 0.55f), 0.3f to Nf.Ink.copy(alpha = 0.1f),
                        0.62f to Nf.Ink.copy(alpha = 0.55f), 1f to Nf.Ink,
                    )
                )
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = backdropH - 104.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            PosterImage(
                stub?.poster,
                Modifier.sharedPoster("poster/$origin/${stub?.id}", HeroShape).size(PosterW, PosterH)
                    .shadow(28.dp, HeroShape, ambientColor = accent, spotColor = accent).clip(HeroShape)
                    .border(1.dp, Color(0x1FFFFFFF), HeroShape),
            )
            Spacer(Modifier.width(16.dp))
            // Late data (status, original name) grows the block smoothly instead of jolting the title upwards.
            Column(Modifier.weight(1f).reveal(0).animateContentSize(spring(dampingRatio = 0.9f, stiffness = 380f)).padding(bottom = 2.dp)) {
                anime?.status?.takeIf { it == "ongoing" || it == "anons" }?.let {
                    Text(
                        statusLabel(it).uppercase(), style = Type.Overline, color = if (it == "ongoing") Nf.Mint else Nf.Amber,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Text(
                    stub?.title.orEmpty(), style = Type.Display.copy(fontSize = 20.sp, lineHeight = 25.sp), color = Nf.Text,
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
                anime?.name?.takeIf { it != stub?.title }?.let {
                    Text(it, style = Type.Caption, color = Nf.TextMute, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
                val score = formatScore(anime?.score ?: stub?.score)
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (score != null) {
                        Icon(Icons.Rounded.Star, null, tint = Nf.Amber, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(score, style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        listOfNotNull((anime?.year ?: stub?.year)?.toString(), kindLabel(anime?.kind ?: stub?.kind).takeIf { it.isNotBlank() }).joinToString(" · "),
                        style = Type.Label, color = Nf.TextDim, maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaChips(anime: Anime?, pending: Boolean, modifier: Modifier) {
    // Same height as the chip row, so the buttons below don't jump when the data arrives.
    if (anime == null) { Spacer(modifier.height(52.dp)); return }
    val chips = buildList {
        when {
            anime.status == "ongoing" -> add(Triple(Icons.Rounded.LiveTv, "${anime.episodesAired}${if (anime.episodes > 0) " из ${anime.episodes}" else ""} эп.", Nf.Mint))
            anime.episodes > 1 -> add(Triple(Icons.Rounded.LiveTv, "${anime.episodes} эп.", null))
        }
        nextEpisodeLabel(anime.nextEpisodeAt)?.let { add(Triple(Icons.Rounded.Schedule, it, null)) }
        durationLabel(anime)?.let { add(Triple(Icons.Rounded.Timer, it, null)) }
        ageLabel(anime.rating)?.let { add(Triple(null, it, null)) }
    }
    if (chips.isEmpty()) { Spacer(modifier.height(if (pending) 52.dp else 18.dp)); return }
    LazyRow(
        modifier.padding(top = 18.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips) { (icon, text, tint) ->
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(Nf.Surface).border(1.dp, Nf.Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(icon, null, tint = tint ?: Nf.TextDim, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(text, style = Type.Label, color = tint ?: Nf.Text, maxLines = 1)
            }
        }
    }
}

private fun ageLabel(r: String?) = when (r) { "g" -> "0+"; "pg" -> "6+"; "pg_13" -> "13+"; "r" -> "17+"; "r_plus", "rx" -> "18+"; else -> null }

private fun durationLabel(a: Anime): String? {
    val d = a.duration?.takeIf { it > 0 } ?: return null
    return if (d >= 60) "${d / 60} ч ${d % 60} мин" else "$d мин"
}

private fun nextEpisodeLabel(iso: String?): String? {
    iso ?: return null
    return runCatching {
        val t = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
        val today = java.time.LocalDate.now()
        val day = when (t.toLocalDate()) {
            today -> "сегодня"
            today.plusDays(1) -> "завтра"
            else -> t.format(java.time.format.DateTimeFormatter.ofPattern("EE, d MMM", java.util.Locale("ru"))).trimEnd('.')
        }
        "Серия $day в ${t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
    }.getOrNull()
}

private fun originLabel(o: String?) = when (o) {
    "manga" -> "Манга"; "light_novel" -> "Ранобэ"; "web_manga" -> "Веб-манга"; "four_koma_manga" -> "Ёнкома"
    "novel" -> "Роман"; "web_novel" -> "Веб-новелла"; "visual_novel" -> "Визуальная новелла"; "game" -> "Игра"
    "card_game" -> "Карточная игра"; "music" -> "Музыка"; "original" -> "Оригинал"; "book" -> "Книга"; "picture_book" -> "Книжка с картинками"
    "radio" -> "Радио"; "mixed_media" -> "Медиамикс"; null, "" -> null; else -> o.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

// --- actions ---------------------------------------------------------------------------------------

@Composable
private fun SquareAction(icon: ImageVector, label: String, tint: Color = Nf.Text, onClick: () -> Unit) {
    Box(
        Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(Nf.Surface).border(1.dp, Nf.Line, RoundedCornerShape(18.dp))
            .pressable(0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(icon, label = "sq", transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) }) { i ->
            Icon(i, label, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun WatchButton(
    state: PlayState, history: List<WatchEntry>, anime: Anime?, isMovie: Boolean, accent: Color, signedIn: Boolean,
    modifier: Modifier, onSignIn: () -> Unit, onPlay: (Int) -> Unit,
) {
    val ready = state as? PlayState.Ready
    val eps = ready?.episodes.orEmpty()
    val targetIdx = ready?.target ?: -1
    val target = eps.getOrNull(targetIdx)
    val last = history.maxByOrNull { it.updatedAt }
    val resuming = last != null && !last.finished && target != null && target.number == last.episode
    val loading = anime == null || state is PlayState.Loading
    val label = when {
        loading -> "Загрузка…"
        state is PlayState.Error -> "Не удалось загрузить"
        target == null -> if (anime?.status == "anons") "Ещё не вышло" else "Серий пока нет"
        !signedIn -> "Войти и смотреть"
        ready?.started == false -> if (isMovie || eps.size == 1) "Смотреть" else "Начать просмотр"
        isMovie || eps.size == 1 -> if (resuming) "Продолжить" else "Смотреть"
        resuming -> "Продолжить · серия ${target.number}"
        else -> "Смотреть · серия ${target.number}"
    }
    val progress = if (resuming) last!!.progress else 0f
    val active = target != null
    val bg by animateColorAsState(if (active) accent else Nf.Surface, tween(400), label = "btn")
    Box(modifier.height(54.dp).clip(RoundedCornerShape(18.dp)).background(bg)
        .pressable(0.97f, enabled = active) { if (!signedIn) onSignIn() else onPlay(targetIdx) }
    ) {
        // A resume button shows how far into the episode you are.
        if (progress > 0.01f) Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color.White.copy(alpha = 0.18f)))
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            AnimatedContent(Triple(loading, active, signedIn), label = "wbi") { (l, act, si) ->
                when {
                    l -> CircularProgressIndicator(Modifier.size(18.dp), color = Nf.TextDim, strokeWidth = 2.dp)
                    act -> Icon(if (si) Icons.Rounded.PlayArrow else Icons.AutoMirrored.Rounded.Login, null, tint = Nf.Ink, modifier = Modifier.size(24.dp))
                    else -> Spacer(Modifier.size(0.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            AnimatedContent(label, label = "wbl", transitionSpec = { (fadeIn(tween(220)) + slideInVertically { it / 2 }) togetherWith (fadeOut(tween(120)) + slideOutVertically { -it / 2 }) }) { l ->
                Text(l, style = Type.Title, color = if (active) Nf.Ink else Nf.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * For a title not started yet: what Anime 365 has for it, by kind ("Озвучка · 12  Субтитры · 3  RAW").
 * Nothing is chosen here; tapping opens the picker to look around.
 */
@Composable
private fun Available(r: PlayState.Ready, accent: Color, onClick: () -> Unit) {
    if (r.kinds.isEmpty()) {
        Text(r.trError ?: "Переводов пока нет", style = Type.Label, color = Nf.TextMute, modifier = Modifier.padding(start = 4.dp))
        return
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            r.kinds.entries.take(4).forEach { (k, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(kindIcon(k), null, tint = accent, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (k == TranslationKind.Raw) k.short else "${k.short} · $n", style = Type.Label, color = Nf.TextDim, maxLines = 1)
                }
            }
            if (r.kinds.size > 4) Text("ещё ${r.kinds.size - 4}", style = Type.Label, color = Nf.TextMute)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Nf.TextMute, modifier = Modifier.size(18.dp))
    }
}

/**
 * The translation the title plays in, right under the buttons: studio, kind, quality, which episodes it has, and
 * a plain warning when the next episode isn't among them (with what plays instead). Tapping opens the picker.
 */
@Composable
private fun StudioCard(state: PlayState, startedHint: Boolean?, status: ListStatus?, accent: Color, onClick: () -> Unit) {
    val r = state as? PlayState.Ready
    // While loading, the placeholder has the shape of what will come: the studio card for a title under way,
    // the slim "what's here" line for a new one. Not known yet (a moment at most): an empty gap of that line's size.
    val started = r?.started ?: (startedHint == true)
    val loading = state is PlayState.Loading || (r != null && r.studios == null && r.episodes.isNotEmpty())
    Column(Modifier.padding(top = 14.dp)) {
        when {
            loading && started -> Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(20.dp)).shimmer())
            loading && startedHint != null -> Row(
                Modifier.padding(vertical = 8.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) { listOf(96.dp, 110.dp, 50.dp).forEach { w -> Box(Modifier.width(w).height(14.dp).clip(CircleShape).shimmer()) } }
            loading -> Spacer(Modifier.height(30.dp))
            r != null && r.episodes.isNotEmpty() && r.studios != null && !r.started -> Available(r, accent, onClick)
            r != null && r.episodes.isNotEmpty() -> {
                StudioSummary(r, accent, onClick = onClick)
                val target = r.episodes.getOrNull(r.target)
                val studio = r.studio
                if (target != null && studio != null && r.scope.any { it.id == target.id } && !studio.has(target.id)) {
                    val instead = r.fallbackFor(target)
                    Row(
                        Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nf.Amber.copy(alpha = 0.10f))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Info, null, tint = Nf.Amber, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${target.label} у ${studio.author} нет" + (instead?.let { " — включится ${it.author}" } ?: ""),
                            style = Type.Caption.copy(fontSize = 12.5.sp), color = Nf.TextDim,
                        )
                    }
                }
            }
        }
        if (status != null) Text(
            "В списке «${status.title}»", style = Type.Caption, color = statusColor(status),
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }
}

// --- sections --------------------------------------------------------------------------------------

@Composable
private fun SectionsSkeleton(modifier: Modifier) {
    Column(modifier.padding(horizontal = 20.dp).padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(4) { i -> Box(Modifier.fillMaxWidth(if (i == 3) 0.6f else 1f).height(12.dp).clip(CircleShape).shimmer()) }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(22.dp)).shimmer())
    }
}

@Composable
private fun Description(text: String, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val long = text.length > 240
    Column(
        modifier.padding(horizontal = 20.dp).padding(top = 22.dp).animateContentSize(spring(dampingRatio = 0.9f, stiffness = 300f))
            .clickable(remember { MutableInteractionSource() }, null, enabled = long) { expanded = !expanded }
    ) {
        Box {
            Text(text, style = Type.Body, color = Nf.TextDim, maxLines = if (expanded || !long) Int.MAX_VALUE else 5, overflow = TextOverflow.Clip)
            if (long && !expanded) Box(
                Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Nf.Ink))
            )
        }
        if (long) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "Свернуть" else "Читать полностью", style = Type.Label, color = LocalAccent.current)
            Icon(if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = LocalAccent.current, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Facts(anime: Anime, cast: AnimeCast?, modifier: Modifier) {
    fun staff(vararg roles: String) = cast?.staff.orEmpty()
        .filter { p -> p.rolesRu.any { it in roles } }.mapNotNull { it.person?.label }.distinct().take(2).joinToString(", ").ifBlank { null }
    val aired = anime.airedOn?.format()
    val released = anime.releasedOn?.format()
    val dates = when {
        aired == null -> null
        anime.status == "ongoing" -> "с $aired"
        released != null && released != aired && anime.kind != "movie" -> "$aired — $released"
        else -> aired
    }
    val rows = listOfNotNull(
        "Тип" to kindLabel(anime.kind).ifBlank { null },
        "Эпизоды" to when {
            anime.kind == "movie" -> null
            anime.status == "ongoing" -> if (anime.episodes > 0) "${anime.episodesAired} из ${anime.episodes}" else "${anime.episodesAired}, выходит"
            anime.episodes > 0 -> "${anime.episodes}"
            else -> null
        },
        "Длительность" to durationLabel(anime)?.let { if (anime.kind == "movie") it else "$it на серию" },
        "Выход" to dates,
        "Студия" to anime.studios.joinToString(", ") { it.name }.ifBlank { null },
        "Первоисточник" to originLabel(anime.origin),
        "Режиссёр" to staff("Режиссёр"),
        "Автор оригинала" to staff("Автор оригинала", "Оригинальное создание"),
        "Музыка" to staff("Композитор", "Музыка"),
        "Возраст" to ageLabel(anime.rating),
    ).filter { it.second != null }
    Column(modifier.padding(horizontal = 20.dp).padding(top = 30.dp)) {
        Text("О тайтле", style = Type.Section, color = Nf.Text, modifier = Modifier.padding(bottom = 14.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Nf.Surface).padding(vertical = 6.dp)) {
            rows.forEachIndexed { i, (k, v) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
                    Text(k, style = Type.Label, color = Nf.TextMute, modifier = Modifier.width(126.dp))
                    Text(v!!, style = Type.Label.copy(fontSize = 14.sp), color = Nf.Text, modifier = Modifier.weight(1f))
                }
                if (i != rows.lastIndex) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(Nf.Line.copy(alpha = 0.6f)))
            }
        }
    }
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale("ru"), "%.1f млн", n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000} тыс."
    n >= 1000 -> String.format(Locale("ru"), "%.1f тыс.", n / 1000.0)
    else -> n.toString()
}

@Composable
private fun Scores(anime: Anime, accent: Color, modifier: Modifier) {
    val stats = anime.scoresStats.sortedByDescending { it.score }
    val total = stats.sumOf { it.count }
    val max = stats.maxOf { it.count }.coerceAtLeast(1)
    val st = anime.statusesStats.associate { it.status to it.count }
    Column(modifier.padding(horizontal = 20.dp).padding(top = 30.dp)) {
        Text("Оценки", style = Type.Section, color = Nf.Text, modifier = Modifier.padding(bottom = 14.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Nf.Surface).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatScore(anime.score) ?: "—", style = Type.Hero.copy(fontSize = 38.sp, lineHeight = 42.sp), color = Nf.Text)
                    Row(Modifier.padding(top = 4.dp)) {
                        val full = ((anime.score ?: 0.0) / 2).toFloat()
                        repeat(5) { i ->
                            val f = (full - i).coerceIn(0f, 1f)
                            Icon(if (f >= 0.75f) Icons.Rounded.Star else if (f >= 0.25f) Icons.AutoMirrored.Rounded.StarHalf else Icons.Rounded.StarOutline, null, tint = Nf.Amber, modifier = Modifier.size(13.dp))
                        }
                    }
                    Text("${compact(total)} оценок", style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    stats.filter { it.score >= 1 }.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${s.score}", style = Type.Caption, color = Nf.TextMute, modifier = Modifier.width(18.dp))
                            Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Nf.Line)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(s.count / max.toFloat()).clip(CircleShape).background(if (s.score >= 7) accent else Nf.TextMute))
                            }
                        }
                    }
                }
            }
            val lists = listOfNotNull(
                st["watching"]?.let { "Смотрят" to it }, st["completed"]?.let { "Посмотрели" to it },
                st["planned"]?.let { "В планах" to it }, st["dropped"]?.let { "Бросили" to it },
            )
            if (lists.isNotEmpty()) {
                Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(1.dp).background(Nf.Line.copy(alpha = 0.6f)))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    lists.forEach { (k, v) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(compact(v), style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text)
                            Text(k, style = Type.Caption, color = Nf.TextMute)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Characters(chars: List<CharacterRole>, modifier: Modifier) {
    Column(modifier) {
        SectionTitle("Персонажи", Modifier.padding(top = 30.dp, bottom = 14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(chars, key = { it.character!!.id }) { r ->
                val c = r.character!!
                Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PosterImage(c.poster?.main2xUrl ?: c.poster?.mainUrl, Modifier.size(72.dp).clip(CircleShape).border(2.dp, if (r.isMain) LocalAccent.current.copy(alpha = 0.7f) else Nf.Line, CircleShape))
                    Text(c.label, style = Type.Caption.copy(fontSize = 12.sp), color = Nf.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    Text(if (r.isMain) "Главный" else "Второстеп.", style = Type.Caption.copy(fontSize = 10.5.sp), color = Nf.TextMute, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Videos(videos: List<Video>, modifier: Modifier, onOpen: (String) -> Unit) {
    Column(modifier) {
        SectionTitle("Трейлеры и музыка", Modifier.padding(top = 30.dp, bottom = 14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(videos.take(12)) { v ->
                Column(Modifier.width(212.dp).pressable(0.97f) { v.url?.let(onOpen) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Nf.Surface), contentAlignment = Alignment.Center) {
                        PosterImage(thumb(v), Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Color(0x33000000)))
                        Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xCC0E0E15)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, "Открыть", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        Text(
                            when (v.kind) { "pv" -> "Трейлер"; "op" -> "Опенинг"; "ed" -> "Эндинг"; "op_ed_clip" -> "Клип"; "character_trailer" -> "Персонаж"; else -> "Видео" },
                            style = Type.Caption.copy(fontSize = 10.5.sp), color = Color.White,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB30E0E15)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(v.name?.trim().orEmpty().ifBlank { "Видео" }, style = Type.Label, color = Nf.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

/** Shikimori gives scheme-less thumbnail URLs ("//img.youtube.com/..."); YouTube ones get a sharper variant. */
private fun thumb(v: Video): String? {
    val yt = Regex("""(?:youtu\.be/|youtube\.com/(?:watch\?v=|embed/))([\w-]{11})""").find(v.url.orEmpty())?.groupValues?.get(1)
    if (yt != null) return "https://i.ytimg.com/vi/$yt/hqdefault.jpg"
    val u = v.imageUrl ?: return null
    return when { u.startsWith("//") -> "https:$u"; u.startsWith("http://") -> "https://" + u.removePrefix("http://"); else -> u }
}

@Composable
private fun CollapsingBar(title: String, listState: LazyListState, onBack: () -> Unit) {
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 520 } }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(220), label = "bar")
    Box(
        Modifier.fillMaxWidth().graphicsLayer { alpha = barAlpha }.background(Nf.Ink.copy(alpha = 0.96f))
            .statusBarsPadding().height(58.dp).padding(start = 70.dp, end = 20.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(title, style = Type.Title, color = Nf.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    Box(
        Modifier.statusBarsPadding().padding(start = 14.dp, top = 8.dp).size(42.dp).clip(CircleShape)
            .background(Color(0x99090910)).border(1.dp, Color(0x14FFFFFF), CircleShape).pressable(0.88f, onClick = onBack),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Nf.Text, modifier = Modifier.size(22.dp)) }
}
