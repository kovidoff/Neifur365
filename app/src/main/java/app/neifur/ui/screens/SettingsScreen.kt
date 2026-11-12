@file:OptIn(ExperimentalLayoutApi::class)

package app.neifur.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.neifur.BuildConfig
import app.neifur.Container
import app.neifur.data.a365.AccountState
import app.neifur.data.a365.MirrorHealth
import app.neifur.data.a365.MirrorOrigin
import app.neifur.data.a365.MirrorStatus
import app.neifur.nfViewModel
import app.neifur.ui.*
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsVm(private val c: Container) : ViewModel() {
    val account = c.account.state
    var mirrors by mutableStateOf<List<MirrorStatus>?>(null)
        private set
    var mirrorsBusy by mutableStateOf(false)
        private set
    var mirrorInput by mutableStateOf("")
    val mirrorAuto = c.prefs.mirrorAuto
    var cacheBytes by mutableStateOf<Long?>(null)
        private set
    val quality = c.prefs.quality
    val preferredKind = c.prefs.preferredKind
    val history = c.prefs.history
    val subScale = c.prefs.subScaleFlow
    val studios = c.prefs.favouriteStudios
    val pickCount = c.prefs.pickCount
    /** Address in use, for "open on the site" links. */
    var base by mutableStateOf<String?>(null)
        private set

    init {
        checkMirrors()
        viewModelScope.launch { base = runCatching { c.a365.baseUrl() }.getOrNull() }
    }

    fun removeStudio(key: String) = viewModelScope.launch { c.prefs.removeFavouriteStudio(key) }
    fun clearPicks() = viewModelScope.launch { c.prefs.clearPicks() }

    fun checkMirrors() {
        mirrorsBusy = true
        viewModelScope.launch {
            runCatching { c.a365.resolveMirror(); mirrors = c.a365.probeAll().withActive(c.prefs.mirror.first()) }
            mirrorsBusy = false
        }
    }

    private fun List<MirrorStatus>.withActive(host: String?) = map { it.copy(active = it.host == host) }

    fun pickMirror(host: String) = viewModelScope.launch {
        c.a365.pick(host)
        mirrors = mirrors?.withActive(host)
    }

    fun setAuto(auto: Boolean) = viewModelScope.launch {
        c.prefs.setMirrorAuto(auto)
        if (auto) { c.prefs.setMirror(null); checkMirrors() }
    }

    fun removeMirror(host: String) = viewModelScope.launch {
        c.a365.removeCustom(host)
        mirrors = mirrors?.filterNot { it.host == host }
        if (c.prefs.mirror.first() == null) checkMirrors()
    }

    fun addMirror() {
        val h = mirrorInput.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').trimEnd('.').lowercase()
        if (h.isBlank() || !h.contains('.')) return
        viewModelScope.launch {
            c.prefs.setCustomMirrors((c.prefs.customMirrors.first() + h).distinct())
            mirrorInput = ""
            checkMirrors()
        }
    }

    fun logout() = c.account.logout()
    fun setQuality(q: Int) = viewModelScope.launch { c.prefs.setQuality(q) }
    fun setKind(k: String) = viewModelScope.launch { c.prefs.setPreferredKind(k) }
    fun setSubScale(v: Double) = viewModelScope.launch { c.prefs.setSubScale(v) }
    fun clearHistory() = viewModelScope.launch { c.prefs.clearHistory() }
    fun showWelcome() = viewModelScope.launch { c.prefs.beginOnboarding() }

    fun measureCache(ctx: android.content.Context) = viewModelScope.launch {
        cacheBytes = withContext(Dispatchers.IO) { runCatching { SingletonImageLoader.get(ctx).diskCache?.size }.getOrNull() ?: 0L }
    }

    fun clearCache(ctx: android.content.Context) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            runCatching { SingletonImageLoader.get(ctx).diskCache?.clear() }
            runCatching { SingletonImageLoader.get(ctx).memoryCache?.clear() }
        }
        measureCache(ctx)
    }
}

