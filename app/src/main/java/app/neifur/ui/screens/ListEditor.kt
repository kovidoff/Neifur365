@file:OptIn(ExperimentalMaterial3Api::class)

package app.neifur.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Remove
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
import app.neifur.data.a365.ListRecord
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.scoreLabel
import app.neifur.ui.Nf
import app.neifur.ui.Type
import app.neifur.ui.pressable

/** One colour per list, used on chips, bookmarks and the editor. */
fun statusColor(s: ListStatus?): Color = when (s) {
    ListStatus.Watching -> Nf.Mint
    ListStatus.Planned -> Nf.Violet
    ListStatus.Completed -> Color(0xFF6FB7FF)
    ListStatus.OnHold -> Nf.Amber
    ListStatus.Dropped -> Nf.Red
    null -> Nf.TextMute
}

fun statusIcon(s: ListStatus?): ImageVector = when (s) {
    ListStatus.Watching -> Icons.Rounded.PlayCircle
    ListStatus.Planned -> Icons.Rounded.Event
    ListStatus.Completed -> Icons.Rounded.CheckCircle
    ListStatus.OnHold -> Icons.Rounded.PauseCircle
    ListStatus.Dropped -> Icons.Rounded.DoNotDisturbOn
    null -> Icons.Rounded.Event
}

/** The order lists are shown in: what you're watching first, as on the site. */
val ListOrder = listOf(ListStatus.Watching, ListStatus.Planned, ListStatus.Completed, ListStatus.OnHold, ListStatus.Dropped)

/**
 * A title's place in the viewer's Anime 365 lists: which list, how many episodes are watched, the score.
 * Every change is applied at once (and synced with the site); there is no "save".
 */
@Composable
fun ListEditor(
    title: String, record: ListRecord?, total: Int?, aired: Int?, isMovie: Boolean,
    onDismiss: () -> Unit, onChange: (ListStatus?, Int, Int?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val status = record?.status
    val watched = record?.watched ?: 0
    val score = record?.score
    // Episodes can't go past what exists: the full count, or what has aired for an ongoing title.
    val cap = total ?: aired
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nf.Surface, contentColor = Nf.Text,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(40.dp, 4.dp).clip(CircleShape).background(Nf.Line)) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp)) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                Text(if (status == null) "Добавить в список" else "Мой список", style = Type.Display, color = Nf.Text)
                Text(title, style = Type.Caption, color = Nf.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }

            // Lists as a 2-column grid of tiles; the current one is filled with its colour.
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListOrder.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { s ->
                            StatusTile(s, s == status, Modifier.weight(1f)) {
                                if (s == status) return@StatusTile
                                // Finishing a title fills the counter; starting one keeps it.
                                val w = if (s == ListStatus.Completed && cap != null && !isMovie) cap else if (s == ListStatus.Completed && isMovie) 1 else watched
                                onChange(s, w, score)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            AnimatedVisibility(status != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    if (!isMovie) {
                        Label("Просмотрено серий")
                        Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                            Stepper(Icons.Rounded.Remove, "Меньше", enabled = watched > 0) { onChange(status, watched - 1, score) }
                            // "3 из 13" on one line: the count reads as a number, not a lone glyph.
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                                Text(
                                    "$watched", style = Type.Title.copy(fontSize = 30.sp, lineHeight = 34.sp),
                                    color = statusColor(status), modifier = Modifier.alignByBaseline(),
                                )
                                Text(
                                    when {
                                        total != null -> "из $total"
                                        aired != null -> "из $aired вышедших"
                                        else -> "из ?"
                                    },
                                    style = Type.Title.copy(fontSize = 16.sp), color = Nf.TextMute,
                                    modifier = Modifier.alignByBaseline().padding(start = 8.dp),
                                )
                            }
                            Stepper(Icons.Rounded.Add, "Больше", enabled = cap == null || watched < cap) {
                                val next = watched + 1
                                // The last episode of a finished title completes it.
                                val done = total != null && next >= total && status != ListStatus.Completed
                                onChange(if (done) ListStatus.Completed else status, next, score)
                            }
                        }
                    }

                    Label("Оценка")
                    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = ((score ?: 8) - 3).coerceAtLeast(0))
                    LazyRow(state = scroll, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items((1..10).toList()) { n ->
                            val on = n == score
                            val bg by animateColorAsState(if (on) Nf.Amber.copy(alpha = 0.2f) else Nf.SurfaceHi, label = "sc")
                            Box(
                                Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                                    .border(1.dp, if (on) Nf.Amber.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(14.dp))
                                    .pressable(0.9f) { onChange(status, watched, if (on) null else n) },
                                contentAlignment = Alignment.Center,
                            ) { Text("$n", style = Type.Title, color = if (on) Nf.Amber else Nf.Text) }
                        }
                    }
                    Text(
                        if (score != null) "★ $score — ${scoreLabel(score)}" else "Нажмите, чтобы оценить",
                        style = Type.Label, color = if (score != null) Nf.Amber else Nf.TextMute,
                        modifier = Modifier.padding(horizontal = 22.dp).padding(top = 10.dp),
                    )

                    Row(
                        Modifier.padding(horizontal = 16.dp).padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .clickable { onChange(null, 0, null); onDismiss() }.padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, tint = Nf.Red, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Убрать из списков", style = Type.Title.copy(fontSize = 15.sp), color = Nf.Red)
                    }
                }
            }
            Text(
                "Синхронизируется с Anime 365", style = Type.Caption, color = Nf.TextMute,
                modifier = Modifier.padding(horizontal = 22.dp).padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun StatusTile(s: ListStatus, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = statusColor(s)
    val bg by animateColorAsState(if (on) c.copy(alpha = 0.18f) else Nf.SurfaceHi, label = "st")
    val br by animateColorAsState(if (on) c.copy(alpha = 0.65f) else Color.Transparent, label = "stb")
    Row(
        modifier.height(54.dp).clip(RoundedCornerShape(16.dp)).background(bg).border(1.dp, br, RoundedCornerShape(16.dp))
            .pressable(0.96f, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(statusIcon(s), null, tint = if (on) c else Nf.TextDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(s.title, style = Type.Label.copy(fontSize = 14.sp), color = if (on) Nf.Text else Nf.TextDim, maxLines = 1)
    }
}

@Composable
private fun Label(text: String) =
    Text(text.uppercase(), style = Type.Overline, color = Nf.TextMute, modifier = Modifier.padding(start = 22.dp, top = 22.dp, bottom = 10.dp))

@Composable
private fun Stepper(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(if (enabled) Nf.SurfaceHi else Nf.Surface)
            .border(1.dp, Nf.Line, CircleShape).pressable(0.9f, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (enabled) Nf.Text else Nf.TextMute, modifier = Modifier.size(22.dp)) }
}
