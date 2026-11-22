package app.neifur.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.ListedTitle
import app.neifur.data.shikimori.Anime
import app.neifur.nfViewModel
import app.neifur.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One scheduled ongoing release, resolved into the viewer's local timezone and matched against their lists. */
data class ScheduleItem(
    val malId: String,
    val seriesId: Long?,
    val title: String,
    val poster: String?,
    val nextEpisode: Int,
    val totalEpisodes: Int,
    val score: Double?,
    val year: Int?,
    val kind: String?,
    val airAt: ZonedDateTime,
    val status: ListStatus?,
) {
    val isMine: Boolean get() = status == ListStatus.Watching || status == ListStatus.Planned
    fun stub(): AnimeStub = AnimeStub(
        id = seriesId?.toString() ?: "mal:$malId",
        title = title,
        poster = poster,
        kind = kind,
        year = year,
        score = score,
    )
}

/** Groups ongoing Shikimori titles into the 7-day window [today..today+6], sorted by release time inside each day. */
internal fun buildWeeklySchedule(
    animes: List<Anime>,
    byMalId: Map<Long, ListedTitle>,
    zone: ZoneId,
    today: LocalDate,
): Map<LocalDate, List<ScheduleItem>> {
    val lastDay = today.plusDays(6)
    val grouped = LinkedHashMap<LocalDate, MutableList<ScheduleItem>>()
    for (d in 0L..6L) grouped[today.plusDays(d)] = mutableListOf()

    animes.forEach { a ->
        val iso = a.nextEpisodeAt ?: return@forEach
        val air = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone) }.getOrNull() ?: return@forEach
        val day = air.toLocalDate()
        if (day.isBefore(today) || day.isAfter(lastDay)) return@forEach
        val listed = a.id.toLongOrNull()?.let { byMalId[it] }
        val nextEp = (a.episodesAired + 1).coerceAtLeast(1)
        grouped[day]?.add(
            ScheduleItem(
                malId = a.id,
                seriesId = listed?.entry?.seriesId,
                title = listed?.series?.title ?: a.title,
                poster = listed?.series?.posterUrlSmall ?: listed?.series?.posterUrl ?: a.poster?.card,
                nextEpisode = nextEp,
                totalEpisodes = listed?.total ?: a.episodes,
                score = listed?.series?.score ?: a.score,
                year = listed?.series?.year?.takeIf { it > 0 } ?: a.year,
                kind = listed?.series?.type ?: a.kind,
                airAt = air,
                status = listed?.status,
            )
        )
    }
    return grouped.mapValues { (_, list) ->
        list.sortedWith(compareBy<ScheduleItem> { it.airAt.toInstant() }.thenByDescending { it.isMine })
    }
}

/** Human-friendly relative countdown for a scheduled release ("через 2 ч 15 мин", "вышла 30 мин назад"). */
internal fun formatRelativeAir(airAt: ZonedDateTime, now: ZonedDateTime): Pair<String, Boolean> {
    val mins = Duration.between(now, airAt).toMinutes()
    return when {
        mins <= -60 -> "вышла ${-mins / 60} ч назад" to true
        mins < 0 -> "вышла ${(-mins).coerceAtLeast(1)} мин назад" to true
        mins == 0L -> "выходит сейчас" to true
        mins < 60 -> "через $mins мин" to false
        mins < 24 * 60 -> {
            val h = mins / 60
            val m = mins % 60
            (if (m > 0) "через $h ч $m мин" else "через $h ч") to false
        }
        else -> {
            val d = (mins + 720) / (24 * 60)
            "через $d ${plural(d.toInt(), "день", "дня", "дней")}" to false
        }
    }
}

internal fun shortWeekday(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "Пн"
    DayOfWeek.TUESDAY -> "Вт"
    DayOfWeek.WEDNESDAY -> "Ср"
    DayOfWeek.THURSDAY -> "Чт"
    DayOfWeek.FRIDAY -> "Пт"
    DayOfWeek.SATURDAY -> "Сб"
    DayOfWeek.SUNDAY -> "Вс"
}

private val RU = Locale.forLanguageTag("ru")
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FMT = DateTimeFormatter.ofPattern("d MMMM", RU)
private val FULL_DAY_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)