/** [onBack] is null when Settings is opened as a tab of the bottom bar. */
@Composable
fun SettingsScreen(onBack: (() -> Unit)? = null) {
    val vm = nfViewModel { SettingsVm(it) }
    val ctx = LocalContext.current
    val quality by vm.quality.collectAsStateWithLifecycle(1080)
    val kind by vm.preferredKind.collectAsStateWithLifecycle("VoiceRu")
    val history by vm.history.collectAsStateWithLifecycle(emptyList())
    val subScale by vm.subScale.collectAsStateWithLifecycle(1.0)
    val studios by vm.studios.collectAsStateWithLifecycle(emptyList())
    val pickCount by vm.pickCount.collectAsStateWithLifecycle(0)
    var confirmHistory by remember { mutableStateOf(false) }
    var confirmPicks by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.measureCache(ctx) }

    // A lazy list: the tab opens by composing only what's on screen, which keeps the switch from Home smooth.
    LazyColumn(
        Modifier.fillMaxSize().background(Nf.Ink).statusBarsPadding().imePadding(),
        contentPadding = PaddingValues(bottom = 128.dp),
    ) {
        item(key = "header") { Row(Modifier.padding(start = if (onBack != null) 14.dp else 20.dp, top = 14.dp, end = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Nf.Surface).pressable(0.88f, onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Nf.Text, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
            }
            Text("Настройки", style = Type.Display, color = Nf.Text)
        } }

        item(key = "account") { Spacer(Modifier.height(10.dp)); AccountCard(vm) { confirmLogout = true } }

        item(key = "translation") { SectionLabel("Перевод")
        Card {
            SettingTitle("Как вы смотрите", "Что включать в новых тайтлах, пока вы не выбрали студию сами")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KindTile("VoiceRu", Icons.Rounded.Mic, Nf.Pink, "Озвучка", kind) { vm.setKind(it) }
                KindTile("SubRu", Icons.Rounded.ClosedCaption, Nf.Violet, "Субтитры", kind) { vm.setKind(it) }
                KindTile("Raw", Icons.Rounded.Translate, Nf.Mint, "Оригинал", kind) { vm.setKind(it) }
            }
            Divider()
            SettingTitle(
                "Любимые студии",
                if (studios.isEmpty()) "Появятся, когда вы выберете перевод на странице тайтла или в плеере"
                else "Их Neifur 365 ставит первыми на новых тайтлах. Сверху — последняя выбранная",
            )
            if (studios.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                studios.forEach { (key, name) -> StudioChip(key, name) { vm.removeStudio(key) } }
            }
            if (pickCount > 0) {
                Divider()
                ActionRow(Icons.Rounded.RestartAlt, "Выбор по тайтлам", "Запомнено для $pickCount ${plural(pickCount, "тайтла", "тайтлов", "тайтлов")}", "Сбросить", enabled = true) { confirmPicks = true }
            }
        } }

        item(key = "player") { SectionLabel("Плеер")
        Card {
            SettingTitle("Качество видео", "Если такого нет у перевода, включится ближайшее ниже")
            Segmented(listOf(1080 to "1080p", 720 to "720p", 480 to "480p"), quality) { vm.setQuality(it) }
            Divider()
            SettingTitle("Размер субтитров", null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.8, 1.0, 1.25, 1.5).forEachIndexed { i, v ->
                    val on = kotlin.math.abs(subScale - v) < 0.01
                    val bg by animateColorAsState(if (on) Nf.Violet.copy(alpha = 0.2f) else Nf.Ink, label = "sz")
                    Box(
                        Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                            .border(1.dp, if (on) Nf.Violet.copy(alpha = 0.55f) else Nf.Line, RoundedCornerShape(14.dp))
                            .clickable { vm.setSubScale(v) },
                        contentAlignment = Alignment.Center,
                    ) { Text("Аа", style = Type.Title.copy(fontSize = (13 + i * 3).sp), color = if (on) Nf.Violet else Nf.Text) }
                }
            }
        } }

        item(key = "site") { SectionLabel("Сайт Anime 365"); MirrorsCard(vm) }

        item(key = "data") { SectionLabel("Данные")
        Card(padding = 6.dp) {
            ActionRow(
                Icons.Rounded.History, "История просмотра",
                history.distinctBy { it.animeId }.size.let { if (it == 0) "Пусто" else "$it ${plural(it, "тайтл", "тайтла", "тайтлов")}" },
                "Очистить", enabled = history.isNotEmpty(),
            ) { confirmHistory = true }
            Divider(inset = 58.dp)
            ActionRow(
                Icons.Rounded.Image, "Кэш обложек",
                vm.cacheBytes?.let { if (it < 1L shl 20) "Меньше 1 МБ" else "${it / (1L shl 20)} МБ" } ?: "…",
                "Очистить", enabled = (vm.cacheBytes ?: 0) > 0,
            ) { vm.clearCache(ctx) }
        } }

        // ---------------- About ----------------
        item(key = "about") { Column(Modifier.fillMaxWidth().padding(top = 34.dp, start = 28.dp, end = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Hidden for testing: five quick taps on the wordmark bring the welcome flow back.
            var taps by remember { mutableIntStateOf(0) }
            var lastTap by remember { mutableLongStateOf(0L) }
            Wordmark(
                Modifier.graphicsLayer { alpha = 0.75f }.clickable(remember { MutableInteractionSource() }, indication = null) {
                    val now = System.currentTimeMillis()
                    taps = if (now - lastTap < 600) taps + 1 else 1
                    lastTap = now
                    if (taps >= 5) { taps = 0; vm.showWelcome() }
                },
            )
            Text("Версия ${BuildConfig.VERSION_NAME}", style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 4.dp))
            Text(
                "Neifur 365 — неофициальное приложение для просмотра каталога Anime 365. Каталог, видео и аккаунт — Anime 365, описания и персонажи — Shikimori.",
                style = Type.Caption, color = Nf.TextMute, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        } }
    }

    if (confirmHistory) AlertDialog(
        onDismissRequest = { confirmHistory = false },
        containerColor = Nf.SurfaceHi, titleContentColor = Nf.Text, textContentColor = Nf.TextDim,
        title = { Text("Очистить историю?", style = Type.Section) },
        text = { Text("Пропадут «Продолжить просмотр» и позиции в сериях на этом телефоне. Списки на Anime 365 не изменятся.", style = Type.Body) },
        confirmButton = { TextButton({ vm.clearHistory(); confirmHistory = false }) { Text("Очистить", color = Nf.Red, style = Type.Label) } },
        dismissButton = { TextButton({ confirmHistory = false }) { Text("Отмена", color = Nf.Text, style = Type.Label) } },
    )
    if (confirmPicks) AlertDialog(
        onDismissRequest = { confirmPicks = false },
        containerColor = Nf.SurfaceHi, titleContentColor = Nf.Text, textContentColor = Nf.TextDim,
        title = { Text("Сбросить выбор студий?", style = Type.Section) },
        text = { Text("Тайтлы снова будут включаться по настройке «Как вы смотрите», любимые студии очистятся.", style = Type.Body) },
        confirmButton = { TextButton({ vm.clearPicks(); confirmPicks = false }) { Text("Сбросить", color = Nf.Red, style = Type.Label) } },
        dismissButton = { TextButton({ confirmPicks = false }) { Text("Отмена", color = Nf.Text, style = Type.Label) } },
    )
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        containerColor = Nf.SurfaceHi, titleContentColor = Nf.Text, textContentColor = Nf.TextDim,
        title = { Text("Выйти из аккаунта?", style = Type.Section) },
        text = { Text("Смотреть видео можно будет после нового входа. История на телефоне сохранится.", style = Type.Body) },
        confirmButton = { TextButton({ vm.logout(); confirmLogout = false }) { Text("Выйти", color = Nf.Red, style = Type.Label) } },
        dismissButton = { TextButton({ confirmLogout = false }) { Text("Отмена", color = Nf.Text, style = Type.Label) } },
    )
}

