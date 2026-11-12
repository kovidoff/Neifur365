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
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.neifur.data.WatchEntry
import app.neifur.data.a365.Episode
import app.neifur.data.a365.Translation
import app.neifur.data.a365.TranslationKind
import app.neifur.ui.EmptyState
import app.neifur.ui.Nf
import app.neifur.ui.Tag
import app.neifur.ui.Type
import app.neifur.ui.pressable
import app.neifur.ui.shimmer

enum class SheetPage { Episodes, Translations }

fun kindIcon(k: TranslationKind): ImageVector = when (k) {
    TranslationKind.VoiceRu, TranslationKind.VoiceEn -> Icons.Rounded.Mic
    else -> Icons.Rounded.ClosedCaption
}

/**
 * Episodes and translations of a title, in a sheet over its page so a 1000-episode show doesn't turn the
 * page into a phone book. The translation list is the one of the episode that plays next.
 */
@Composable
fun EpisodesSheet(
    state: PlayState, history: List<WatchEntry>, isMovie: Boolean, accent: Color, startPage: SheetPage,
    onDismiss: () -> Unit, onPlay: (Int) -> Unit, onPick: (Translation) -> Unit, onRetry: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember { mutableStateOf(startPage) }
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
                    SheetPage.Episodes -> EpisodesPage(state, history, isMovie, accent, onPlay, onRetry) { page = SheetPage.Translations }
                    SheetPage.Translations -> TranslationsPage(
                        state as? PlayState.Ready, accent,
                        onBack = { page = SheetPage.Episodes },
                        onPick = { onPick(it); page = SheetPage.Episodes },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodesPage(
    state: PlayState, history: List<WatchEntry>, isMovie: Boolean, accent: Color,
    onPlay: (Int) -> Unit, onRetry: () -> Unit, onTranslations: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 22.dp).padding(top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (isMovie) "Просмотр" else "Серии", style = Type.Display, color = Nf.Text, modifier = Modifier.weight(1f))
            (state as? PlayState.Ready)?.let { Text("${it.episodes.size} ${plural(it.episodes.size, "серия", "серии", "серий")}", style = Type.Label, color = Nf.TextMute) }
        }
        when (state) {
            PlayState.Loading -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(20.dp)).shimmer())
                repeat(5) { Box(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(16.dp)).shimmer()) }
            }
            is PlayState.Error -> EmptyState("Не получилось загрузить серии", state.message, action = "Повторить", onAction = onRetry)
            is PlayState.Ready ->
                if (state.episodes.isEmpty()) EmptyState("Серий пока нет", "На Anime 365 ещё не загрузили ни одной серии этого тайтла")
                else Episodes(state, history, isMovie, accent, onPlay, onTranslations)
        }
    }
}

@Composable
private fun Episodes(
    state: PlayState.Ready, history: List<WatchEntry>, isMovie: Boolean, accent: Color,
    onPlay: (Int) -> Unit, onTranslations: () -> Unit,
) {
    val eps = state.episodes
    // Long shows are split into ranges of 50; the sheet opens on the range you're watching.
    val chunk = 50
    val ranges = if (eps.size > 60) eps.indices.chunked(chunk) else listOf(eps.indices.toList())
    val resume = state.target
    var range by remember { mutableIntStateOf(if (ranges.size > 1 && resume >= 0) resume / chunk else 0) }
    val list = rememberLazyListState()
    LaunchedEffect(range) {
        val pos = ranges.getOrNull(range).orEmpty().indexOf(resume)
        if (pos > 2) list.scrollToItem(pos - 2)
    }

    Column(Modifier.fillMaxSize()) {
        TranslationCard(state, accent, onTranslations)
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
                EpisodeRow(ep, isMovie, history.firstOrNull { it.episode == ep.number }, i < state.watchedOnSite, accent, i == resume) { onPlay(i) }
            }
        }
    }
}

