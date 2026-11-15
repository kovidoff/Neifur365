@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.neifur

import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.animation.core.animateFloatAsState
import kotlin.math.roundToInt
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.neifur.ui.*
import app.neifur.ui.screens.CatalogScreen
import app.neifur.ui.screens.DetailScreen
import app.neifur.ui.screens.HomeScreen
import app.neifur.ui.screens.LibraryScreen
import app.neifur.ui.screens.SettingsScreen
import app.neifur.ui.screens.WelcomeScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(AColor.TRANSPARENT), SystemBarStyle.dark(AColor.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent { NeifurTheme { AppRoot() } }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Главная", Icons.Rounded.Home),
    Tab("catalog", "Каталог", Icons.Rounded.Explore),
    Tab("library", "Списки", Icons.Rounded.Bookmark),
    Tab("settings", "Настройки", Icons.Rounded.Settings),
)

private fun tabIndex(route: String?) = tabs.indexOfFirst { it.route == route }

typealias OpenAnime = (stub: AnimeStub, origin: String) -> Unit

@Composable
private fun AppRoot() {
    val nav = rememberNavController()
    val container = LocalContext.current.container
    // null while DataStore loads: the screen stays ink instead of flashing Home before the welcome flow.
    val onboarded by container.prefs.onboarded.collectAsStateWithLifecycle(null)
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val open: OpenAnime = { stub, origin ->
        container.remember(stub)
        nav.navigate("detail/${stub.id}?origin=${Uri.encode(origin)}") { launchSingleTop = true }
    }

    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedScope provides this) {
            Box(Modifier.fillMaxSize().background(Nf.Ink)) {
                NavHost(
                    nav, startDestination = "home",
                    // Plain cross-fades of equal length: the shared poster carries the motion, and scaling whole
                    // screens under it fought the poster and read as a stutter.
                    // Between tabs the screens glide in the direction of the tab you picked, like the pill below.
                    enterTransition = {
                        val dir = tabDirection(initialState.destination.route, targetState.destination.route)
                        if (dir != 0) slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { dir * it / 7 } + fadeIn(tween(240, 60))
                        else fadeIn(tween(300, easing = LinearOutSlowInEasing))
                    },
                    exitTransition = {
                        val dir = tabDirection(initialState.destination.route, targetState.destination.route)
                        if (dir != 0) slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { -dir * it / 7 } + fadeOut(tween(160))
                        else fadeOut(tween(300, easing = FastOutLinearInEasing))
                    },
                    popEnterTransition = {
                        val dir = tabDirection(initialState.destination.route, targetState.destination.route)
                        if (dir != 0) slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { dir * it / 7 } + fadeIn(tween(240, 60))
                        else fadeIn(tween(200, easing = LinearOutSlowInEasing))
                    },
                    popExitTransition = {
                        val dir = tabDirection(initialState.destination.route, targetState.destination.route)
                        if (dir != 0) slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { -dir * it / 7 } + fadeOut(tween(160))
                        else fadeOut(tween(200, easing = FastOutLinearInEasing))
                    },
                ) {
                    composable("home") {
                        CompositionLocalProvider(LocalNavAnimScope provides this) {
                            HomeScreen(open, onSearch = { nav.switchTab("catalog") })
                        }
                    }
                    composable("catalog") {
                        CompositionLocalProvider(LocalNavAnimScope provides this) { CatalogScreen(open) }
                    }
                    composable("library") {
                        CompositionLocalProvider(LocalNavAnimScope provides this) {
                            LibraryScreen(open)
                        }
                    }
                    composable("settings") { SettingsScreen() }
                    composable(
                        "detail/{id}?origin={origin}",
                        arguments = listOf(
                            navArgument("id") { type = NavType.StringType },
                            navArgument("origin") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { e ->
                        CompositionLocalProvider(LocalNavAnimScope provides this) {
                            DetailScreen(
                                id = e.arguments?.getString("id").orEmpty(),
                                origin = e.arguments?.getString("origin").orEmpty(),
                                open = open,
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = route == null || tabs.any { it.route == route },
                    enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    BottomBar(route ?: "home") { nav.switchTab(it) }
                }

                // The welcome flow sits over the app, so Home is already loaded underneath when it lifts away.
                AnimatedVisibility(
                    visible = onboarded != true,
                    enter = fadeIn(tween(300)),
                    exit = fadeOut(tween(520, easing = FastOutSlowInEasing)) + scaleOut(tween(620, easing = FastOutSlowInEasing), targetScale = 1.08f),
                ) {
                    // Swallows touches on empty areas, so nothing reaches the app underneath while it is covered.
                    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) {}) {
                        // Once shown, the welcome stays composed through its own exit fade instead of swapping to the placeholder.
                        var welcomed by remember { mutableStateOf(false) }
                        if (onboarded == false) welcomed = true
                        if (welcomed) WelcomeScreen() else Box(Modifier.fillMaxSize().background(Nf.Ink))
                    }
                }
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** +1 when moving to a tab on the right, -1 to the left, 0 when it is not a tab-to-tab move. */
private fun tabDirection(from: String?, to: String?): Int {
    val a = tabIndex(from)
    val b = tabIndex(to)
    return if (a < 0 || b < 0 || a == b) 0 else if (b > a) 1 else -1
}

/**
 * Fixed-width pill. The selected slot grows to fit its label (weights animate), and a single highlight slides
 * between slots on its own spring, so the bar never changes size or shifts sideways.
 */
@Composable
private fun BottomBar(current: String, onSelect: (String) -> Unit) {
    val sel = tabIndex(current).coerceAtLeast(0)
    val view = LocalView.current
    val weights = tabs.indices.map { i ->
        animateFloatAsState(if (i == sel) 2.1f else 1f, spring(dampingRatio = 0.82f, stiffness = 420f), label = "w$i").value
    }
    val pos by animateFloatAsState(sel.toFloat(), spring(dampingRatio = 0.78f, stiffness = 380f), label = "pos")
    BoxWithConstraints(
        Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 14.dp).fillMaxWidth().height(62.dp)
            .shadow(28.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape).background(Color(0xF2151520)).border(1.dp, Color(0x14FFFFFF), CircleShape)
            .padding(6.dp)
    ) {
        val total = constraints.maxWidth.toFloat()
        val sum = weights.sum()
        fun left(i: Int) = weights.take(i).sum() / sum * total
        fun width(i: Int) = weights[i] / sum * total
        val i0 = pos.toInt().coerceIn(0, tabs.lastIndex)
        val i1 = (i0 + 1).coerceAtMost(tabs.lastIndex)
        val f = (pos - i0).coerceIn(0f, 1f)
        val x = left(i0) + (left(i1) - left(i0)) * f
        val w = width(i0) + (width(i1) - width(i0)) * f
        val density = LocalDensity.current
        Box(
            Modifier.offset { IntOffset(x.roundToInt(), 0) }.width(with(density) { w.toDp() }).fillMaxHeight()
                .clip(CircleShape).background(Nf.Violet.copy(alpha = 0.2f))
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, tab ->
                val selected = i == sel
                val tint by animateColorAsState(if (selected) Nf.Violet else Nf.TextMute, tween(220), label = "tint")
                val iconScale by animateFloatAsState(if (selected) 1f else 0.92f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "is")
                Row(
                    Modifier.weight(weights[i]).fillMaxHeight().clip(CircleShape)
                        .clickable(remember { MutableInteractionSource() }, indication = null) {
                            if (!selected) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            onSelect(tab.route)
                        },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(tab.icon, tab.label, tint = tint, modifier = Modifier.size(22.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale })
                    AnimatedVisibility(
                        selected,
                        enter = expandHorizontally(spring(dampingRatio = 0.85f, stiffness = 420f)) + fadeIn(tween(200, 80)),
                        exit = shrinkHorizontally(spring(dampingRatio = 0.9f, stiffness = 500f)) + fadeOut(tween(90)),
                    ) {
                        Row {
                            Spacer(Modifier.width(8.dp))
                            Text(tab.label, style = Type.Label, color = Nf.Text, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}