// --- account -------------------------------------------------------------------------------------------

@Composable
private fun AccountCard(vm: SettingsVm, onLogout: () -> Unit) {
    val state by vm.account.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var signIn by remember { mutableStateOf(false) }
    // The account sits on a soft violet wash: it's the first thing on the page and the heart of Neifur 365.
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Nf.Violet.copy(alpha = 0.16f), Nf.Pink.copy(alpha = 0.07f), Nf.Surface)))
            .border(1.dp, Nf.Violet.copy(alpha = 0.22f), RoundedCornerShape(26.dp)).padding(18.dp)
    ) {
        AnimatedContent(state, label = "acc", transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }) { s ->
            when (s) {
                AccountState.Unknown -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(CircleShape).shimmer())
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth(0.55f).height(13.dp).clip(CircleShape).shimmer())
                        Box(Modifier.fillMaxWidth(0.8f).height(10.dp).clip(CircleShape).shimmer())
                    }
                }
                is AccountState.SignedIn -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Nf.Violet, Nf.Pink))).padding(2.5.dp)) {
                            PosterImage(s.profile.avatarUrl, Modifier.fillMaxSize().clip(CircleShape))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.profile.name, style = Type.Display.copy(fontSize = 19.sp), color = Nf.Text, maxLines = 1)
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(Nf.Mint))
                                Spacer(Modifier.width(6.dp))
                                Text("Anime 365 · ID ${s.profile.id}", style = Type.Caption, color = Nf.TextDim, maxLines = 1)
                            }
                        }
                    }
                    Text(
                        "Списки и отметки о просмотре синхронизируются с сайтом",
                        style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 14.dp),
                    )
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.base?.let { b -> PillButton("Профиль на сайте", Icons.AutoMirrored.Rounded.OpenInNew) { openUrl(ctx, "$b/users/profile") } }
                        PillButton("Выйти", Icons.AutoMirrored.Rounded.Logout, tint = Nf.Red, onClick = onLogout)
                    }
                }
                AccountState.SignedOut -> Column {
                    Wordmark()
                    Text("Войдите, чтобы смотреть", style = Type.Title, color = Nf.Text, modifier = Modifier.padding(top = 14.dp))
                    Text(
                        "Видео Anime 365 доступно после входа. Списки и отметки о просмотре будут общими с сайтом.",
                        style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                    )
                    PrimaryButton("Войти в Anime 365") { signIn = true }
                }
            }
        }
    }
    if (signIn) SignInSheet(onDismiss = { signIn = false }, onDone = { signIn = false })
}

