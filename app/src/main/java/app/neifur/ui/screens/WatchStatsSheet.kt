@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.neifur.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.ListsState
import app.neifur.data.a365.Profile
import app.neifur.ui.*
import java.util.Locale

data class WatchStats(
    val totalTitles: Int,
    val touchedTitles: Int,
    val completedTitles: Int,
    val totalEpisodes: Int,
    val estimatedMinutes: Int,
    val meanScore: Double?,
    val ratedCount: Int,
    val byStatus: Map<ListStatus, Int>,
    val scoreHistogram: Map<Int, Int>,
    val topGenres: List<Pair<String, Int>>,
)

/** Computes viewing statistics directly from already-loaded Anime 365 lists (zero network calls). */
internal fun computeWatchStats(lists: ListsState): WatchStats {
    val all = lists.titles.values
    val byStatus = ListOrder.associateWith { st -> all.count { it.status == st } }
    val totalEpisodes = all.sumOf { it.entry.watched.coerceAtLeast(0) }
    val touchedTitles = all.count { it.entry.watched > 0 || it.status == ListStatus.Completed }
    val estimatedMinutes = all.sumOf { t ->
        val w = t.entry.watched.coerceAtLeast(0)
        if (w == 0) 0 else if (t.series?.isMovie == true) w * 95 else w * 24
    }
    val scores = all.mapNotNull { it.entry.score?.takeIf { s -> s in 1..10 } }
    val scoreHistogram = (1..10).associateWith { s -> scores.count { it == s } }

    fun collectGenres(source: Iterable<app.neifur.data.a365.ListedTitle>): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        source.forEach { t ->
            t.series?.genres.orEmpty()
                .mapNotNull { it.title.trim().takeIf(String::isNotEmpty) }
                .distinct()
                .forEach { g -> counts[g] = (counts[g] ?: 0) + 1 }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .take(5)
            .map { it.key to it.value }
    }

    val activeGenres = collectGenres(all.filter { it.status != ListStatus.Planned && it.status != ListStatus.Dropped })
    val topGenres = activeGenres.ifEmpty { collectGenres(all.filter { it.status != ListStatus.Planned }) }

    return WatchStats(
        totalTitles = all.size,
        touchedTitles = touchedTitles,
        completedTitles = byStatus[ListStatus.Completed] ?: 0,
        totalEpisodes = totalEpisodes,
        estimatedMinutes = estimatedMinutes,
        meanScore = if (scores.isEmpty()) null else scores.average(),
        ratedCount = scores.size,
        byStatus = byStatus,
        scoreHistogram = scoreHistogram,
        topGenres = topGenres,
    )
}

/** Formats [minutes] as a headline value ("184 ч") and a friendly subtitle ("≈ 7.7 дн."). */
internal fun formatWatchTime(minutes: Int): Pair<String, String> {
    if (minutes <= 0) return "0 ч" to "пока без серий"
    if (minutes < 60) return "$minutes мин" to "меньше часа"
    val hours = (minutes + 30) / 60
    val sub = if (hours >= 24) String.format(Locale.US, "≈ %.1f дн.", hours / 24.0) else "примерно по 24 мин"
    return "$hours ч" to sub
}

private fun pluralTitles(n: Int): String {
    val m10 = n % 10; val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "тайтле"
        else -> "тайтлах"
    }
}

private fun pluralRatings(n: Int): String {
    val m10 = n % 10; val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "оценка"
        m10 in 2..4 && m100 !in 12..14 -> "оценки"
        else -> "оценок"
    }
}

/**
 * Viewing stats card opened from the viewer's profile chip in Lists (or from the stats strip in Settings):
 * Bento KPI grid, status distribution bar, personal 1–10 score histogram, and top watched genres.
 */
