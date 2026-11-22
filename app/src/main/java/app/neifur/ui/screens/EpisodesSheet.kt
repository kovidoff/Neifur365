@file:OptIn(ExperimentalMaterial3Api::class)

package app.neifur.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.neifur.data.WatchEntry
import app.neifur.data.a365.Episode
import app.neifur.data.a365.Studio
import app.neifur.data.a365.Studios
import app.neifur.data.a365.TranslationKind
import app.neifur.downloads.DownloadStatus
import app.neifur.downloads.DownloadedEpisode
import app.neifur.downloads.DownloadsState
import app.neifur.downloads.formatBytes
import app.neifur.ui.EmptyState
import app.neifur.ui.FavouriteDot
import app.neifur.ui.FavouriteStar
import app.neifur.ui.FavouriteStudios
import app.neifur.ui.FavouriteTint
import app.neifur.ui.ListOverline
import app.neifur.ui.rememberFavouriteStudios
import app.neifur.ui.Nf
import app.neifur.ui.Tag
import app.neifur.ui.Type
import app.neifur.ui.pressable
import app.neifur.ui.shimmer

enum class SheetPage { Episodes, Translations }

fun kindIcon(k: TranslationKind): ImageVector = when {
    k == TranslationKind.Raw -> Icons.Rounded.MovieFilter
    k.isSubs -> Icons.Rounded.ClosedCaption
    else -> Icons.Rounded.Mic
}

/** The episodes a studio has, one mark per episode (a solid bar when there are too many to tell apart). */
@Composable
fun CoverageBar(studio: Studio, episodes: List<Episode>, accent: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val n = episodes.size
        if (n == 0) return@Canvas
        val r = CornerRadius(size.height / 2)
        if (n > 60) {
            val share = episodes.count { studio.has(it.id) } / n.toFloat()
            drawRoundRect(Nf.Line, cornerRadius = r)
            drawRoundRect(accent, size = Size(size.width * share, size.height), cornerRadius = r)
            return@Canvas
        }
        val gap = if (n > 30) 1.5.dp.toPx() else 3.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        episodes.forEachIndexed { i, e ->
            drawRoundRect(
                if (studio.has(e.id)) accent else Nf.Line,
                topLeft = Offset(i * (w + gap), 0f), size = Size(w, size.height), cornerRadius = r,
            )
        }
    }
}

/**
 * Episodes and translations of a title, in a sheet over its page. The episode list follows the chosen studio:
 * episodes it hasn't uploaded are marked, with what plays instead. The translation page picks a studio for the
 * whole title, showing how many episodes each one has.
 */