// --- mirrors -------------------------------------------------------------------------------------------

@Composable
private fun MirrorsCard(vm: SettingsVm) {
    val focus = LocalFocusManager.current
    val auto by vm.mirrorAuto.collectAsStateWithLifecycle(true)
    val list = vm.mirrors
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Адреса Anime 365", style = Type.Title, color = Nf.Text)
                val working = list?.count { it.ok }
                Text(
                    when {
                        list == null -> "Проверяю…"
                        working == 0 -> "Сейчас ни один не отвечает"
                        else -> "Работают $working из ${list.size}"
                    },
                    style = Type.Caption, color = if (working == 0) Nf.Red else Nf.TextMute, modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(40.dp).clip(CircleShape).background(Nf.Ink).pressable(0.88f, enabled = !vm.mirrorsBusy) { vm.checkMirrors() }, contentAlignment = Alignment.Center) {
                if (vm.mirrorsBusy) CircularProgressIndicator(Modifier.size(18.dp), color = Nf.TextDim, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, "Проверить адреса", tint = Nf.TextDim, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Segmented(listOf(true to "Автовыбор", false to "Вручную"), auto) { vm.setAuto(it) }
        Text(
            if (auto) "Используется самый быстрый рабочий адрес. Если он перестанет отвечать, приложение переключится само"
            else "Используется выбранный адрес. Если он перестанет отвечать, приложение временно переключится на другой",
            style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
        )
        Column(Modifier.animateContentSize()) {
            if (list == null) Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(14.dp)).shimmer())
            else list.forEach { m -> MirrorRow(m, auto, onPick = { vm.pickMirror(m.host) }, onRemove = { vm.removeMirror(m.host) }) }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(Nf.Ink).border(1.dp, Nf.Line, RoundedCornerShape(14.dp))
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (vm.mirrorInput.isEmpty()) Text("Добавить свой адрес", style = Type.Label, color = Nf.TextMute)
                BasicTextField(
                    vm.mirrorInput, { vm.mirrorInput = it }, singleLine = true, textStyle = Type.Body.copy(color = Nf.Text),
                    cursorBrush = SolidColor(Nf.Violet), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(vm.mirrorInput.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(Nf.Violet.copy(alpha = 0.2f)).pressable(0.88f) { focus.clearFocus(); vm.addMirror() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, "Добавить", tint = Nf.Violet, modifier = Modifier.size(20.dp))
                }
            }
        }
        val ctx = LocalContext.current
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Официальные адреса сайта. smotret-anime.app работает из России без VPN",
                style = Type.Caption, color = Nf.TextMute, modifier = Modifier.weight(1f).padding(start = 2.dp),
            )
            vm.mirrors?.firstOrNull { it.active }?.let { m ->
                Text(
                    "Открыть сайт", style = Type.Label, color = Nf.Violet,
                    modifier = Modifier.clip(CircleShape).clickable { openUrl(ctx, "https://${m.host}") }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun MirrorRow(m: MirrorStatus, auto: Boolean, onPick: () -> Unit, onRemove: () -> Unit) {
    val bg by animateColorAsState(if (m.active) Nf.Violet.copy(alpha = 0.12f) else Color.Transparent, label = "mr")
    val (dot, label) = when (m.health) {
        MirrorHealth.Ok -> Nf.Mint to "${m.millis} мс"
        MirrorHealth.Slow -> Nf.Amber to "медленно · ${m.millis / 100 / 10.0} с"
        MirrorHealth.Blocked -> Nf.Amber to "защита Cloudflare"
        MirrorHealth.Down -> Nf.Red to "не отвечает"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).clickable(enabled = m.ok, onClick = onPick)
            .padding(start = 10.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.host, style = Type.Title.copy(fontSize = 15.sp), color = if (m.ok) Nf.Text else Nf.TextMute, maxLines = 1)
                if (m.origin == MirrorOrigin.Custom) Badge("свой", Nf.Violet)
            }
            Text(if (m.active) (if (auto) "Используется · выбран автоматически" else "Используется · выбран вами") else label,
                style = Type.Caption, color = if (m.active) Nf.Violet else if (m.ok) Nf.TextMute else dot.copy(alpha = 0.85f))
        }
        if (m.origin == MirrorOrigin.Custom) Icon(
            Icons.Rounded.Close, "Удалить адрес", tint = Nf.TextMute,
            modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onRemove).padding(9.dp),
        ) else if (m.active) Icon(Icons.Rounded.Check, null, tint = Nf.Violet, modifier = Modifier.padding(end = 8.dp).size(18.dp))
    }
}

