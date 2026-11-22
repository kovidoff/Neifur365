@file:OptIn(ExperimentalMaterial3Api::class)

package app.neifur.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.neifur.AnimeStub
import app.neifur.container
import app.neifur.core.userMessage
import app.neifur.data.a365.AccountState
import app.neifur.data.a365.ListRecord
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.Series
import app.neifur.data.shikimori.kindLabel
import app.neifur.ui.*
import kotlinx.coroutines.launch

/** What episode count should be saved when quickly moving a title to [target] from a long-press or swipe. */
internal fun quickWatchedFor(
    target: ListStatus,
    currentWatched: Int,
    total: Int?,
    aired: Int? = null,
    isMovie: Boolean = false,
): Int {
    if (target != ListStatus.Completed) return currentWatched.coerceAtLeast(0)
    val full = total?.takeIf { it > 0 } ?: aired?.takeIf { it > 0 } ?: if (isMovie) 1 else null
    return maxOf(currentWatched.coerceAtLeast(0), full ?: 0)
}

/**
 * Compact context sheet opened by long-pressing any anime card (Home, Catalog, Schedule, Continue watching):
 * lets the viewer file a title into a list in one tap without opening its detail page.
 */
@Composable
fun QuickAnimeSheet(
    stub: AnimeStub,
    series: Series? = null,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onNotice: (String) -> Unit,
    onForgetHistory: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val account by c.account.state.collectAsStateWithLifecycle()
    val lists by c.lists.state.collectAsStateWithLifecycle()
    val favorites by c.prefs.favorites.collectAsStateWithLifecycle(emptyList())
    val signedIn = account is AccountState.SignedIn
    val seriesId = stub.id.toLongOrNull()
    val listed = seriesId?.let { lists.titles[it] }
    val currentStatus = listed?.status
    val currentWatched = listed?.entry?.watched ?: 0
    val currentScore = listed?.entry?.score
    val total = listed?.total ?: series?.numberOfEpisodes?.takeIf { it > 0 }
    val isMovie = series?.isMovie == true || listed?.series?.isMovie == true || stub.kind.equals("movie", true)
    val isFav = remember(favorites, stub.id) { favorites.any { it.id == stub.id } }

    var openFullEditor by remember { mutableStateOf(false) }

    if (openFullEditor && seriesId != null) {
        ListEditor(
            title = stub.title,
            record = listed?.let { ListRecord(it.status, it.entry.watched, it.entry.score) } ?: ListRecord(null, 0, null),
            total = total,
            aired = listed?.aired,
            isMovie = isMovie,
            onDismiss = { openFullEditor = false; onDismiss() },
            onChange = { st, w, sc ->
                scope.launch {
                    c.lists.set(seriesId, st, w, sc, series = series ?: listed?.series, title = stub.title)
                        .onFailure { onNotice(it.userMessage()) }
                }
            },
        )
        return
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Nf.Surface,
        contentColor = Nf.Text,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(40.dp, 4.dp).clip(CircleShape).background(Nf.Line)) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp)) {
            // Preview header: poster + title + meta + current list pill
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PosterImage(stub.poster, Modifier.width(54.dp).aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stub.title, style = Type.Title.copy(fontSize = 16.sp), color = Nf.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        formatScore(stub.score)?.let { s ->
                            Icon(Icons.Rounded.Star, null, tint = Nf.Amber, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(s, style = Type.Label, color = Nf.Text)
                            Spacer(Modifier.width(8.dp))
                        }
                        val meta = listOfNotNull(stub.year?.toString(), kindLabel(stub.kind).takeIf { it.isNotBlank() }).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, style = Type.Caption, color = Nf.TextMute, maxLines = 1)
                    }
                    if (currentStatus != null) {
                        val sc = statusColor(currentStatus)
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(sc))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "В списке «${currentStatus.title}»",
                                style = Type.Caption, color = sc, maxLines = 1,
                            )
                        }
                    }
                }
            }

            // One-tap list status picker (for signed-in Anime 365 titles)
            if (signedIn && seriesId != null) {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListOrder.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { st ->
                                val on = st == currentStatus
                                val tint = statusColor(st)
                                val bg by animateColorAsState(if (on) tint.copy(alpha = 0.2f) else Nf.Ink, label = "qs-bg")
                                val br by animateColorAsState(if (on) tint.copy(alpha = 0.6f) else Nf.Line, label = "qs-br")
                                Row(
                                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                                        .border(1.dp, br, RoundedCornerShape(14.dp))
                                        .pressable(0.96f) {
                                            if (on) {
                                                onDismiss()
                                            } else {
                                                val w = quickWatchedFor(st, currentWatched, total, listed?.aired, isMovie)
                                                onDismiss()
                                                onNotice("В «${st.title}» · ${stub.title}")
                                                c.scope.launch {
                                                    c.lists.set(seriesId, st, w, currentScore, series = series ?: listed?.series, title = stub.title)
                                                        .onFailure { onNotice(it.userMessage()) }
                                                }
                                            }
                                        }
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(statusIcon(st), null, tint = if (on) tint else Nf.TextDim, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(st.title, style = Type.Label, color = if (on) Nf.Text else Nf.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }

                    if (currentStatus != null) {
                        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(14.dp)).background(Nf.SurfaceHi)
                                    .border(1.dp, Nf.Line, RoundedCornerShape(14.dp))
                                    .pressable(0.96f) { openFullEditor = true }.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Rounded.Tune, null, tint = Nf.Violet, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Серии и оценка", style = Type.Label, color = Nf.Text, maxLines = 1)
                            }
                            Row(
                                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(14.dp)).background(Nf.Red.copy(alpha = 0.12f))
                                    .border(1.dp, Nf.Red.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                    .pressable(0.96f) {
                                        onDismiss()
                                        onNotice("Убрано из списка · ${stub.title}")
                                        c.scope.launch {
                                            c.lists.set(seriesId, null, 0, null)
                                                .onFailure { onNotice(it.userMessage()) }
                                        }
                                    }.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, null, tint = Nf.Red, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Убрать", style = Type.Label, color = Nf.Red, maxLines = 1)
                            }
                        }
                    }
                }
            } else if (!signedIn) {
                Box(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
                    QuickActionRow(
                        icon = if (isFav) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        tint = if (isFav) Nf.Violet else Nf.TextDim,
                        title = if (isFav) "Убрать из избранного" else "В избранное на телефоне",
                    ) {
                        onDismiss()
                        onNotice(if (isFav) "Убрано из избранного" else "Добавлено в избранное")
                        c.scope.launch { c.prefs.toggleFavorite(stub.saved()) }
                    }
                }
            }

            // Secondary quick actions
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onForgetHistory != null) {
                    QuickActionRow(Icons.Rounded.HistoryToggleOff, Nf.Amber, "Убрать из истории просмотра") {
                        onDismiss()
                        onForgetHistory()
                        onNotice("Убрано из «Продолжить просмотр»")
                    }
                }
                if (seriesId != null) {
                    QuickActionRow(Icons.Rounded.Share, Nf.TextDim, "Поделиться ссылкой") {
                        onDismiss()
                        c.scope.launch {
                            val base = runCatching { c.a365.baseUrl() }.getOrDefault("https://smotret-anime.org")
                            shareTitle(ctx, stub.title, "$base/catalog/$seriesId")
                        }
                    }
                }
                QuickActionRow(Icons.AutoMirrored.Rounded.OpenInNew, Nf.TextDim, "Открыть страницу аниме") {
                    onDismiss()
                    onOpen()
                }
            }
        }
    }
}

@Composable
private fun QuickActionRow(icon: ImageVector, tint: Color, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Nf.Ink), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = Type.Label.copy(fontSize = 14.sp), color = Nf.Text)
    }
}

private fun shareTitle(ctx: Context, title: String, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(Intent.createChooser(intent, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
