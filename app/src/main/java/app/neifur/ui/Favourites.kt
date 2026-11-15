package app.neifur.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.neifur.container
import kotlinx.coroutines.launch

/** The colour of everything "favourite studio": a warm gold that stands apart from the kind colours. */
val FavouriteTint = Color(0xFFFFC56B)

/** The viewer's starred studios (pick keys) and a way to star or unstar one. */
class FavouriteStudios(val keys: Set<String>, val names: List<String>, val toggle: (key: String, name: String) -> Unit) {
    operator fun contains(key: String) = key in keys
}

@Composable
fun rememberFavouriteStudios(): FavouriteStudios {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val list by c.prefs.favouriteStudios.collectAsStateWithLifecycle(emptyList())
    return remember(list) {
        FavouriteStudios(list.map { it.first }.toSet(), list.map { it.second }) { k, n -> scope.launch { c.prefs.toggleFavouriteStudio(k, n) } }
    }
}

/**
 * The star on a studio row: outlined when it's just a studio, gold and filled when it's a favourite. It pops a
 * little when tapped, so the change is felt as well as seen.
 */
@Composable
fun FavouriteStar(on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, idle: Color = Nf.TextMute) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(1f) }
    val tint by animateColorAsState(if (on) FavouriteTint else idle, label = "star")
    Box(
        modifier.size(36.dp).clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggle()
                scope.launch {
                    pop.snapTo(0.7f)
                    pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (on) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            if (on) "Убрать из любимых" else "В любимые",
            tint = tint, modifier = Modifier.size(21.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
        )
    }
}

/** A small overline above a block of a list: "★ ЛЮБИМЫЕ", "ОСТАЛЬНЫЕ". */
@Composable
fun ListOverline(text: String, color: Color, star: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (star) {
            Icon(Icons.Rounded.Star, null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text.uppercase(), style = Type.Overline.copy(fontSize = 10.5.sp), color = color)
    }
}

/** Gold mark for a kind chip that holds a favourite studio. */
@Composable
fun FavouriteDot(modifier: Modifier = Modifier) =
    Box(modifier.padding(start = 6.dp).size(6.dp).clip(CircleShape).background(FavouriteTint))