@Composable
fun EpisodesSheet(
    state: PlayState, history: List<WatchEntry>, isMovie: Boolean, accent: Color, startPage: SheetPage,
    onDismiss: () -> Unit, onPlay: (Int) -> Unit, onPick: (Studio) -> Unit, onRetry: () -> Unit,
    onRange: (List<Episode>) -> Unit,
    /** Opened by the first play of a title: the picker starts this episode in the studio chosen. */
    startFor: Int? = null, preferred: TranslationKind = TranslationKind.VoiceRu,
    onStart: (Studio, Int) -> Unit = { _, _ -> },
    downloads: DownloadsState = DownloadsState(),
    onDownload: (Episode) -> Unit = {},
    onCancelDownload: (Long) -> Unit = {},
    onRetryDownload: (Long) -> Unit = {},
    onDeleteDownload: (Long) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember { mutableStateOf(startPage) }
    // Which episode the picker is choosing for, when the title has no studio yet.
    var choosingFor by remember { mutableStateOf(startFor) }
    val openedOnPicker = startPage == SheetPage.Translations
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nf.Surface, contentColor = Nf.Text,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(40.dp, 4.dp).clip(CircleShape).background(Nf.Line)) },
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            AnimatedContent(
                page, label = "page",
                transitionSpec = {
                    val dir = if (targetState == SheetPage.Translations) 1 else -1
                    (slideInHorizontally(tween(280)) { it / 4 * dir } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(220)) { -it / 4 * dir } + fadeOut(tween(140)))
                },
            ) { p ->
                when (p) {
                    SheetPage.Episodes -> EpisodesPage(
                        state, history, isMovie, accent, downloads,
                        onPlay = { i ->
                            if ((state as? PlayState.Ready)?.started == false) { choosingFor = i; page = SheetPage.Translations } else onPlay(i)
                        },
                        onRetry = onRetry, onRange = onRange,
                        onDownload = onDownload, onCancelDownload = onCancelDownload,
                        onRetryDownload = onRetryDownload, onDeleteDownload = onDeleteDownload,
                    ) { choosingFor = null; page = SheetPage.Translations }
                    SheetPage.Translations -> TranslationsPage(
                        state as? PlayState.Ready, accent, choosingFor, preferred,
                        showBack = !(openedOnPicker && choosingFor == startFor),
                        onBack = { choosingFor = null; page = SheetPage.Episodes },
                        onPick = { s ->
                            val i = choosingFor
                            if (i != null) onStart(s, i) else { onPick(s); page = SheetPage.Episodes }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodesPage(
    state: PlayState, history: List<WatchEntry>, isMovie: Boolean, accent: Color, downloads: DownloadsState,
    onPlay: (Int) -> Unit, onRetry: () -> Unit, onRange: (List<Episode>) -> Unit,
    onDownload: (Episode) -> Unit, onCancelDownload: (Long) -> Unit, onRetryDownload: (Long) -> Unit, onDeleteDownload: (Long) -> Unit,
    onTranslations: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 22.dp).padding(top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (isMovie) "Просмотр" else "Серии", style = Type.Display, color = Nf.Text, modifier = Modifier.weight(1f))
            (state as? PlayState.Ready)?.let { Text("${it.episodes.size} ${plural(it.episodes.size, "серия", "серии", "серий")}", style = Type.Label, color = Nf.TextMute) }
        }
        when (state) {
            PlayState.Loading -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(20.dp)).shimmer())
                repeat(5) { Box(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(16.dp)).shimmer()) }
            }
            is PlayState.Error -> EmptyState("Не получилось загрузить серии", state.message, action = "Повторить", onAction = onRetry)
            is PlayState.Ready ->
                if (state.episodes.isEmpty()) EmptyState("Серий пока нет", "На Anime 365 ещё не загрузили ни одной серии этого тайтла")
                else Episodes(
                    state, history, isMovie, accent, downloads,
                    onPlay, onRange, onTranslations, onDownload, onCancelDownload, onRetryDownload, onDeleteDownload,
                )
        }
    }
}

@Composable
private fun Episodes(
    state: PlayState.Ready, history: List<WatchEntry>, isMovie: Boolean, accent: Color, downloads: DownloadsState,
    onPlay: (Int) -> Unit, onRange: (List<Episode>) -> Unit, onTranslations: () -> Unit,
    onDownload: (Episode) -> Unit, onCancelDownload: (Long) -> Unit, onRetryDownload: (Long) -> Unit, onDeleteDownload: (Long) -> Unit,
) {
    val eps = state.episodes
    // Long shows are split into ranges of 50; the sheet opens on the range you're watching.
    val chunk = 50
    val ranges = if (eps.size > 60) eps.indices.chunked(chunk) else listOf(eps.indices.toList())
    val resume = state.target
    var range by remember { mutableIntStateOf(if (ranges.size > 1 && resume >= 0) resume / chunk else 0) }
    val list = rememberLazyListState()
    LaunchedEffect(range) {
        if (ranges.size > 1) onRange(ranges[range].map { eps[it] })
        val pos = ranges.getOrNull(range).orEmpty().indexOf(resume)
        if (pos > 2) list.scrollToItem(pos - 2)
    }
    val studio = state.studio

    Column(Modifier.fillMaxSize()) {
        if (state.started) StudioSummary(state, accent, Modifier.padding(horizontal = 20.dp), onTranslations)
        else ChooseHint(accent, Modifier.padding(horizontal = 20.dp), onTranslations)
        if (ranges.size > 1) LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 14.dp),
        ) {
            itemsIndexed(ranges) { i, r ->
                val on = i == range
                val bg by animateColorAsState(if (on) accent.copy(alpha = 0.2f) else Nf.SurfaceHi, label = "rg")
                Text(
                    "${eps[r.first()].number}–${eps[r.last()].number}", style = Type.Label, color = if (on) accent else Nf.TextDim,
                    modifier = Modifier.clip(CircleShape).background(bg)
                        .border(1.dp, if (on) accent.copy(alpha = 0.5f) else Color.Transparent, CircleShape)
                        .clickable { range = i }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        LazyColumn(state = list, contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp), modifier = Modifier.weight(1f)) {
            items(ranges.getOrNull(range).orEmpty(), key = { eps[it].id }) { i ->
                val ep = eps[i]
                // Unknown until this range's translations are in (long shows): don't call it missing yet.
                val counted = state.studios != null && state.scope.any { it.id == ep.id }
                val missing = studio != null && counted && !studio.has(ep.id)
                EpisodeRow(
                    ep, isMovie, history.firstOrNull { it.episode == ep.number }, i < state.watchedOnSite, accent, i == resume,
                    missingNote = if (missing) "Нет у ${studio!!.author}" + (state.fallbackFor(ep)?.let { " · включится ${it.author}" } ?: "") else null,
                    downloaded = downloads.find(ep.id),
                    downloadStatus = downloads.active[ep.id],
                    onDownload = { onDownload(ep) },
                    onCancelDownload = { onCancelDownload(ep.id) },
                    onRetryDownload = { onRetryDownload(ep.id) },
                    onDeleteDownload = { onDeleteDownload(ep.id) },
                ) { onPlay(i) }
            }
        }
    }
}

