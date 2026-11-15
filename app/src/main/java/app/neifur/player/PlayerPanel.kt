@file:OptIn(ExperimentalLayoutApi::class)

package app.neifur.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.neifur.data.a365.Episode
import app.neifur.data.a365.Translation
import app.neifur.data.a365.TranslationKind
import app.neifur.ui.Nf
import app.neifur.ui.Type
import app.neifur.ui.pressable
import java.util.Locale
import kotlin.math.abs

/** Side panel pages. Subtitles appear only when the playing translation has a subtitle track to tune. */
enum class PanelTab(val title: String, val icon: ImageVector) {
    Translation("Перевод", Icons.Rounded.Mic),
    Episodes("Серии", Icons.Rounded.VideoLibrary),
    Subs("Субтитры", Icons.Rounded.Subtitles),
}

/** Small cards that pop up above their button: quick choices that don't need the side panel. */
enum class QuickMenu { Quality, Speed }

/** What the player shows about the stream: the translation playing, this episode's alternatives, the qualities. */
data class StreamUi(
    val options: Map<TranslationKind, List<Translation>> = emptyMap(),
    val current: Translation? = null,
    val heights: List<Int> = emptyList(),
    val height: Int = 0,
    /** Subtitles came as a separate file (they can be turned off and restyled), not burned into the picture. */
    val externalSubs: Boolean = false,
) {
    val isSubs: Boolean get() = current?.kind?.isSubs == true
}

fun kindIcon(k: TranslationKind?): ImageVector = when {
    k == TranslationKind.Raw -> Icons.Rounded.MovieFilter
    k?.isSubs == true -> Icons.Rounded.ClosedCaption
    else -> Icons.Rounded.Mic
}

fun kindTint(k: TranslationKind?): Color = when {
    k == TranslationKind.Raw -> Nf.Mint
    k?.isSubs == true -> Nf.Violet
    else -> Nf.Pink
}

private val PanelBg = Color(0xF5121219)
internal val PanelWidth = 420.dp
private val RowOn = Nf.Violet.copy(alpha = 0.16f)

@Composable
fun PlayerPanel(
    tab: PanelTab?, tabs: List<PanelTab>, player: MpvPlayer, stream: StreamUi,
    episodes: List<Episode>, index: Int, watched: Set<Int>,
    onTab: (PanelTab) -> Unit, onClose: () -> Unit,
    onSelectTrack: (String, Track?) -> Unit, onSubScale: (Double) -> Unit,
    onTranslation: (Translation) -> Unit, onEpisode: (Int) -> Unit,
) {
    // Scrim: the video stays visible but dims, and a tap on it closes the panel.
    val scrim by animateFloatAsState(if (tab != null) 0.45f else 0f, tween(260), label = "scrim")
    if (scrim > 0.01f) Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose)
    )
    var shown by remember { mutableStateOf(PanelTab.Translation) }
    if (tab != null) shown = tab
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            tab != null,
            enter = slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(160)),
            exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(200)),
        ) {
            // A floating card: only the screen edge it sits against is inset.
            Column(
                Modifier.fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
                    .padding(top = 12.dp, bottom = 12.dp, end = 12.dp)
                    .width(PanelWidth)
                    .clip(RoundedCornerShape(28.dp)).background(PanelBg).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(28.dp))
                    .clickable(remember { MutableInteractionSource() }, null) {}
            ) {
                if (tabs.size > 1) Tabs(tabs, shown, onTab, Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp))
                Spacer(Modifier.height(10.dp))
                AnimatedContent(
                    shown, label = "tab", modifier = Modifier.weight(1f),
                    transitionSpec = {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInHorizontally(tween(260)) { it / 5 * dir } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(200)) { -it / 5 * dir } + fadeOut(tween(120)))
                    },
                ) { t ->
                    when (t) {
                        PanelTab.Translation -> TranslationTab(stream, onTranslation)
                        PanelTab.Episodes -> EpisodesTab(episodes, index, watched, onEpisode)
                        PanelTab.Subs -> SubsTab(player, onSelectTrack, onSubScale)
                    }
                }
            }
        }
    }
}

