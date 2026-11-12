@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.neifur.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.neifur.AnimeStub
import app.neifur.data.shikimori.kindLabel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import java.util.Locale

val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

val PosterShape = RoundedCornerShape(18.dp)

/** Shared-element hook for posters; a no-op outside a navigation transition. */
@Composable
fun Modifier.sharedPoster(key: String, shape: Shape = PosterShape): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val anim = LocalNavAnimScope.current ?: return this
    return with(shared) {
        this@sharedPoster.sharedElement(
            rememberSharedContentState(key), anim,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.86f, stiffness = 320f) },
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}

/** Tactile press feedback: the element sinks slightly under the finger and springs back. */
fun Modifier.pressable(scale: Float = 0.96f, enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) scale else 1f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "press",
    )
    graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

fun Modifier.shimmer(): Modifier = composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    background(
        Brush.linearGradient(
            listOf(Nf.Surface, Nf.SurfaceHi, Nf.Surface),
            start = Offset(-600f + 1800f * x, 0f), end = Offset(1800f * x, 400f),
        )
    )
}

@Composable
fun PosterImage(url: String?, modifier: Modifier = Modifier, cacheKey: String? = url, contentScale: ContentScale = ContentScale.Crop) {
    val ctx = LocalContext.current
    AsyncImage(
        model = remember(url) {
            ImageRequest.Builder(ctx).data(url).memoryCacheKey(cacheKey).placeholderMemoryCacheKey(cacheKey).crossfade(240).build()
        },
        contentDescription = null, contentScale = contentScale,
        modifier = modifier.background(Nf.Surface),
    )
}

fun formatScore(score: Double?): String? = score?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) }

@Composable
fun ScoreBadge(score: Double?, modifier: Modifier = Modifier) {
    val text = formatScore(score) ?: return
    Row(
        modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xB8090910)).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Star, null, tint = Nf.Amber, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, style = Type.Caption, color = Nf.Text)
    }
}

@Composable
fun PosterCard(stub: AnimeStub, origin: String, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = 128.dp) {
    Column(
        (if (width != null) modifier.width(width) else modifier).pressable(onClick = onClick)
    ) {
        Box {
            PosterImage(
                stub.poster,
                Modifier.sharedPoster("poster/$origin/${stub.id}").fillMaxWidth().aspectRatio(0.7f).clip(PosterShape),
            )
            ScoreBadge(stub.score, Modifier.align(Alignment.TopStart).padding(7.dp))
        }
        Spacer(Modifier.height(9.dp))
        Text(stub.title, style = Type.Label, color = Nf.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(stub.year?.toString(), kindLabel(stub.kind).takeIf { it.isNotBlank() }).joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, style = Type.Caption, color = Nf.TextMute, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun PosterCardSkeleton(modifier: Modifier = Modifier, width: Dp? = 128.dp) {
    Column(if (width != null) modifier.width(width) else modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f).clip(PosterShape).shimmer())
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(0.85f).height(11.dp).clip(CircleShape).shimmer())
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.5f).height(9.dp).clip(CircleShape).shimmer())
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Type.Section, color = Nf.Text, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun Pill(
    text: String, modifier: Modifier = Modifier, selected: Boolean = false, icon: ImageVector? = null,
    tint: Color = LocalAccent.current, onClick: (() -> Unit)? = null,
) {
    val bg = if (selected) tint.copy(alpha = 0.18f) else Nf.Surface
    val fg = if (selected) tint else Nf.TextDim
    Row(
        modifier.clip(CircleShape).background(bg)
            .border(1.dp, if (selected) tint.copy(alpha = 0.45f) else Nf.Line, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
        Text(text, style = Type.Label, color = fg, maxLines = 1)
    }
}

@Composable
fun Tag(text: String, color: Color = Nf.TextDim, modifier: Modifier = Modifier) {
    Text(
        text, style = Type.Caption, color = color,
        modifier = modifier.clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
fun EmptyState(
    title: String, subtitle: String? = null, icon: ImageVector = Icons.Rounded.CloudOff,
    modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(PaddingValues(horizontal = 36.dp, vertical = 28.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(Nf.Surface), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Nf.TextDim, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = Type.Title, color = Nf.Text, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = Type.Body, color = Nf.TextDim, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.clip(CircleShape).background(Nf.Text).pressable(onClick = onAction).padding(horizontal = 20.dp, vertical = 11.dp)
            ) { Text(action, style = Type.Label, color = Nf.Ink) }
        }
    }
}

@Composable
fun ScrimBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(Nf.Ink)) { content() }
}