/** Shown above the episodes of a title with no studio yet: what happens when you tap one. */
@Composable
private fun ChooseHint(accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Nf.SurfaceHi).pressable(0.98f, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.SwapHoriz, null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Перевод ещё не выбран", style = Type.Title, color = Nf.Text)
            Text("Нажмите на серию — предложим, в чём её смотреть", style = Type.Caption, color = Nf.TextMute)
        }
    }
}

/** The chosen studio for the whole title, its episode coverage, and the way to another one. */
@Composable
fun StudioSummary(state: PlayState.Ready, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val s = state.studio
    val count = state.all.size
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Nf.SurfaceHi)
            .pressable(0.98f, enabled = state.studios != null && count > 0, onClick = onClick).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(kindIcon(s?.kind ?: TranslationKind.VoiceRu), null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                when {
                    state.studios == null -> {
                        Box(Modifier.width(140.dp).height(12.dp).clip(CircleShape).shimmer())
                        Spacer(Modifier.height(7.dp))
                        Box(Modifier.width(90.dp).height(9.dp).clip(CircleShape).shimmer())
                    }
                    s == null -> {
                        Text("Переводов пока нет", style = Type.Title, color = Nf.Text)
                        Text(state.trError ?: "На Anime 365 ещё ничего не загрузили", style = Type.Caption, color = Nf.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    else -> {
                        Text(s.author, style = Type.Title, color = Nf.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(s.kind.title, s.qualityLabel).filter { it.isNotBlank() }.joinToString(" · "), style = Type.Caption, color = Nf.TextMute, maxLines = 1)
                    }
                }
            }
            if (count > 1) Row(
                Modifier.clip(CircleShape).background(accent.copy(alpha = 0.14f)).padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.SwapHoriz, null, tint = accent, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("Сменить", style = Type.Label, color = accent)
            }
        }
        if (s != null && state.scope.size > 1) {
            CoverageBar(s, state.scope, accent, Modifier.padding(top = 12.dp))
            Text(Studios.coverageText(s, state.scope), style = Type.Caption, color = Nf.TextDim, modifier = Modifier.padding(top = 7.dp))
        }
    }
}