/** Segmented tabs with an indicator that slides between them. */
@Composable
private fun Tabs(tabs: List<PanelTab>, current: PanelTab, onTab: (PanelTab) -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    var w by remember { mutableIntStateOf(0) }
    val inner = 5.dp
    val segment = with(density) { ((w - (inner * 2).roundToPx()) / tabs.size).toDp() }
    val x by animateDpAsState(segment * tabs.indexOf(current).coerceAtLeast(0), spring(dampingRatio = 0.8f, stiffness = 500f), label = "tabx")
    Box(
        modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x0FFFFFFF))
            .onSizeChanged { w = it.width }.padding(inner)
    ) {
        if (w > 0) Box(
            Modifier.offset(x = x).width(segment).fillMaxHeight().clip(RoundedCornerShape(14.dp))
                .background(Nf.Violet.copy(alpha = 0.26f)).border(1.dp, Nf.Violet.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { t ->
                val on = t == current
                val c by animateColorAsState(if (on) Color.White else Color(0x8CFFFFFF), label = "tabc")
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).clickable { onTab(t) }.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(t.icon, null, tint = c, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(t.title, style = Type.Label.copy(fontSize = 13.5.sp), color = c, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * Every translation of this episode, filtered by kind (voice-overs, subtitles, original…). Picking one restarts
 * the episode in it at the same moment, and it becomes the pick for the next episodes too.
 */
@Composable
private fun TranslationTab(stream: StreamUi, onPick: (Translation) -> Unit) {
    val kinds = stream.options.keys.toList()
    var kind by remember(stream.options) { mutableStateOf(stream.current?.kind?.takeIf { it in kinds } ?: kinds.firstOrNull()) }
    Column(Modifier.fillMaxSize()) {
        if (kinds.size > 1) LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            items(kinds) { k ->
                val on = k == kind
                val tint = kindTint(k)
                val bg by animateColorAsState(if (on) tint.copy(alpha = 0.22f) else Color(0x0FFFFFFF), label = "kc")
                Row(
                    Modifier.clip(CircleShape).background(bg).border(1.dp, if (on) tint.copy(alpha = 0.5f) else Color.Transparent, CircleShape)
                        .clickable { kind = k }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(kindIcon(k), null, tint = if (on) tint else Color(0x99FFFFFF), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${k.short} · ${stream.options[k]?.size ?: 0}", style = Type.Label, color = if (on) Color.White else Color(0xB3FFFFFF))
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (stream.options.isEmpty()) item { Hint("Список переводов загружается…") }
            items(stream.options[kind].orEmpty(), key = { "t${it.id}" }) { t ->
                val on = t.pickKey == stream.current?.pickKey
                OptionRow(selected = on, onClick = { if (!on) onPick(t) }) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.author, style = Type.Title.copy(fontSize = 15.sp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (on) Badge("Сейчас", kindTint(t.kind))
                        }
                        val people = t.authorsList.joinToString(", ").takeIf { it.isNotBlank() && it != t.author }
                        Text(
                            listOfNotNull(t.qualityLabel.takeIf { it.isNotBlank() }, people).joinToString(" · "),
                            style = Type.Caption, color = Color(0x80FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The title's episodes: jump anywhere without leaving the player. */
@Composable
private fun EpisodesTab(episodes: List<Episode>, index: Int, watched: Set<Int>, onEpisode: (Int) -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (index - 2).coerceAtLeast(0))
    LazyColumn(state = list, contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(episodes, key = { _, e -> e.id }) { i, e ->
            val now = i == index
            val seen = e.number in watched
            Row(
                Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (now) RowOn else Color.Transparent).clickable(enabled = !now) { onEpisode(i) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (now) Nf.Violet.copy(alpha = 0.3f) else if (seen) Nf.Mint.copy(alpha = 0.14f) else Color(0x14FFFFFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        now -> Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        seen -> Icon(Icons.Rounded.Check, null, tint = Nf.Mint, modifier = Modifier.size(18.dp))
                        else -> Text(if (e.number > 0) e.number.toString() else "•", style = Type.Title.copy(fontSize = if (e.number >= 1000) 12.sp else 15.sp), color = Color.White)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.label, style = Type.Title.copy(fontSize = 15.sp), color = Color.White, maxLines = 1)
                    val sub = when { now -> "Сейчас смотрите"; seen -> "Просмотрено"; else -> e.episodeTitle?.takeIf { it.isNotBlank() } }
                    if (sub != null) Text(sub, style = Type.Caption, color = if (now) Nf.Violet else Color(0x80FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SubsTab(player: MpvPlayer, onSelect: (String, Track?) -> Unit, onSubScale: (Double) -> Unit) {
    val subs = remember(player.tracks) { player.tracks.filter { it.type == "sub" }.sortedBy { TrackNames.sortKey(it) } }
    LazyColumn(contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "off") {
            OptionRow(selected = subs.none { it.selected }, onClick = { onSelect("sub", null) }) {
                Icon(Icons.Rounded.SubtitlesOff, null, tint = Color(0x99FFFFFF), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Без субтитров", style = Type.Title.copy(fontSize = 15.sp), color = Color.White, modifier = Modifier.weight(1f))
            }
        }
        items(subs, key = { "s${it.id}" }) { t -> TrackRow(t) { onSelect("sub", t) } }

        item(key = "look") {
            Column(Modifier.padding(horizontal = 8.dp).padding(top = 18.dp)) {
                SectionLabel("Размер")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.8, 1.0, 1.25, 1.5).forEachIndexed { i, v ->
                        val on = abs(player.subScale - v) < 0.01
                        Box(
                            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (on) RowOn else Color(0x0FFFFFFF))
                                .border(1.dp, if (on) Nf.Violet.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(14.dp))
                                .clickable { onSubScale(v) },
                            contentAlignment = Alignment.Center,
                        ) { Text("Аа", style = Type.Title.copy(fontSize = (13 + i * 3).sp), color = if (on) Nf.Violet else Color.White) }
                    }
                }
                Spacer(Modifier.height(18.dp))
                SectionLabel("Синхронизация")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton(Icons.Rounded.Remove, "Раньше на 0,1 с") { player.changeSubDelay(player.subDelay - 0.1) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        val d = player.subDelay
                        Text(
                            if (d == 0.0) "0,0 с" else String.format(Locale("ru"), "%+.1f с", d),
                            style = Type.Title, color = if (d == 0.0) Color.White else Nf.Amber,
                        )
                        Text(
                            when { d > 0 -> "субтитры позже"; d < 0 -> "субтитры раньше"; else -> "как в файле" },
                            style = Type.Caption, color = Color(0x80FFFFFF),
                        )
                    }
                    StepButton(Icons.Rounded.Add, "Позже на 0,1 с") { player.changeSubDelay(player.subDelay + 0.1) }
                }
                AnimatedVisibility(player.subDelay != 0.0) {
                    Text(
                        "Сбросить", style = Type.Label, color = Nf.Violet,
                        modifier = Modifier.padding(top = 10.dp).clip(CircleShape).clickable { player.changeSubDelay(0.0) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

// --- quick menus -------------------------------------------------------------------------------------

/**
 * Quality or speed: a small card rising from the bottom-right corner, where their buttons are.
 * A tap anywhere else closes it.
 */
@Composable
fun QuickMenuHost(menu: QuickMenu?, player: MpvPlayer, stream: StreamUi, onHeight: (Int) -> Unit, onClose: () -> Unit) {
    if (menu != null) Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
    Box(Modifier.fillMaxSize().displayCutoutPadding().padding(end = 22.dp, bottom = 70.dp), contentAlignment = Alignment.BottomEnd) {
        AnimatedVisibility(
            menu != null,
            enter = scaleIn(spring(dampingRatio = 0.8f, stiffness = 600f), 0.85f, TransformOrigin(1f, 1f)) + fadeIn(tween(120)),
            exit = scaleOut(tween(140), 0.9f, TransformOrigin(1f, 1f)) + fadeOut(tween(120)),
        ) {
            var shown by remember { mutableStateOf(QuickMenu.Quality) }
            if (menu != null) shown = menu
            Column(
                Modifier.width(236.dp).clip(RoundedCornerShape(22.dp)).background(PanelBg).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(22.dp))
                    .clickable(remember { MutableInteractionSource() }, null) {}.padding(8.dp)
            ) {
                when (shown) {
                    QuickMenu.Quality -> {
                        MenuTitle("Качество")
                        stream.heights.forEach { h ->
                            MenuRow(
                                "${h}p", when { h >= 2160 -> "4K"; h >= 1080 -> "Full HD"; h >= 720 -> "HD"; else -> "Экономит трафик" },
                                selected = h == stream.height,
                            ) { if (h != stream.height) onHeight(h); onClose() }
                        }
                        if (stream.heights.size <= 1) Hint("У этого перевода одно качество", padding = 10.dp)
                    }
                    QuickMenu.Speed -> {
                        MenuTitle("Скорость")
                        FlowRow(
                            Modifier.padding(horizontal = 6.dp, vertical = 4.dp), maxItemsInEachRow = 4,
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0).forEach { v ->
                                val on = abs(player.speed - v) < 0.01
                                Box(
                                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                                        .background(if (on) RowOn else Color(0x0FFFFFFF))
                                        .border(1.dp, if (on) Nf.Violet.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(12.dp))
                                        .clickable { player.changeSpeed(v) },
                                    contentAlignment = Alignment.Center,
                                ) { Text(speedLabel(v), style = Type.Label, color = if (on) Nf.Violet else Color.White) }
                            }
                        }
                        Hint("Удерживайте палец на видео — временно 2×", padding = 10.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuTitle(text: String) =
    Text(text.uppercase(), style = Type.Overline, color = Color(0x80FFFFFF), modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 6.dp))

@Composable
private fun MenuRow(title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) RowOn else Color.Transparent, label = "mr")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Title.copy(fontSize = 15.sp), color = if (selected) Nf.Violet else Color.White)
            Text(hint, style = Type.Caption, color = Color(0x80FFFFFF))
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = Nf.Violet, modifier = Modifier.size(18.dp))
    }
}

/** One audio or subtitle track: language first and large, the details that tell twins apart underneath. */
@Composable
private fun TrackRow(t: Track, onClick: () -> Unit) {
    val info = t.info
    OptionRow(selected = t.selected, onClick = onClick) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(info.name, style = Type.Title.copy(fontSize = 15.sp), color = if (t.selected) Color.White else Color(0xE6FFFFFF), maxLines = 1)
                if (info.kind == SubKind.Signs) Badge("Надписи", Nf.Amber)
                if (info.kind == SubKind.Captions) Badge("Для слабослышащих", Nf.Mint)
            }
            val details = listOfNotNull(info.detail, t.codecLabel, t.channelLabel).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = Type.Caption, color = Color(0x80FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun OptionRow(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val bg by animateColorAsState(if (selected) RowOn else Color.Transparent, tween(220), label = "row")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(16.dp)).background(bg).clickable(onClick = onClick)
            .padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        Spacer(Modifier.width(10.dp))
        Radio(selected)
    }
}

@Composable
private fun Radio(on: Boolean) {
    val dot by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "radio")
    val ring by animateColorAsState(if (on) Nf.Violet else Color(0x4DFFFFFF), label = "ring")
    Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).graphicsLayer { scaleX = dot; scaleY = dot }.clip(CircleShape).background(Nf.Violet))
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, style = Type.Caption.copy(fontSize = 10.5.sp), color = color, maxLines = 1,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SectionLabel(text: String) =
    Text(text.uppercase(), style = Type.Overline, color = Color(0x80FFFFFF), modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))

@Composable
private fun Hint(text: String, padding: androidx.compose.ui.unit.Dp = 14.dp) =
    Text(text, style = Type.Caption, color = Color(0x80FFFFFF), modifier = Modifier.padding(horizontal = padding, vertical = 10.dp))

@Composable
private fun StepButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0x14FFFFFF)).pressable(0.88f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