@Composable
private fun RowScope.KindTile(key: String, icon: ImageVector, tint: Color, label: String, selected: String, onPick: (String) -> Unit) {
    val on = key == selected
    val bg by animateColorAsState(if (on) tint.copy(alpha = 0.16f) else Nf.Ink, label = "kt")
    val br by animateColorAsState(if (on) tint.copy(alpha = 0.6f) else Nf.Line, label = "ktb")
    Column(
        Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(18.dp)).background(bg).border(1.dp, br, RoundedCornerShape(18.dp))
            .pressable(0.95f) { onPick(key) },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (on) tint else Nf.TextDim, modifier = Modifier.size(24.dp))
        Text(label, style = Type.Label, color = if (on) Nf.Text else Nf.TextDim, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun StudioChip(key: String, name: String, onRemove: () -> Unit) {
    val voice = key.startsWith("Voice")
    val tint = if (voice) Nf.Pink else Nf.Violet
    Row(
        Modifier.clip(CircleShape).background(tint.copy(alpha = 0.12f)).border(1.dp, tint.copy(alpha = 0.3f), CircleShape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (voice) Icons.Rounded.Mic else Icons.Rounded.ClosedCaption, null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(name, style = Type.Label, color = Nf.Text, maxLines = 1)
        Icon(
            Icons.Rounded.Close, "Убрать $name", tint = Nf.TextMute,
            modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove).padding(8.dp),
        )
    }
}

private fun openUrl(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun Badge(text: String, color: Color) = Text(
    text, style = Type.Caption.copy(fontSize = 10.5.sp), color = color,
    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 1.dp),
)

// --- building blocks -----------------------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) =
    Text(text.uppercase(), style = Type.Overline, color = Nf.TextMute, modifier = Modifier.padding(start = 26.dp, top = 26.dp, bottom = 10.dp))

@Composable
private fun Card(padding: androidx.compose.ui.unit.Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Nf.Surface)
            .border(1.dp, Color(0x0DFFFFFF), RoundedCornerShape(24.dp)).padding(padding),
        content = content,
    )
}

