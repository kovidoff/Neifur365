package app.neifur.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Broad layout buckets matching Material 3 window width classes. */
enum class ScreenSize { Compact, Medium, Expanded }

/** Derived layout metrics for the current window so phones stay unchanged while tablets use space well. */
data class ScreenLayout(
    val widthDp: Int,
    val heightDp: Int,
    val size: ScreenSize,
    val posterColumns: Int,
    val wideColumns: Int,
    val horizontalPadding: Dp,
    val posterCardWidth: Dp,
) {
    val isTablet: Boolean get() = size != ScreenSize.Compact
    val isExpanded: Boolean get() = size == ScreenSize.Expanded
    val isLandscape: Boolean get() = widthDp > heightDp

    /** Grid span for a wide card inside a [posterColumns]-column LazyVerticalGrid. */
    val wideSpan: Int get() = (posterColumns / wideColumns).coerceAtLeast(1)
}

internal fun screenSizeFor(widthDp: Int): ScreenSize = when {
    widthDp >= 840 -> ScreenSize.Expanded
    widthDp >= 600 -> ScreenSize.Medium
    else -> ScreenSize.Compact
}

/**
 * Even column counts on tablets (>= 600 dp) let wide cards split cleanly into 2 columns inside one grid
 * without nested scrolling: 3 on phones, 4 on medium tablets/foldables, 6 on 10–12" landscape, 8 on ultra-wide.
 */
internal fun posterGridColumns(widthDp: Int): Int = when {
    widthDp >= 1200 -> 8
    widthDp >= 840 -> 6
    widthDp >= 600 -> 4
    else -> 3
}

internal fun wideGridColumns(widthDp: Int): Int = if (widthDp >= 720) 2 else 1

internal fun layoutFor(widthDp: Int, heightDp: Int): ScreenLayout {
    val size = screenSizeFor(widthDp)
    val tablet = size != ScreenSize.Compact
    return ScreenLayout(
        widthDp = widthDp,
        heightDp = heightDp,
        size = size,
        posterColumns = posterGridColumns(widthDp),
        wideColumns = wideGridColumns(widthDp),
        horizontalPadding = if (tablet) 28.dp else 20.dp,
        posterCardWidth = if (tablet) 144.dp else 128.dp,
    )
}

@Composable
fun rememberScreenLayout(): ScreenLayout {
    val cfg = LocalConfiguration.current
    return remember(cfg.screenWidthDp, cfg.screenHeightDp) { layoutFor(cfg.screenWidthDp, cfg.screenHeightDp) }
}