class ScheduleVm(private val c: Container) : ViewModel() {
    val lists = c.lists.state
    val state = MutableStateFlow<Load<List<Anime>>>(Load.Loading)
    var onlyMine by mutableStateOf(false)
    var selectedIdx by mutableIntStateOf(0)

    init { load() }

    fun load() {
        state.value = Load.Loading
        viewModelScope.launch {
            state.value = runCatching {
                val extra = c.lists.state.value.titles.values
                    .filter { it.airing && (it.status == ListStatus.Watching || it.status == ListStatus.Planned) }
                    .mapNotNull { it.series?.myAnimeListId?.takeIf { id -> id > 0 } }
                c.shikimori.ongoingSchedule(extra)
            }.fold({ Load.Ready(it) }, { Load.Error(it.userMessage()) })
        }
    }
}

@Composable
fun ScheduleScreen(open: OpenAnime, onBack: () -> Unit) {
    val vm = nfViewModel { ScheduleVm(it) }
    val load by vm.state.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val days = remember(today) { (0L..6L).map { today.plusDays(it) } }
    val selectedIdx = vm.selectedIdx.coerceIn(0, days.lastIndex)

    val byMalId = remember(lists) {
        lists.titles.values.mapNotNull { t ->
            val mal = t.series?.myAnimeListId?.takeIf { it > 0 } ?: return@mapNotNull null
            mal to t
        }.toMap()
    }
    val raw = (load as? Load.Ready)?.value
    val schedule = remember(raw, byMalId, today) {
        raw?.let { buildWeeklySchedule(it, byMalId, zone, today) } ?: emptyMap()
    }
    val hasAnyMine = remember(schedule) { schedule.values.any { list -> list.any { it.isMine } } }

    Column(Modifier.fillMaxSize().background(Nf.Ink).statusBarsPadding().navigationBarsPadding()) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
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
                Text("Расписание", style = Type.Display.copy(fontSize = 22.sp), color = Nf.Text, maxLines = 1)
                Text("Онгоинги на неделю по вашему времени", style = Type.Caption, color = Nf.TextMute, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
            if (hasAnyMine) {
                val on = vm.onlyMine
                val bg by animateColorAsState(if (on) Nf.Mint.copy(alpha = 0.16f) else Nf.Surface, label = "mbg")
                val br by animateColorAsState(if (on) Nf.Mint.copy(alpha = 0.5f) else Nf.Line, label = "mbr")
                Row(
                    Modifier.height(36.dp).clip(CircleShape).background(bg).border(1.dp, br, CircleShape)
                        .pressable(0.92f) { vm.onlyMine = !vm.onlyMine }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Bookmark, null, tint = if (on) Nf.Mint else Nf.TextDim, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Мои", style = Type.Label, color = if (on) Nf.Mint else Nf.TextDim)
                }
            }
        }

        // 7-day calendar strip
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 14.dp),
        ) {
            itemsIndexed(days) { i, day ->
                val on = i == selectedIdx
                val dayItems = schedule[day].orEmpty().let { l -> if (vm.onlyMine) l.filter { it.isMine } else l }
                val hasMine = dayItems.any { it.isMine }
                val bg by animateColorAsState(if (on) Nf.Violet.copy(alpha = 0.2f) else Nf.Surface, label = "dbg")
                val br by animateColorAsState(if (on) Nf.Violet.copy(alpha = 0.6f) else Nf.Line, label = "dbr")
                Column(
                    Modifier.width(54.dp).height(68.dp).clip(RoundedCornerShape(18.dp)).background(bg)
                        .border(1.dp, br, RoundedCornerShape(18.dp)).pressable(0.92f) { vm.selectedIdx = i },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (i == 0) "Сег" else shortWeekday(day.dayOfWeek),
                        style = Type.Caption.copy(fontSize = 11.sp),
                        color = if (on) Nf.Violet else Nf.TextMute,
                    )
                    Text(
                        day.dayOfMonth.toString(),
                        style = Type.Title.copy(fontSize = 17.sp),
                        color = if (on) Nf.Text else Nf.TextDim,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier.size(5.dp).clip(CircleShape).background(
                            when {
                                hasMine -> Nf.Mint
                                dayItems.isNotEmpty() -> if (on) Nf.Violet else Nf.TextMute.copy(alpha = 0.45f)
                                else -> Color.Transparent
                            }
                        )
                    )
                }
            }
        }

        when (val f = load) {
            Load.Loading -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(5) { Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(20.dp)).shimmer()) }
            }
            is Load.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("Не получилось загрузить расписание", f.message, Icons.Rounded.WifiOff, action = "Повторить", onAction = vm::load)
            }
            is Load.Ready -> {
                val now = remember(selectedIdx) { ZonedDateTime.now(zone) }
                AnimatedContent(
                    targetState = selectedIdx,
                    label = "day",
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { dir * it / 6 } + fadeIn(tween(180))) togetherWith
                            (slideOutHorizontally(tween(180)) { -dir * it / 8 } + fadeOut(tween(120)))
                    },
                ) { idx ->
                    val day = days[idx]
                    val dayList = schedule[day].orEmpty().let { l -> if (vm.onlyMine) l.filter { it.isMine } else l }
                    val heading = when (idx) {
                        0 -> "Сегодня, ${day.format(DATE_FMT)}"
                        1 -> "Завтра, ${day.format(DATE_FMT)}"
                        else -> day.format(FULL_DAY_FMT).replaceFirstChar { it.uppercase() }
                    }
                    if (dayList.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize().padding(top = 56.dp, start = 32.dp, end = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(62.dp).clip(CircleShape).background(Nf.Surface), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.EventBusy, null, tint = Nf.TextMute, modifier = Modifier.size(28.dp))
                            }
                            Text(heading, style = Type.Title, color = Nf.Text, modifier = Modifier.padding(top = 16.dp))
                            Text(
                                if (vm.onlyMine) "В этот день нет серий из ваших списков" else "На этот день нет запланированных серий",
                                style = Type.Body, color = Nf.TextDim, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            item(key = "day-head") {
                                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(heading, style = Type.Section, color = Nf.Text, modifier = Modifier.weight(1f))
                                    Text(
                                        "${dayList.size} ${plural(dayList.size, "серия", "серии", "серий")}",
                                        style = Type.Caption, color = Nf.TextMute,
                                    )
                                }
                            }
                            items(dayList, key = { it.malId }) { item ->
                                ScheduleCard(item, now) { open(item.stub(), "schedule") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleCard(item: ScheduleItem, now: ZonedDateTime, onClick: () -> Unit) {
    val border = when (item.status) {
        ListStatus.Watching -> Nf.Mint.copy(alpha = 0.35f)
        ListStatus.Planned -> Nf.Violet.copy(alpha = 0.3f)
        else -> Color(0x0DFFFFFF)
    }
    val (rel, aired) = formatRelativeAir(item.airAt, now)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Nf.Surface)
            .border(1.dp, border, RoundedCornerShape(20.dp)).pressable(0.98f, onClick = onClick)
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterImage(item.poster, Modifier.width(56.dp).aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val epLabel = if (item.totalEpisodes > 0) "${item.nextEpisode} из ${item.totalEpisodes} эп." else "${item.nextEpisode} серия"
                Text(epLabel, style = Type.Caption, color = Nf.TextDim, maxLines = 1)
                if (item.status == ListStatus.Watching || item.status == ListStatus.Planned) {
                    val c = statusColor(item.status)
                    Text(
                        item.status.title, style = Type.Caption.copy(fontSize = 10.5.sp), color = c, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(rel, style = Type.Caption, color = if (aired) Nf.Mint else Nf.Violet, maxLines = 1)
                formatScore(item.score)?.let { s ->
                    Text("  ·  ", style = Type.Caption, color = Nf.TextMute)
                    Icon(Icons.Rounded.Star, null, tint = Nf.Amber, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(s, style = Type.Caption, color = Nf.TextDim)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier.clip(RoundedCornerShape(13.dp)).background(Nf.Ink).border(1.dp, Nf.Line, RoundedCornerShape(13.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(item.airAt.format(TIME_FMT), style = Type.Title.copy(fontSize = 14.5.sp), color = if (aired) Nf.Mint else Nf.Text)
        }
    }
}