@Composable
private fun EpisodeRow(
    ep: Episode, isMovie: Boolean, entry: WatchEntry?, siteWatched: Boolean, accent: Color, current: Boolean,
    missingNote: String?, downloaded: DownloadedEpisode?, downloadStatus: DownloadStatus?,
    onDownload: () -> Unit, onCancelDownload: () -> Unit, onRetryDownload: () -> Unit, onDeleteDownload: () -> Unit,
    onClick: () -> Unit,
) {
    val watched = entry?.finished == true || (siteWatched && entry == null)
    val dim = missingNote != null
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (current) accent.copy(alpha = 0.10f) else Color.Transparent)
            .pressable(0.98f, onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                    .background(if (watched) Nf.Mint.copy(alpha = 0.14f) else if (current) accent.copy(alpha = 0.2f) else Nf.SurfaceHi)
                    .then(if (dim) Modifier.border(1.dp, Nf.Line, RoundedCornerShape(13.dp)) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (watched) Icon(Icons.Rounded.Check, null, tint = Nf.Mint, modifier = Modifier.size(20.dp))
                else Text(
                    if (isMovie || ep.number <= 0) "▶" else ep.number.toString(),
                    style = Type.Title.copy(fontSize = if (ep.number >= 1000) 13.sp else 16.sp),
                    color = if (dim) Nf.TextMute else if (current) accent else Nf.Text,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (isMovie) "Фильм" else ep.label, style = Type.Title.copy(fontSize = 15.sp), color = if (dim) Nf.TextDim else Nf.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (sub, subColor) = when {
                    downloadStatus is DownloadStatus.Running -> {
                        val pct = (downloadStatus.progress * 100).toInt().coerceIn(0, 100)
                        (if (downloadStatus.totalBytes > 0L) "Загрузка $pct%" else "Загрузка…") to Nf.Violet
                    }
                    downloadStatus is DownloadStatus.Queued -> "В очереди на загрузку" to Nf.Violet
                    downloadStatus is DownloadStatus.Failed -> "Ошибка загрузки" to Nf.Red
                    downloaded != null -> ("Офлайн · ${formatBytes(downloaded.sizeBytes)}") to Nf.Mint
                    missingNote != null -> missingNote to Nf.Amber.copy(alpha = 0.85f)
                    entry != null && !entry.finished && entry.positionSec > 30 -> remaining(entry) to (if (current && !watched) accent else Nf.TextMute)
                    watched -> "Просмотрено" to Nf.TextMute
                    else -> (ep.episodeTitle?.takeIf { it.isNotBlank() } ?: uploaded(ep.firstUploadedDateTime)) to (if (current && !watched) accent else Nf.TextMute)
                }
                if (sub != null) Text(sub, style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis, color = subColor)
            }
            Spacer(Modifier.width(8.dp))
            EpisodeDownloadButton(downloaded, downloadStatus, onDownload, onCancelDownload, onRetryDownload, onDeleteDownload)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .background(if (dim) Color.Transparent else accent.copy(alpha = if (current) 0.9f else 0.16f))
                    .then(if (dim) Modifier.border(1.dp, Nf.Line, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, "Смотреть", tint = if (dim) Nf.TextMute else if (current) Nf.Ink else accent, modifier = Modifier.size(20.dp))
            }
        }
        if (entry != null && !watched && entry.progress > 0.01f) Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Nf.Line)) {
            Box(Modifier.fillMaxWidth(entry.progress).fillMaxHeight().background(accent))
        }
    }
}

@Composable
private fun EpisodeDownloadButton(
    downloaded: DownloadedEpisode?,
    status: DownloadStatus?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    when {
        status is DownloadStatus.Running -> Box(
            Modifier.size(34.dp).clip(CircleShape).background(Nf.Violet.copy(alpha = 0.14f)).pressable(0.88f, onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            if (status.totalBytes > 0L) CircularProgressIndicator(
                progress = { status.progress.coerceIn(0.05f, 1f) },
                modifier = Modifier.size(32.dp), color = Nf.Violet, trackColor = Nf.Line, strokeWidth = 2.5.dp,
            ) else CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Nf.Violet, strokeWidth = 2.5.dp)
            Icon(Icons.Rounded.Close, "Отменить загрузку", tint = Nf.Violet, modifier = Modifier.size(14.dp))
        }
        status is DownloadStatus.Queued -> Box(
            Modifier.size(34.dp).clip(CircleShape).background(Nf.Violet.copy(alpha = 0.14f)).pressable(0.88f, onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Nf.Violet.copy(alpha = 0.7f), strokeWidth = 2.dp)
            Icon(Icons.Rounded.Close, "Отменить загрузку", tint = Nf.Violet, modifier = Modifier.size(14.dp))
        }
        status is DownloadStatus.Failed -> Box(
            Modifier.size(34.dp).clip(CircleShape).background(Nf.Red.copy(alpha = 0.14f)).pressable(0.88f, onClick = onRetry),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Refresh, "Повторить загрузку", tint = Nf.Red, modifier = Modifier.size(18.dp)) }
        downloaded != null -> Box(
            Modifier.size(34.dp).clip(CircleShape).background(Nf.Mint.copy(alpha = 0.14f)).pressable(0.88f, onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.DownloadDone, "Удалить загрузку", tint = Nf.Mint, modifier = Modifier.size(18.dp)) }
        else -> Box(
            Modifier.size(34.dp).clip(CircleShape).background(Nf.SurfaceHi).pressable(0.88f, onClick = onDownload),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Download, "Скачать серию", tint = Nf.TextDim, modifier = Modifier.size(18.dp)) }
    }
}