/** Which translation plays, with a way into the full list. */
@Composable
private fun TranslationCard(state: PlayState.Ready, accent: Color, onClick: () -> Unit) {
    val t = state.chosen
    val count = state.options?.values?.sumOf { it.size }
    Row(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Nf.SurfaceHi)
            .pressable(0.98f, enabled = state.options != null, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(kindIcon(t?.kind ?: TranslationKind.VoiceRu), null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            when {
                state.options == null && state.trError == null -> {
                    Box(Modifier.width(140.dp).height(12.dp).clip(CircleShape).shimmer())
                    Spacer(Modifier.height(7.dp))
                    Box(Modifier.width(90.dp).height(9.dp).clip(CircleShape).shimmer())
                }
                t == null -> {
                    Text("Нет переводов", style = Type.Title, color = Nf.Text)
                    Text(state.trError ?: "Для этой серии пока ничего не загрузили", style = Type.Caption, color = Nf.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                else -> {
                    Text(t.author, style = Type.Title, color = Nf.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(t.kind.title, t.qualityLabel).filter { it.isNotBlank() }.joinToString(" · "), style = Type.Caption, color = Nf.TextMute, maxLines = 1)
                }
            }
        }
        if (count != null && count > 0) Row(
            Modifier.clip(CircleShape).background(accent.copy(alpha = 0.14f)).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$count", style = Type.Label, color = accent)
            Icon(Icons.Rounded.UnfoldMore, "Сменить перевод", tint = accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, isMovie: Boolean, entry: WatchEntry?, siteWatched: Boolean, accent: Color, current: Boolean, onClick: () -> Unit) {
    val watched = entry?.finished == true || (siteWatched && entry == null)
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (current) accent.copy(alpha = 0.10f) else Color.Transparent)
            .pressable(0.98f, onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(if (watched) Nf.Mint.copy(alpha = 0.14f) else if (current) accent.copy(alpha = 0.2f) else Nf.SurfaceHi),
                contentAlignment = Alignment.Center,
            ) {
                if (watched) Icon(Icons.Rounded.Check, null, tint = Nf.Mint, modifier = Modifier.size(20.dp))
                else Text(
                    if (isMovie || ep.number <= 0) "▶" else ep.number.toString(),
                    style = Type.Title.copy(fontSize = if (ep.number >= 1000) 13.sp else 16.sp), color = if (current) accent else Nf.Text,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (isMovie) "Фильм" else ep.label, style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    entry != null && !entry.finished && entry.positionSec > 30 -> remaining(entry)
                    watched -> "Просмотрено"
                    else -> ep.episodeTitle?.takeIf { it.isNotBlank() } ?: uploaded(ep.firstUploadedDateTime)
                }
                if (sub != null) Text(sub, style = Type.Caption, color = if (current && !watched) accent else Nf.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(34.dp).clip(CircleShape).background(accent.copy(alpha = if (current) 0.9f else 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, "Смотреть", tint = if (current) Nf.Ink else accent, modifier = Modifier.size(20.dp))
            }
        }
        if (entry != null && !watched && entry.progress > 0.01f) Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Nf.Line)) {
            Box(Modifier.fillMaxWidth(entry.progress).fillMaxHeight().background(accent))
        }
    }
}

/** "2023-09-29 18:05:57" → "Вышла 29 сент. 2023". */
private fun uploaded(s: String?): String? = runCatching {
    val d = java.time.LocalDate.parse(s!!.take(10))
    "Вышла " + d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale("ru")))
}.getOrNull()

@Composable
private fun TranslationsPage(state: PlayState.Ready?, accent: Color, onBack: () -> Unit, onPick: (Translation) -> Unit) {
    val options = state?.options.orEmpty()
    val kinds = options.keys.toList()
    var kind by remember { mutableStateOf(state?.chosen?.kind ?: kinds.firstOrNull()) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 12.dp, end = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).pressable(0.85f, onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "К сериям", tint = Nf.Text, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(6.dp))
            Column {
                Text("Перевод", style = Type.Display, color = Nf.Text)
                state?.episodes?.getOrNull(state.target)?.let {
                    Text("Список для: ${it.label.lowercase()}. Выбор запомнится для следующих серий", style = Type.Caption, color = Nf.TextMute)
                }
            }
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
                    Text("${k.short} · ${options[k]?.size ?: 0}", style = Type.Label, color = if (on) accent else Nf.TextDim)
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp)) {
            items(options[kind].orEmpty(), key = { it.id }) { t ->
                val on = t.pickKey == state?.chosen?.pickKey
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (on) accent.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable { onPick(t) }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.author, style = Type.Title, color = if (on) accent else Nf.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val people = t.authorsList.joinToString(", ").takeIf { it.isNotBlank() && it != t.author }
                        if (people != null) Text(people, style = Type.Caption, color = Nf.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    if (t.qualityLabel.isNotBlank()) Tag(t.qualityLabel, if (t.height >= 1080) Nf.Mint else Nf.TextDim)
                    if (on) Icon(Icons.Rounded.Check, null, tint = accent, modifier = Modifier.padding(start = 10.dp).size(20.dp))
                }
            }
        }
    }
}

internal fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    return when { m10 == 1 && m100 != 11 -> one; m10 in 2..4 && m100 !in 12..14 -> few; else -> many }
}
