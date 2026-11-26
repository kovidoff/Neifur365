package app.neifur.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.neifur.PlaySession
import app.neifur.container
import app.neifur.data.WatchEntry
import app.neifur.downloads.DownloadStatus
import app.neifur.downloads.DownloadedAnime
import app.neifur.downloads.DownloadedEpisode
import app.neifur.downloads.formatBytes
import app.neifur.player.PlayerActivity
import app.neifur.ui.Nf
import app.neifur.ui.PosterImage
import app.neifur.ui.Type
import app.neifur.ui.pressable

/** Launches [PlayerActivity] straight from locally downloaded episodes — works with zero internet. */
fun playDownloaded(ctx: Context, anime: DownloadedAnime, startEpisodeId: Long? = null, history: List<WatchEntry> = emptyList()) {
    if (anime.episodes.isEmpty()) return
    val idx = when {
        startEpisodeId != null -> anime.episodes.indexOfFirst { it.episodeId == startEpisodeId }.coerceAtLeast(0)
        else -> {
            val h = history.filter { it.animeId == anime.animeId }
            val unfinished = anime.episodes.indexOfFirst { ep ->
                val e = h.firstOrNull { it.episode == ep.episodeNumber }
                e == null || !e.finished
            }
            if (unfinished >= 0) unfinished else 0
        }
    }
    val chosen = anime.episodes[idx]
    ctx.container.session = PlaySession(
        anime = anime.stub(),
        episodes = anime.episodes.map { it.toEpisode() },
        index = idx,
        pick = chosen.studioKey,
        pickName = chosen.studioName,
    )
    ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
}

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val state by c.downloads.state.collectAsStateWithLifecycle()
    val history by c.prefs.history.collectAsStateWithLifecycle(emptyList())
    val groups = remember(state.items) { state.byAnime() }
    val activeList = remember(state.active) {
        state.active.values.sortedBy {
            when (it) {
                is DownloadStatus.Running -> 0
                is DownloadStatus.Queued -> 1
                is DownloadStatus.WaitingWifi -> 2
                is DownloadStatus.Failed -> 3
            }
        }
    }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmDeleteAnime by remember { mutableStateOf<DownloadedAnime?>(null) }
    var confirmDeleteEp by remember { mutableStateOf<DownloadedEpisode?>(null) }

    Box(Modifier.fillMaxSize().background(Nf.Ink).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 44.dp),
        ) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(Nf.Surface).border(1.dp, Nf.Line, CircleShape)
                        .pressable(0.88f, onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Nf.Text, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Загрузки", style = Type.Display.copy(fontSize = 22.sp), color = Nf.Text, maxLines = 1)
                    val sub = if (state.items.isNotEmpty()) {
                        val n = state.items.size
                        "$n ${plural(n, "серия", "серии", "серий")} · ${formatBytes(state.totalBytes)}"
                    } else "Офлайн-просмотр без интернета"
                    Text(sub, style = Type.Caption, color = Nf.TextMute, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
                if (state.items.isNotEmpty()) {
                    Text(
                        "Очистить", style = Type.Label, color = Nf.Red,
                        modifier = Modifier.clip(CircleShape).background(Nf.Red.copy(alpha = 0.12f))
                            .pressable(0.92f) { confirmClearAll = true }
                            .padding(horizontal = 13.dp, vertical = 8.dp),
                    )
                }
            }
        }

        if (activeList.isNotEmpty()) {
            item(key = "h-active") {
                Text(
                    "СКАЧИВАЮТСЯ", style = Type.Overline, color = Nf.TextMute,
                    modifier = Modifier.padding(start = 26.dp, top = 4.dp, bottom = 10.dp),
                )
            }
            items(activeList, key = { "act-${it.episodeId}" }) { st ->
                ActiveDownloadCard(
                    status = st,
                    modifier = Modifier.animateItem(),
                    onRetry = { c.downloads.retry(st.episodeId) },
                    onCancel = { c.downloads.cancel(st.episodeId) },
                )
            }
        }

        if (groups.isNotEmpty()) {
            item(key = "h-saved") {
                Text(
                    "СКАЧАНО", style = Type.Overline, color = Nf.TextMute,
                    modifier = Modifier.padding(start = 26.dp, top = if (activeList.isNotEmpty()) 18.dp else 4.dp, bottom = 10.dp),
                )
            }
            items(groups, key = { "grp-${it.animeId}" }) { anime ->
                DownloadedAnimeCard(
                    anime = anime,
                    history = history,
                    modifier = Modifier.animateItem(),
                    onPlay = { ep -> playDownloaded(ctx, anime, ep.episodeId, history) },
                    onDeleteEpisode = { ep -> confirmDeleteEp = ep },
                    onDeleteAnime = { confirmDeleteAnime = anime },
                )
            }
        } else if (activeList.isEmpty()) {
            item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(top = 72.dp, start = 32.dp, end = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(68.dp).clip(CircleShape).background(Nf.Violet.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.CloudDownload, null, tint = Nf.Violet, modifier = Modifier.size(32.dp))
                    }
                    Text("Пока ничего не скачано", style = Type.Title, color = Nf.Text, modifier = Modifier.padding(top = 18.dp))
                    Text(
                        "Нажмите значок загрузки рядом с серией на странице тайтла — видео и субтитры сохранятся для просмотра без интернета",
                        style = Type.Body, color = Nf.TextDim, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
    }

    if (confirmClearAll) AlertDialog(
        onDismissRequest = { confirmClearAll = false },
        containerColor = Nf.SurfaceHi, titleContentColor = Nf.Text, textContentColor = Nf.TextDim,
        title = { Text("Удалить все загрузки?", style = Type.Section) },
        text = { Text("Освободится ${formatBytes(state.totalBytes)}. История просмотра и списки Anime 365 сохранятся.", style = Type.Body) },
        confirmButton = { TextButton({ c.downloads.clearAll(); confirmClearAll = false }) { Text("Удалить", color = Nf.Red, style = Type.Label) } },
        dismissButton = { TextButton({ confirmClearAll = false }) { Text("Отмена", color = Nf.Text, style = Type.Label) } },
    )

    confirmDeleteAnime?.let { anime ->
        AlertDialog(
            onDismissRequest = { confirmDeleteAnime = null },
            containerColor = Nf.SurfaceHi, titleContentColor = Nf.Text, textContentColor = Nf.TextDim,
            title = { Text("Удалить «${anime.title}»?", style = Type.Section) },
            text = { Text("Будут удалены все скачанные серии этого тайтла (${formatBytes(anime.totalBytes)}).", style = Type.Body) },
            confirmButton = { TextButton({ c.downloads.deleteAnime(anime.animeId); confirmDeleteAnime = null }) { Text("Удалить", color = Nf.Red, style = Type.Label) } },
            dismissButton = { TextButton({ confirmDeleteAnime = null }) { Text("Отмена", color = Nf.Text, style = Type.Label) } },
        )
    }

    confirmDeleteEp?.let { ep ->
        AlertDialog(
            onDismissRequest = { confirmDeleteEp = null },
            containerColor = Nf.SurfaceHi, titleContentColor = Nf.Text, textContentColor = Nf.TextDim,
            title = { Text("Удалить ${ep.episodeLabel.lowercase()}?", style = Type.Section) },
            text = { Text("Освободится ${formatBytes(ep.sizeBytes)}.", style = Type.Body) },
            confirmButton = { TextButton({ c.downloads.delete(ep.episodeId); confirmDeleteEp = null }) { Text("Удалить", color = Nf.Red, style = Type.Label) } },
            dismissButton = { TextButton({ confirmDeleteEp = null }) { Text("Отмена", color = Nf.Text, style = Type.Label) } },
        )
    }
}