/** "2023-09-29 18:05:57" → "Вышла 29 сент. 2023". */
private fun uploaded(s: String?): String? = runCatching {
    val d = java.time.LocalDate.parse(s!!.take(10))
    "Вышла " + d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale("ru")))
}.getOrNull()

/**
 * The studio picker: kinds as Anime 365 names them, and in each the studios with the most episodes first.
 * Every row shows which episodes that studio has. With [startFor] it is the first-play question "В чём смотреть?":
 * studios that have that episode come first, the rest are shown but can't start it, and a tap starts playback.
 */
@Composable
private fun TranslationsPage(
    state: PlayState.Ready?, accent: Color, startFor: Int?, preferred: TranslationKind, showBack: Boolean,
    onBack: () -> Unit, onPick: (Studio) -> Unit,
) {
    val groups = state?.studios.orEmpty()
    val kinds = groups.keys.toList()
    val ep = startFor?.let { state?.episodes?.getOrNull(it) }
    var kind by remember(kinds) {
        mutableStateOf(state?.studio?.kind?.takeIf { it in kinds } ?: preferred.takeIf { it in kinds } ?: kinds.firstOrNull())
    }
    val scope = state?.scope.orEmpty()
    val fav = rememberFavouriteStudios()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = if (showBack) 12.dp else 22.dp, end = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                Box(Modifier.size(40.dp).clip(CircleShape).pressable(0.85f, onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "К сериям", tint = Nf.Text, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(6.dp))
            }
            Column {
                Text(if (ep != null) "В чём смотреть?" else "Перевод", style = Type.Display, color = Nf.Text)
                Text(
                    when {
                        ep != null && (state?.episodes?.size ?: 0) > 1 -> "${ep.label} · выбор запомнится для следующих серий"
                        ep != null -> "Выбор запомнится для этого тайтла"
                        state != null && state.episodes.size > scope.size -> "Серии посчитаны для ${Studios.ranges(scope)} · выбор запомнится для тайтла"
                        else -> "Выбор запомнится для этого тайтла"
                    },
                    style = Type.Caption, color = Nf.TextMute,
                )
            }
        }
        if (state?.studios == null) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(4) { Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).shimmer()) }
            }
            return@Column
        }
        if (kinds.isEmpty()) {
            EmptyState("Переводов пока нет", state.trError ?: "На Anime 365 для этого тайтла ещё ничего не загрузили")
            return@Column
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        ) {
            items(kinds) { k ->
                val on = k == kind
                val bg by animateColorAsState(if (on) accent.copy(alpha = 0.2f) else Nf.SurfaceHi, label = "kd")
                Row(
                    Modifier.clip(CircleShape).background(bg).border(1.dp, if (on) accent.copy(alpha = 0.5f) else Color.Transparent, CircleShape)
                        .clickable { kind = k }.padding(horizontal = 13.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(kindIcon(k), null, tint = if (on) accent else Nf.TextMute, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${k.title} · ${groups[k]?.size ?: 0}", style = Type.Label, color = if (on) accent else Nf.TextDim)
                    if (groups[k].orEmpty().any { it.key in fav }) FavouriteDot()
                }
            }
        }
        if (kind == TranslationKind.Raw) Text(
            "Серия как в оригинале: японский звук, без озвучки и субтитров",
            style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
        val list = groups[kind].orEmpty().let { l -> if (ep != null) l.sortedByDescending { it.has(ep.id) } else l }
        // Starred studios lead, in a block of their own; the rest follow in the site's order.
        val favs = list.filter { it.key in fav }
        val rest = list.filterNot { it.key in fav }
        // A newly starred studio moves into "Любимые" at the top: follow it there instead of leaving it off screen.
        val listState = rememberLazyListState()
        val favKeys = favs.map { it.key }
        var seenFavs by remember(kind) { mutableStateOf(favKeys) }
        LaunchedEffect(favKeys) {
            if (favKeys.size > seenFavs.size && listState.firstVisibleItemIndex < 8) listState.animateScrollToItem(0)
            seenFavs = favKeys
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp)) {
            if (favs.isNotEmpty()) {
                item(key = "h-fav") {
                    ListOverline("Любимые", FavouriteTint, star = true, modifier = Modifier.animateItem().padding(start = 24.dp, top = 8.dp, bottom = 6.dp))
                }
                items(favs, key = { it.key }) { s -> StudioRow(s, state, ep, accent, scope, true, fav, onPick, Modifier.animateItem()) }
                if (rest.isNotEmpty()) item(key = "h-rest") {
                    ListOverline("Остальные", Nf.TextMute, modifier = Modifier.animateItem().padding(start = 24.dp, top = 18.dp, bottom = 6.dp))
                }
            }
            items(rest, key = { it.key }) { s -> StudioRow(s, state, ep, accent, scope, false, fav, onPick, Modifier.animateItem()) }
        }
    }
}