@Composable
fun WatchStatsSheet(
    profile: Profile,
    lists: ListsState,
    onDismiss: () -> Unit,
) {
    val stats = remember(lists.titles) { computeWatchStats(lists) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Nf.Surface,
        contentColor = Nf.Text,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(40.dp, 4.dp).clip(CircleShape).background(Nf.Line)) },
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 26.dp),
            ) {
                // Header: avatar + name + subtitle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Nf.Violet.copy(alpha = 0.55f), Nf.Pink.copy(alpha = 0.45f))))
                            .padding(2.dp),
                    ) {
                        PosterImage(profile.avatarUrl, Modifier.fillMaxSize().clip(CircleShape))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, style = Type.Section, color = Nf.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Статистика просмотра", style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (lists.loaded && stats.totalTitles > 0) {
                        Text(
                            "${stats.totalTitles} в списках",
                            style = Type.Caption,
                            color = Nf.Violet,
                            modifier = Modifier.clip(CircleShape)
                                .background(Nf.Violet.copy(alpha = 0.14f))
                                .border(1.dp, Nf.Violet.copy(alpha = 0.35f), CircleShape)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                when {
                    !lists.loaded && lists.loading -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f).height(98.dp).clip(RoundedCornerShape(20.dp)).shimmer())
                            Box(Modifier.weight(1f).height(98.dp).clip(RoundedCornerShape(20.dp)).shimmer())
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f).height(98.dp).clip(RoundedCornerShape(20.dp)).shimmer())
                            Box(Modifier.weight(1f).height(98.dp).clip(RoundedCornerShape(20.dp)).shimmer())
                        }
                    }
                    stats.totalTitles == 0 -> {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Nf.Ink)
                                .border(1.dp, Nf.Line, RoundedCornerShape(22.dp))
                                .padding(horizontal = 22.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(52.dp).clip(CircleShape).background(Nf.Violet.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.BarChart, null, tint = Nf.Violet, modifier = Modifier.size(26.dp))
                            }
                            Text("Пока нет статистики", style = Type.Title, color = Nf.Text, modifier = Modifier.padding(top = 12.dp))
                            Text(
                                "Добавьте аниме в списки и отмечайте серии — здесь появятся часы просмотра, средняя оценка и любимые жанры",
                                style = Type.Caption, color = Nf.TextDim, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    else -> {
                        // 2×2 Bento KPI tiles
                        val (timeValue, timeSub) = remember(stats.estimatedMinutes) { formatWatchTime(stats.estimatedMinutes) }
                        val scoreValue = stats.meanScore?.let { String.format(Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') } ?: "—"
                        val scoreSub = if (stats.ratedCount > 0) "${stats.ratedCount} ${pluralRatings(stats.ratedCount)}" else "пока без оценок"
                        val epSub = if (stats.touchedTitles > 0) "в ${stats.touchedTitles} ${pluralTitles(stats.touchedTitles)}" else "ещё не начато"
                        val donePct = if (stats.totalTitles > 0) (stats.completedTitles * 100 / stats.totalTitles) else 0
                        val doneSub = "из ${stats.totalTitles} · $donePct%"

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatBentoTile(
                                icon = Icons.Rounded.Schedule,
                                accent = Nf.Violet,
                                label = "Время",
                                value = timeValue,
                                subtitle = timeSub,
                                modifier = Modifier.weight(1f),
                            )
                            StatBentoTile(
                                icon = Icons.Rounded.PlayCircleOutline,
                                accent = Nf.Mint,
                                label = "Серий",
                                value = stats.totalEpisodes.toString(),
                                subtitle = epSub,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatBentoTile(
                                icon = Icons.Rounded.Star,
                                accent = Nf.Amber,
                                label = "Средняя оценка",
                                value = scoreValue,
                                subtitle = scoreSub,
                                modifier = Modifier.weight(1f),
                            )
                            StatBentoTile(
                                icon = Icons.Rounded.CheckCircle,
                                accent = statusColor(ListStatus.Completed),
                                label = "Досмотрено",
                                value = stats.completedTitles.toString(),
                                subtitle = doneSub,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // Status distribution bar
                        Spacer(Modifier.height(14.dp))
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Nf.Ink)
                                .border(1.dp, Nf.Line, RoundedCornerShape(20.dp)).padding(16.dp),
                        ) {
                            Text("РАСПРЕДЕЛЕНИЕ ПО СПИСКАМ", style = Type.Overline, color = Nf.TextMute)
                            Spacer(Modifier.height(12.dp))
                            val nonZero = ListOrder.mapNotNull { st ->
                                val c = stats.byStatus[st] ?: 0
                                if (c > 0) st to c else null
                            }
                            Row(
                                Modifier.fillMaxWidth().height(9.dp).clip(CircleShape),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                nonZero.forEach { (st, count) ->
                                    Box(
                                        Modifier.weight(count.toFloat()).fillMaxHeight()
                                            .clip(CircleShape).background(statusColor(st)),
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ListOrder.forEach { st ->
                                    val count = stats.byStatus[st] ?: 0
                                    if (count > 0) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor(st)))
                                            Spacer(Modifier.width(6.dp))
                                            Text(st.title, style = Type.Caption, color = Nf.TextDim)
                                            Spacer(Modifier.width(4.dp))
                                            Text("$count", style = Type.Label, color = Nf.Text)
                                        }
                                    }
                                }
                            }
                        }

                        // Personal 1–10 score histogram
                        if (stats.ratedCount > 0) {
                            Spacer(Modifier.height(12.dp))
                            ScoreHistogramCard(stats.scoreHistogram, revealed)
                        }

                        // Top genres
                        if (stats.topGenres.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Nf.Ink)
                                    .border(1.dp, Nf.Line, RoundedCornerShape(20.dp)).padding(16.dp),
                            ) {
                                Text("ЛЮБИМЫЕ ЖАНРЫ", style = Type.Overline, color = Nf.TextMute)
                                Spacer(Modifier.height(10.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    stats.topGenres.forEachIndexed { idx, (genre, count) ->
                                        val top = idx == 0
                                        Row(
                                            Modifier.clip(CircleShape)
                                                .background(if (top) Nf.Violet.copy(alpha = 0.16f) else Nf.Surface)
                                                .border(1.dp, if (top) Nf.Violet.copy(alpha = 0.45f) else Nf.Line, CircleShape)
                                                .padding(horizontal = 12.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(genre, style = Type.Label, color = if (top) Nf.Text else Nf.TextDim)
                                            Spacer(Modifier.width(6.dp))
                                            Text("$count", style = Type.Caption, color = if (top) Nf.Violet else Nf.TextMute)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBentoTile(
    icon: ImageVector,
    accent: Color,
    label: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.13f), Nf.Ink, Nf.Ink)))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(20.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = Type.Caption, color = Nf.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Text(value, style = Type.Display.copy(fontSize = 22.sp, lineHeight = 26.sp), color = Nf.Text, maxLines = 1)
        Text(subtitle, style = Type.Caption, color = Nf.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun ScoreHistogramCard(histogram: Map<Int, Int>, revealed: Boolean) {
    val maxCount = (histogram.values.maxOrNull() ?: 1).coerceAtLeast(1)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Nf.Ink)
            .border(1.dp, Nf.Line, RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        Text("ВАШИ ОЦЕНКИ", style = Type.Overline, color = Nf.TextMute)
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().height(92.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            (1..10).forEach { score ->
                val count = histogram[score] ?: 0
                val targetFraction = if (count == 0) 0f else (count.toFloat() / maxCount).coerceIn(0.14f, 1f)
                val fraction by animateFloatAsState(
                    targetValue = if (revealed) targetFraction else 0f,
                    animationSpec = tween(durationMillis = 480, delayMillis = score * 25, easing = FluidEasing),
                    label = "bar-$score",
                )
                val isPeak = count > 0 && count == maxCount
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    if (count > 0) {
                        Text(
                            "$count",
                            style = Type.Caption.copy(fontSize = 10.sp),
                            color = if (isPeak) Nf.Amber else Nf.TextMute,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Box(
                        Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (count > 0) {
                            Box(
                                Modifier.fillMaxWidth(0.74f).fillMaxHeight(fraction.coerceAtLeast(0.08f))
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                    .background(if (isPeak) Nf.Amber else Nf.Amber.copy(alpha = 0.45f)),
                            )
                        } else {
                            Box(
                                Modifier.fillMaxWidth(0.74f).height(3.dp)
                                    .clip(CircleShape).background(Nf.Line),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "$score",
                        style = Type.Caption.copy(fontSize = 11.sp),
                        color = if (isPeak) Nf.Text else Nf.TextMute,
                    )
                }
            }
        }
    }
}