@Composable
private fun ActiveDownloadCard(
    status: DownloadStatus,
    modifier: Modifier,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier.padding(horizontal = 20.dp, vertical = 5.dp).fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)).background(Nf.Surface)
            .border(1.dp, Color(0x0DFFFFFF), RoundedCornerShape(20.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterImage(status.animePoster, Modifier.width(44.dp).aspectRatio(0.7f).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(status.animeTitle, style = Type.Title.copy(fontSize = 14.5.sp), color = Nf.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (status) {
                is DownloadStatus.Queued -> Text(
                    "${status.episodeLabel} · в очереди", style = Type.Caption, color = Nf.TextMute,
                    modifier = Modifier.padding(top = 2.dp),
                )
                is DownloadStatus.Running -> {
                    val pct = (status.progress * 100).toInt().coerceIn(0, 100)
                    val sizeText = when {
                        status.totalBytes > 0L -> "$pct% · ${formatBytes(status.bytes)} из ${formatBytes(status.totalBytes)}"
                        status.bytes > 0L -> formatBytes(status.bytes)
                        else -> "получаю ссылку…"
                    }
                    Text("${status.episodeLabel} · $sizeText", style = Type.Caption, color = Nf.Violet, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Nf.Line)) {
                        Box(Modifier.fillMaxWidth(status.progress.coerceIn(0.03f, 1f)).fillMaxHeight().clip(CircleShape).background(Nf.Violet))
                    }
                }
                is DownloadStatus.WaitingWifi -> Row(
                    Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Wifi, null, tint = Nf.Amber, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("${status.episodeLabel} · ждёт Wi-Fi", style = Type.Caption, color = Nf.Amber, maxLines = 1)
                }
                is DownloadStatus.Failed -> Text(
                    "${status.episodeLabel} · ${status.message}", style = Type.Caption, color = Nf.Red, maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (status is DownloadStatus.Failed) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Nf.Violet.copy(alpha = 0.16f)).pressable(0.88f, onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Refresh, "Повторить", tint = Nf.Violet, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(6.dp))
        }
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Nf.Ink).pressable(0.88f, onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Close, "Отменить", tint = Nf.TextDim, modifier = Modifier.size(17.dp)) }
    }
}