/**
 * One studio of the picker. A favourite sits on a faint gold wash with a gold star; any row can be starred or
 * unstarred from its star without picking it.
 */
@Composable
private fun StudioRow(
    s: Studio, state: PlayState.Ready, ep: Episode?, accent: Color, scope: List<Episode>, isFav: Boolean,
    fav: FavouriteStudios, onPick: (Studio) -> Unit, modifier: Modifier,
) {
    val on = s.key == state.studio?.key
    val lacks = ep != null && !s.has(ep.id)
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth().clip(shape)
            .background(
                when {
                    on -> accent.copy(alpha = 0.10f)
                    isFav -> FavouriteTint.copy(alpha = if (lacks) 0.03f else 0.06f)
                    else -> Color.Transparent
                }
            )
            .then(if (isFav) Modifier.border(1.dp, FavouriteTint.copy(alpha = if (on) 0.4f else 0.16f), shape) else Modifier)
            .clickable(enabled = !lacks) { onPick(s) }.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.author, style = Type.Title, color = if (on) accent else if (lacks) Nf.TextMute else Nf.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    lacks -> Text("Нет ${ep!!.label.lowercase()}", style = Type.Caption, color = Nf.Amber.copy(alpha = 0.8f), modifier = Modifier.padding(top = 2.dp))
                    s.details.isNotEmpty() -> Text(
                        s.details, style = Type.Caption, color = Nf.TextMute, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            FavouriteStar(isFav, { fav.toggle(s.key, s.author) })
            Spacer(Modifier.width(4.dp))
            if (s.qualityLabel.isNotBlank()) Tag(s.qualityLabel, if (s.bestHeight >= 1080 && !lacks) Nf.Mint else Nf.TextDim)
            when {
                on -> Icon(Icons.Rounded.Check, null, tint = accent, modifier = Modifier.padding(start = 10.dp).size(20.dp))
                ep != null && !lacks -> Box(
                    Modifier.padding(start = 10.dp).size(30.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, "Смотреть", tint = accent, modifier = Modifier.size(18.dp)) }
            }
        }
        if (scope.size > 1) {
            CoverageBar(s, scope, if (lacks) Nf.TextMute else if (on) accent else accent.copy(alpha = 0.6f), Modifier.padding(top = 8.dp), height = 5.dp)
            Text(Studios.coverageText(s, scope), style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

internal fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    return when { m10 == 1 && m100 != 11 -> one; m10 in 2..4 && m100 !in 12..14 -> few; else -> many }
}