@Composable
private fun SettingTitle(title: String, hint: String?) {
    Text(title, style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text)
    if (hint != null) Text(hint, style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Divider(inset: androidx.compose.ui.unit.Dp = 0.dp) =
    Box(Modifier.padding(start = inset, top = 16.dp, bottom = 16.dp).fillMaxWidth().height(1.dp).background(Nf.Line.copy(alpha = 0.6f)))

/** Segmented control whose highlight slides between options. */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    val density = LocalDensity.current
    var w by remember { mutableIntStateOf(0) }
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val inner = 4.dp
    val seg = with(density) { ((w - (inner * 2).roundToPx()) / options.size).toDp() }
    val x by animateDpAsState(seg * index, spring(dampingRatio = 0.8f, stiffness = 500f), label = "segx")
    Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(Nf.Ink).border(1.dp, Nf.Line, RoundedCornerShape(14.dp)).onSizeChanged { w = it.width }.padding(inner)) {
        if (w > 0) Box(Modifier.offset(x = x).width(seg).fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(Nf.Violet.copy(alpha = 0.24f)))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (v, label) ->
                val c by animateColorAsState(if (i == index) Nf.Text else Nf.TextDim, label = "segc")
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(11.dp)).clickable { onPick(v) }, contentAlignment = Alignment.Center) {
                    Text(label, style = Type.Label.copy(fontSize = 12.5.sp), color = c, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, value: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Nf.Ink), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Nf.TextDim, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text)
            Text(value, style = Type.Caption, color = Nf.TextMute)
        }
        Row(
            Modifier.clip(CircleShape).background(if (enabled) Nf.Red.copy(alpha = 0.12f) else Nf.Ink)
                .pressable(0.92f, enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.DeleteOutline, null, tint = if (enabled) Nf.Red else Nf.TextMute, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(action, style = Type.Label, color = if (enabled) Nf.Red else Nf.TextMute)
        }
    }
}

@Composable
private fun PillButton(text: String, icon: ImageVector?, tint: Color = Nf.Text, onClick: () -> Unit) {
    Row(
        Modifier.height(44.dp).clip(CircleShape).background(Nf.Ink).border(1.dp, Nf.Line, CircleShape).pressable(0.95f, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)) }
        Text(text, style = Type.Label, color = tint)
    }
}