@Composable
private fun DownloadedAnimeCard(
    anime: DownloadedAnime,
    history: List<WatchEntry>,
    modifier: Modifier,
    onPlay: (DownloadedEpisode) -> Unit,
    onDeleteEpisode: (DownloadedEpisode) -> Unit,
    onDeleteAnime: () -> Unit,
) {
    Column(
        modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth()
            .clip(RoundedCornerShape(22.dp)).background(Nf.Surface)
            .border(1.dp, Color(0x0DFFFFFF), RoundedCornerShape(22.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PosterImage(anime.poster, Modifier.width(50.dp).aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(anime.title, style = Type.Title.copy(fontSize = 15.5.sp), color = Nf.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val n = anime.episodes.size
                Text(
                    "$n ${plural(n, "серия", "серии", "серий")} · ${formatBytes(anime.totalBytes)}",
                    style = Type.Caption, color = Nf.Mint, modifier = Modifier.padding(top = 3.dp),
                )
            }
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Nf.Ink).pressable(0.88f, onClick = onDeleteAnime),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.DeleteOutline, "Удалить тайтл", tint = Nf.TextDim, modifier = Modifier.size(18.dp)) }
        }
        Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(Nf.Line.copy(alpha = 0.55f)))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            anime.episodes.forEach { ep ->
                val watch = history.firstOrNull { it.animeId == anime.animeId && it.episode == ep.episodeNumber }
                val finished = watch?.finished == true
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nf.Ink.copy(alpha = 0.55f))
                        .pressable(0.98f) { onPlay(ep) }.padding(horizontal = 10.dp, vertical = 9.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
                                .background(if (finished) Nf.Mint.copy(alpha = 0.14f) else Nf.Violet.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (finished) Icon(Icons.Rounded.Check, null, tint = Nf.Mint, modifier = Modifier.size(18.dp))
                            else Text(
                                if (ep.episodeNumber > 0) ep.episodeNumber.toString() else "▶",
                                style = Type.Title.copy(fontSize = 14.sp), color = Nf.Violet,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ep.episodeLabel, style = Type.Title.copy(fontSize = 14.5.sp), color = Nf.Text, maxLines = 1)
                            val sub = listOfNotNull(
                                ep.studioName.takeIf { it.isNotBlank() },
                                "${ep.height}p",
                                formatBytes(ep.sizeBytes),
                                watch?.takeIf { !it.finished && it.positionSec > 30 }?.let { remaining(it) },
                            ).joinToString(" · ")
                            Text(sub, style = Type.Caption, color = Nf.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                        }
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(Nf.Violet.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.PlayArrow, "Смотреть", tint = Nf.Violet, modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.DeleteOutline, "Удалить серию", tint = Nf.TextMute,
                            modifier = Modifier.size(32.dp).clip(CircleShape).clickable { onDeleteEpisode(ep) }.padding(7.dp),
                        )
                    }
                    if (watch != null && !finished && watch.progress > 0.01f) {
                        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Nf.Line)) {
                            Box(Modifier.fillMaxWidth(watch.progress).fillMaxHeight().clip(CircleShape).background(Nf.Violet))
                        }
                    }
                }
            }
        }
    }
}
