@file:OptIn(ExperimentalLayoutApi::class)

package app.neifur.ui.screens

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.neifur.BuildConfig
import app.neifur.Container
import app.neifur.alerts.EpisodeAlerts
import app.neifur.data.a365.AccountState
import app.neifur.data.a365.ChannelSpeed
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.MirrorHealth
import app.neifur.data.a365.MirrorOrigin
import app.neifur.data.a365.MirrorStatus
import app.neifur.data.a365.VideoChannel
import app.neifur.downloads.formatBytes
import app.neifur.nfViewModel
import app.neifur.ui.*
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Subpages under the Settings hub, keeping the main tab concise and easy to scan. */
enum class SettingsSection(val route: String, val title: String, val subtitle: String) {
    Player("player", "Плеер", "Качество видео, субтитры, перемотка и жесты"),
    Translation("translation", "Перевод и студии", "Язык по умолчанию и любимые студии"),
    Alerts("alerts", "Уведомления", "Выход новых серий из ваших списков"),
    Connection("connection", "Подключение и ссылки", "Адреса сайта, каналы видео и открытие ссылок"),
    Data("data", "Память и данные", "Скачанные серии, история и кэш обложек"),
    ;

    companion object {
        fun of(route: String?): SettingsSection? = entries.firstOrNull { it.route == route }
    }
}

class SettingsVm(private val c: Container) : ViewModel() {
    val account = c.account.state
    val lists = c.lists.state
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

    // --- video channel (where the account's video links come from): shared with the player and startup ---
    val channel = c.channels.state
    val channelAuto = c.prefs.channelAuto
    fun loadChannels() = viewModelScope.launch { if (c.channels.state.value.setup == null) c.channels.load() }
    fun testChannels() = c.channels.testAll()
    fun pickChannel(ch: VideoChannel) = c.channels.pick(ch)
    fun setChannelAuto(on: Boolean) = c.channels.setAuto(on)

    fun removeStudio(key: String) = viewModelScope.launch { c.prefs.removeFavouriteStudio(key) }
    fun clearPicks() = viewModelScope.launch { c.prefs.clearPicks() }

    fun checkMirrors() {
        mirrorsBusy = true
        viewModelScope.launch {
            runCatching { c.a365.ensureMirrorWorking(); mirrors = c.a365.probeAll().withActive(c.prefs.mirror.first()) }
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
    val seekStep = c.prefs.seekStep
    val holdBoost = c.prefs.holdBoost
    val sideGestures = c.prefs.sideGestures
    val rememberBrightness = c.prefs.rememberBrightness
    val downloadQuality = c.prefs.downloadQuality
    val autoDeleteWatched = c.prefs.autoDeleteWatched
    val downloads = c.downloads.state
    val alerts = c.prefs.alerts
    val alertsMyStudio = c.prefs.alertsMyStudio
    fun setSeekStep(v: Int) = viewModelScope.launch { c.prefs.setSeekStep(v) }
    fun setHoldBoost(on: Boolean) = viewModelScope.launch { c.prefs.setHoldBoost(on) }
    fun setSideGestures(on: Boolean) = viewModelScope.launch { c.prefs.setSideGestures(on) }
    fun setRememberBrightness(on: Boolean) = viewModelScope.launch { c.prefs.setRememberBrightness(on) }
    fun setDownloadQuality(q: Int) = viewModelScope.launch { c.prefs.setDownloadQuality(q) }
    fun setAutoDeleteWatched(on: Boolean) = viewModelScope.launch { c.prefs.setAutoDeleteWatched(on) }
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

/**
 * Settings hub: account hero at the top, followed by grouped category rows with live summaries of the current
 * choices. Each row opens a focused subpage so adding new capabilities never turns Settings into an endless scroll.
 */
@Composable
fun SettingsScreen(onOpenSection: (SettingsSection) -> Unit) {
    val vm = nfViewModel { SettingsVm(it) }
    val ctx = LocalContext.current
    val acc by vm.account.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle(1080)
    val kind by vm.preferredKind.collectAsStateWithLifecycle("VoiceRu")
    val history by vm.history.collectAsStateWithLifecycle(emptyList())
    val seekStep by vm.seekStep.collectAsStateWithLifecycle(10)
    val holdBoost by vm.holdBoost.collectAsStateWithLifecycle(true)
    val studios by vm.studios.collectAsStateWithLifecycle(emptyList())
    val alertsOn by vm.alerts.collectAsStateWithLifecycle(false)
    val alertsMyStudio by vm.alertsMyStudio.collectAsStateWithLifecycle(true)
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val channel by vm.channel.collectAsStateWithLifecycle()
    var alertsPermitted by remember { mutableStateOf(EpisodeAlerts.permitted(ctx)) }
    var confirmLogout by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        alertsPermitted = EpisodeAlerts.permitted(ctx)
        vm.measureCache(ctx)
        onPauseOrDispose {}
    }
    LaunchedEffect(acc) { if (acc is AccountState.SignedIn) vm.loadChannels() }

    val signedIn = acc is AccountState.SignedIn
    val (alertsLine, alertsWarn) = alertsSummary(signedIn, alertsOn, alertsPermitted, alertsMyStudio)
    val activeMirror = vm.mirrors?.firstOrNull { it.active }?.host
    val anyMirrorWorking = vm.mirrors?.any { it.ok }
    val activeChannel = channel.setup?.let { s -> s.channels.firstOrNull { it.id == s.current }?.label }
    val (connLine, connWarn) = connectionSummary(activeMirror, anyMirrorWorking, if (signedIn) activeChannel else null)
    val titleCount = remember(history) { history.distinctBy { it.animeId }.size }

    Box(Modifier.fillMaxSize().background(Nf.Ink).statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 128.dp),
        ) {
        item(key = "header") {
            Text("Настройки", style = Type.Display, color = Nf.Text, modifier = Modifier.padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 4.dp))
        }

        item(key = "account") { Spacer(Modifier.height(10.dp)); AccountCard(vm) { confirmLogout = true } }

        item(key = "group-watch") {
            SectionLabel("Просмотр")
            Card(padding = 6.dp) {
                NavRow(
                    icon = Icons.Rounded.PlayArrow, tint = Nf.Violet, title = SettingsSection.Player.title,
                    summary = playerSummary(quality, seekStep, holdBoost),
                ) { onOpenSection(SettingsSection.Player) }
                RowDivider()
                NavRow(
                    icon = Icons.Rounded.Translate, tint = Nf.Pink, title = SettingsSection.Translation.title,
                    summary = translationSummary(kind, studios.size),
                ) { onOpenSection(SettingsSection.Translation) }
                RowDivider()
                NavRow(
                    icon = Icons.Rounded.NotificationsActive, tint = Nf.Mint, title = SettingsSection.Alerts.title,
                    summary = alertsLine, summaryColor = if (alertsWarn) Nf.Amber else Nf.TextMute,
                    badge = if (alertsWarn) Nf.Amber else null,
                ) { onOpenSection(SettingsSection.Alerts) }
            }
        }

        item(key = "group-app") {
            SectionLabel("Приложение")
            Card(padding = 6.dp) {
                NavRow(
                    icon = Icons.Rounded.Public, tint = Nf.Violet, title = SettingsSection.Connection.title,
                    summary = connLine, summaryColor = if (connWarn) Nf.Red else Nf.TextMute,
                    badge = if (connWarn) Nf.Red else null,
                ) { onOpenSection(SettingsSection.Connection) }
                RowDivider()
                NavRow(
                    icon = Icons.Rounded.FolderOpen, tint = Nf.Amber, title = SettingsSection.Data.title,
                    summary = dataSummary(vm.cacheBytes, titleCount, downloads.totalBytes),
                ) { onOpenSection(SettingsSection.Data) }
            }
        }

        item(key = "about") {
            Column(Modifier.fillMaxWidth().padding(top = 30.dp, start = 28.dp, end = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
                    "Neifur 365 — неофициальный клиент Anime 365. Видео и списки — Anime 365, описания и персонажи — Shikimori.",
                    style = Type.Caption, color = Nf.TextMute, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    }

    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        containerColor = Nf.SurfaceHi, titleContentColor = Nf.Text, textContentColor = Nf.TextDim,
        title = { Text("Выйти из аккаунта?", style = Type.Section) },
        text = { Text("Смотреть видео можно будет после нового входа. История на телефоне сохранится.", style = Type.Body) },
        confirmButton = { TextButton({ vm.logout(); confirmLogout = false }) { Text("Выйти", color = Nf.Red, style = Type.Label) } },
        dismissButton = { TextButton({ confirmLogout = false }) { Text("Отмена", color = Nf.Text, style = Type.Label) } },
    )
}

/** Focused subpage for one category of settings, sharing [SettingsVm] with the hub. */
@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    owner: ViewModelStoreOwner,
    onDownloads: () -> Unit = {},
    onBack: () -> Unit,
) {
    val vm = nfViewModel(owner = owner) { SettingsVm(it) }
    val ctx = LocalContext.current
    val acc by vm.account.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle(1080)
    val downloadQuality by vm.downloadQuality.collectAsStateWithLifecycle(0)
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val kind by vm.preferredKind.collectAsStateWithLifecycle("VoiceRu")
    val history by vm.history.collectAsStateWithLifecycle(emptyList())
    val subScale by vm.subScale.collectAsStateWithLifecycle(1.0)
    val seekStep by vm.seekStep.collectAsStateWithLifecycle(10)
    val holdBoost by vm.holdBoost.collectAsStateWithLifecycle(true)
    val sideGestures by vm.sideGestures.collectAsStateWithLifecycle(true)
    val rememberBrightness by vm.rememberBrightness.collectAsStateWithLifecycle(false)
    val autoDeleteWatched by vm.autoDeleteWatched.collectAsStateWithLifecycle(false)
    val studios by vm.studios.collectAsStateWithLifecycle(emptyList())
    val pickCount by vm.pickCount.collectAsStateWithLifecycle(0)
    var confirmHistory by remember { mutableStateOf(false) }
    var confirmPicks by remember { mutableStateOf(false) }

    if (section == SettingsSection.Data) LaunchedEffect(Unit) { vm.measureCache(ctx) }
    if (section == SettingsSection.Connection) LaunchedEffect(acc) { if (acc is AccountState.SignedIn) vm.loadChannels() }

    Box(Modifier.fillMaxSize().background(Nf.Ink).statusBarsPadding().navigationBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 44.dp),
        ) {
        item(key = "sub-header") {
            Row(
                Modifier.padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 18.dp),
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
                    Text(section.title, style = Type.Display.copy(fontSize = 22.sp), color = Nf.Text, maxLines = 1)
                    Text(section.subtitle, style = Type.Caption, color = Nf.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        when (section) {
            SettingsSection.Player -> {
                item(key = "video") {
                    Card {
                        SettingTitle("Качество видео", "Если такого нет у перевода, включится ближайшее ниже")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QualityTile(1080, "Full HD", 3, quality) { vm.setQuality(it) }
                            QualityTile(720, "HD", 2, quality) { vm.setQuality(it) }
                            QualityTile(480, "Экономия", 1, quality) { vm.setQuality(it) }
                        }
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
                    }
                }
                item(key = "controls") {
                    Spacer(Modifier.height(12.dp))
                    Card {
                        SettingTitle("Перемотка", "Кнопки в плеере, двойной тап по краю экрана и наушники")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StepTile(5, Icons.Rounded.Replay5, "Точнее", seekStep) { vm.setSeekStep(it) }
                            StepTile(10, Icons.Rounded.Replay10, "Быстрее", seekStep) { vm.setSeekStep(it) }
                        }
                        Divider()
                        ToggleRow(
                            Icons.Rounded.FastForward, "Ускорение при удержании",
                            "Держите палец на видео — серия идёт в 2×", holdBoost,
                        ) { vm.setHoldBoost(it) }
                        Divider()
                        ToggleRow(
                            Icons.Rounded.Tune, "Жесты яркости и громкости",
                            "Свайп вверх и вниз по левой и правой сторонам экрана", sideGestures,
                        ) { vm.setSideGestures(it) }
                        Divider()
                        ToggleRow(
                            Icons.Rounded.LightMode, "Запоминать яркость",
                            "Сохраняет яркость из свайпа в плеере между сериями", rememberBrightness,
                        ) { vm.setRememberBrightness(it) }
                        Divider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Nf.Ink), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.KeyboardDoubleArrowRight, null, tint = Nf.TextDim, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "В нижней панели плеера есть кнопка «+85» — быстрый пропуск опенинга",
                                style = Type.Caption, color = Nf.TextMute,
                            )
                        }
                    }
                }
            }

            SettingsSection.Translation -> {
                item(key = "kind") {
                    Card {
                        SettingTitle("Как вы смотрите", "Что включать в новых тайтлах, пока вы не выбрали студию сами")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KindTile("VoiceRu", Icons.Rounded.Mic, Nf.Pink, "Озвучка", kind) { vm.setKind(it) }
                            KindTile("SubRu", Icons.Rounded.ClosedCaption, Nf.Violet, "Субтитры", kind) { vm.setKind(it) }
                            KindTile("Raw", Icons.Rounded.Translate, Nf.Mint, "Оригинал", kind) { vm.setKind(it) }
                        }
                    }
                }
                item(key = "studios") {
                    Spacer(Modifier.height(12.dp))
                    Card {
                        SettingTitle(
                            "Любимые студии",
                            if (studios.isEmpty()) "Отмечайте студии звёздочкой ★ в списке переводов — на странице тайтла или в плеере"
                            else "Стоят первыми в списке переводов любого тайтла. Добавляются звёздочкой ★",
                        )
                        if (studios.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            studios.forEach { (key, name) -> StudioChip(key, name) { vm.removeStudio(key) } }
                        }
                    }
                    if (pickCount > 0) {
                        Spacer(Modifier.height(12.dp))
                        Card(padding = 6.dp) {
                            ActionRow(
                                Icons.Rounded.RestartAlt, "Выбор по тайтлам",
                                "Запомнено для $pickCount ${plural(pickCount, "тайтла", "тайтлов", "тайтлов")}",
                                "Сбросить", enabled = true,
                            ) { confirmPicks = true }
                        }
                    }
                }
            }

            SettingsSection.Alerts -> {
                item(key = "alerts") { AlertsSettings(signedIn = acc is AccountState.SignedIn) }
            }

            SettingsSection.Connection -> {
                item(key = "links") { AppLinksCard() }
                item(key = "mirrors") { Spacer(Modifier.height(12.dp)); MirrorsCard(vm) }
                if (acc is AccountState.SignedIn) {
                    item(key = "channels") { Spacer(Modifier.height(12.dp)); ChannelCard(vm) }
                }
            }

            SettingsSection.Data -> {
                item(key = "downloads") {
                    Card(padding = 6.dp) {
                        val n = downloads.items.size
                        val dlText = when {
                            downloads.runningCount > 0 -> "Скачивается: ${downloads.runningCount}" + if (n > 0) " · готово $n" else ""
                            n > 0 -> "$n ${plural(n, "серия", "серии", "серий")} · ${formatBytes(downloads.totalBytes)}"
                            else -> "Пусто · серии для офлайн-просмотра"
                        }
                        NavRow(
                            icon = Icons.Rounded.Download, tint = Nf.Mint, title = "Скачанные серии",
                            summary = dlText,
                            summaryColor = if (downloads.runningCount > 0) Nf.Violet else Nf.TextMute,
                            onClick = onDownloads,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Card {
                        SettingTitle(
                            "Качество загрузки",
                            if (downloadQuality == 0) "Сейчас берётся из настроек плеера (${quality}p)"
                            else "Скачивать серии в ${downloadQuality}p (или ближайшем ниже)",
                        )
                        Segmented(
                            options = listOf(0 to "Плеер", 1080 to "1080p", 720 to "720p", 480 to "480p"),
                            selected = downloadQuality,
                            onPick = { vm.setDownloadQuality(it) },
                        )
                        Divider()
                        ToggleRow(
                            Icons.Rounded.AutoDelete, "Удалять после просмотра",
                            "Освобождает память, когда скачанная серия досмотрена", autoDeleteWatched,
                        ) { vm.setAutoDeleteWatched(it) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "data") {
                    Card(padding = 6.dp) {
                        ActionRow(
                            Icons.Rounded.History, "История просмотра",
                            history.distinctBy { it.animeId }.size.let { if (it == 0) "Пусто" else "$it ${plural(it, "тайтл", "тайтла", "тайтлов")}" },
                            "Очистить", enabled = history.isNotEmpty(),
                        ) { confirmHistory = true }
                        RowDivider()
                        ActionRow(
                            Icons.Rounded.Image, "Кэш обложек",
                            vm.cacheBytes?.let { if (it < 1L shl 20) "Меньше 1 МБ" else "${it / (1L shl 20)} МБ" } ?: "…",
                            "Очистить", enabled = (vm.cacheBytes ?: 0) > 0,
                        ) { vm.clearCache(ctx) }
                    }
                }
            }
        }
    }
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
        text = { Text("Тайтлы снова будут включаться по настройке «Как вы смотрите», любимые студии останутся.", style = Type.Body) },
        confirmButton = { TextButton({ vm.clearPicks(); confirmPicks = false }) { Text("Сбросить", color = Nf.Red, style = Type.Label) } },
        dismissButton = { TextButton({ confirmPicks = false }) { Text("Отмена", color = Nf.Text, style = Type.Label) } },
    )
}

// --- live summaries for the hub ------------------------------------------------------------------------

internal fun playerSummary(quality: Int, seekStep: Int, holdBoost: Boolean): String =
    listOfNotNull("${quality}p", "±$seekStep с", if (holdBoost) "2× удержание" else null).joinToString(" · ")

internal fun translationSummary(kind: String, favouriteCount: Int): String {
    val base = when (kind) { "SubRu" -> "Субтитры"; "Raw" -> "Оригинал"; else -> "Озвучка" }
    return if (favouriteCount > 0) "$base · $favouriteCount ${plural(favouriteCount, "любимая", "любимые", "любимых")}" else base
}

internal fun alertsSummary(signedIn: Boolean, enabled: Boolean, permitted: Boolean, myStudio: Boolean): Pair<String, Boolean> = when {
    !signedIn -> "Нужен вход в аккаунт" to false
    !enabled -> "Выключены" to false
    !permitted -> "Нет разрешения системы" to true
    myStudio -> "Включены · моя студия" to false
    else -> "Включены · любой перевод" to false
}

internal fun connectionSummary(activeMirror: String?, anyMirrorWorking: Boolean?, channelLabel: String?): Pair<String, Boolean> = when {
    anyMirrorWorking == false -> "Нет рабочих адресов" to true
    activeMirror != null && channelLabel != null -> "$activeMirror · $channelLabel" to false
    activeMirror != null -> activeMirror to false
    else -> "Адреса, каналы и ссылки" to false
}

internal fun dataSummary(cacheBytes: Long?, titleCount: Int, downloadBytes: Long = 0L): String {
    val parts = buildList {
        if (downloadBytes > 0L) add("загрузки ${formatBytes(downloadBytes)}")
        cacheBytes?.let { b -> add("кэш " + if (b < 1L shl 20) "< 1 МБ" else "${b / (1L shl 20)} МБ") }
        if (titleCount > 0) add("$titleCount ${plural(titleCount, "тайтл", "тайтла", "тайтлов")}")
    }
    return if (parts.isEmpty()) "История пуста" else parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

// --- supported links (Android 12+ Open by default) -----------------------------------------------------

private data class AppLinksState(val managed: Boolean, val allowed: Boolean, val enabled: Int, val total: Int)

private fun readAppLinks(ctx: android.content.Context): AppLinksState {
    if (Build.VERSION.SDK_INT < 31) return AppLinksState(managed = false, allowed = true, enabled = 0, total = 0)
    return runCatching {
        val mgr = ctx.getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
        val st = mgr?.getDomainVerificationUserState(ctx.packageName) ?: return AppLinksState(false, true, 0, 0)
        val map = st.hostToStateMap
        val on = map.values.count {
            it == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
                it == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        }
        AppLinksState(managed = true, allowed = st.isLinkHandlingAllowed, enabled = on, total = map.size)
    }.getOrDefault(AppLinksState(managed = false, allowed = true, enabled = 0, total = 0))
}

private fun openSupportedLinksSettings(ctx: android.content.Context) {
    val pkg = android.net.Uri.fromParts("package", ctx.packageName, null)
    val primary = if (Build.VERSION.SDK_INT >= 31) {
        android.content.Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg)
    } else {
        android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    }
    if (runCatching { ctx.startActivity(primary.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
        runCatching {
            ctx.startActivity(
                android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

@Composable
private fun AppLinksCard() {
    val ctx = LocalContext.current
    var st by remember { mutableStateOf(readAppLinks(ctx)) }
    LifecycleResumeEffect(Unit) { st = readAppLinks(ctx); onPauseOrDispose {} }
    val active = st.managed && st.allowed && st.enabled > 0
    val tint = if (active) Nf.Mint else Nf.Violet
    val statusText = when {
        active -> "Включено · ${st.enabled} из ${st.total} адресов"
        st.managed && !st.allowed -> "Открытие ссылок выключено в системе"
        st.managed -> "Не настроено · сейчас открываются в браузере"
        else -> "Открывать страницы каталога в Neifur 365"
    }
    val statusColor = when {
        active -> Nf.Mint
        st.managed && !st.allowed -> Nf.Amber
        else -> Nf.TextMute
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                    .background(tint.copy(alpha = 0.14f)).border(1.dp, tint.copy(alpha = 0.22f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Link, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ссылки Anime 365", style = Type.Title, color = Nf.Text)
                Text(statusText, style = Type.Caption, color = statusColor, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text(
            "Включите адреса anime365.ru и зеркал в системном окне «Открывать по умолчанию» — тогда ссылки на тайтлы из мессенджеров и браузера будут открываться сразу здесь.",
            style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 12.dp, bottom = 14.dp),
        )
        Row(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp))
                .background(Nf.Violet.copy(alpha = 0.14f)).border(1.dp, Nf.Violet.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .pressable(0.97f) { openSupportedLinksSettings(ctx) }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = Nf.Violet, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Выбрать поддерживаемые ссылки", style = Type.Label, color = Nf.Text)
        }
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    summary: String,
    summaryColor: Color = Nf.TextMute,
    badge: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).pressable(0.985f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                .background(tint.copy(alpha = 0.14f))
                .border(1.dp, tint.copy(alpha = 0.22f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Title.copy(fontSize = 15.5.sp), color = Nf.Text, maxLines = 1)
            Text(summary, style = Type.Caption, color = summaryColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(badge))
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = Nf.TextMute, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RowDivider() =
    Box(Modifier.padding(start = 66.dp, end = 12.dp).fillMaxWidth().height(1.dp).background(Nf.Line.copy(alpha = 0.5f)))

// --- account -------------------------------------------------------------------------------------------

internal data class SubscriptionBadge(val title: String, val detail: String?, val warning: Boolean)

private val SUB_DATE_FMT = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.forLanguageTag("ru"))

/** Formats the subscription status bar on the profile card, highlighting when ≤ 7 days remain. */
internal fun formatSubscriptionBadge(
    subscribed: Boolean?,
    untilIso: String?,
    today: java.time.LocalDate = java.time.LocalDate.now(),
): SubscriptionBadge? = when (subscribed) {
    false -> SubscriptionBadge("Подписка не активна", null, warning = true)
    true -> {
        val date = untilIso?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        if (date == null) SubscriptionBadge("Подписка активна", null, warning = false)
        else {
            val daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, date)
            val niceDate = "до ${date.format(SUB_DATE_FMT).replace(".", "")}"
            when {
                daysLeft < 0L -> SubscriptionBadge("Подписка истекла", niceDate, warning = true)
                daysLeft == 0L -> SubscriptionBadge("Истекает сегодня", niceDate, warning = true)
                daysLeft <= 7L -> SubscriptionBadge("Осталось $daysLeft дн.", niceDate, warning = true)
                else -> SubscriptionBadge("Подписка активна", niceDate, warning = false)
            }
        }
    }
    null -> null
}

private fun formatCount(n: Int): String {
    val s = n.toString()
    if (s.length <= 3) return s
    return buildString {
        s.forEachIndexed { i, ch ->
            if (i > 0 && (s.length - i) % 3 == 0) append(' ')
            append(ch)
        }
    }
}

@Composable
private fun AccountCard(vm: SettingsVm, onLogout: () -> Unit) {
    val state by vm.account.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
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
                        Box(Modifier.size(62.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Nf.Violet, Nf.Pink))).padding(2.5.dp)) {
                            PosterImage(s.profile.avatarUrl, Modifier.fillMaxSize().clip(CircleShape))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.profile.name, style = Type.Display.copy(fontSize = 19.sp), color = Nf.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(if (s.profile.subscribed == false) Nf.Amber else Nf.Mint))
                                Spacer(Modifier.width(6.dp))
                                Text("Anime 365 · ID ${s.profile.id}", style = Type.Caption, color = Nf.TextDim, maxLines = 1)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(Nf.Ink.copy(alpha = 0.6f))
                                .border(1.dp, Nf.Line, CircleShape).pressable(0.88f, onClick = onLogout),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, "Выйти", tint = Nf.TextDim, modifier = Modifier.size(18.dp))
                        }
                    }

                    // Dedicated subscription status bar (with end date or quick subscribe button).
                    val badge = remember(s.profile.subscribed, s.profile.subscribedUntil) {
                        formatSubscriptionBadge(s.profile.subscribed, s.profile.subscribedUntil)
                    }
                    if (badge != null) {
                        val tint = if (badge.warning) Nf.Amber else Nf.Mint
                        Row(
                            Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(tint.copy(alpha = 0.11f)).border(1.dp, tint.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 13.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (s.profile.subscribed == false) Icons.Rounded.Lock else Icons.Rounded.Verified,
                                null, tint = tint, modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(badge.title, style = Type.Label, color = tint, maxLines = 1, modifier = Modifier.weight(1f))
                            when {
                                s.profile.subscribed == false && vm.base != null -> Text(
                                    "Оформить", style = Type.Label, color = Nf.Ink,
                                    modifier = Modifier.clip(CircleShape).background(Nf.Amber)
                                        .pressable(0.92f) { vm.base?.let { b -> openUrl(ctx, "$b/support/index") } }
                                        .padding(horizontal = 11.dp, vertical = 5.dp),
                                )
                                badge.detail != null -> Text(
                                    badge.detail, style = Type.Caption.copy(fontSize = 12.5.sp),
                                    color = Nf.Text.copy(alpha = 0.9f), maxLines = 1,
                                )
                            }
                        }
                    }

                    // Live collection snapshot from already-loaded lists (tapping opens full WatchStatsSheet).
                    var statsOpen by remember { mutableStateOf(false) }
                    if (lists.loaded && lists.titles.isNotEmpty()) {
                        val watching = remember(lists) { lists.count(ListStatus.Watching) }
                        val completed = remember(lists) { lists.count(ListStatus.Completed) }
                        val episodes = remember(lists) { lists.titles.values.sumOf { it.entry.watched } }
                        Row(
                            Modifier.padding(top = 10.dp).fillMaxWidth().pressable(0.98f) { statsOpen = true },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ProfileStatTile(formatCount(watching), "Смотрю", Nf.Mint, Modifier.weight(1f))
                            ProfileStatTile(formatCount(completed), "Просмотрено", statusColor(ListStatus.Completed), Modifier.weight(1f))
                            ProfileStatTile(formatCount(episodes), "Серий", Nf.Violet, Modifier.weight(1f))
                        }
                    }
                    if (statsOpen) {
                        WatchStatsSheet(profile = s.profile, lists = lists, onDismiss = { statsOpen = false })
                    }

                    vm.base?.let { b ->
                        Row(
                            Modifier.padding(top = 12.dp).fillMaxWidth().height(42.dp).clip(RoundedCornerShape(14.dp))
                                .background(Nf.Ink.copy(alpha = 0.55f)).border(1.dp, Nf.Line, RoundedCornerShape(14.dp))
                                .pressable(0.97f) { openUrl(ctx, "$b/users/profile") }.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = Nf.TextDim, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Профиль на сайте", style = Type.Label, color = Nf.Text)
                        }
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

@Composable
private fun ProfileStatTile(value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Nf.Ink.copy(alpha = 0.48f))
            .border(1.dp, Color(0x0DFFFFFF), RoundedCornerShape(14.dp)).padding(vertical = 9.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = Type.Title.copy(fontSize = 16.sp), color = Nf.Text, maxLines = 1)
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(4.dp))
            Text(label, style = Type.Caption.copy(fontSize = 11.sp), color = Nf.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
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

/**
 * The account's video channel. Anime 365 serves video through several networks; from some places one of them
 * doesn't get through at all, and then nothing plays. Like the addresses above: the app can keep the fastest
 * working one by itself, or the viewer picks one.
 */
@Composable
private fun ChannelCard(vm: SettingsVm) {
    val st by vm.channel.collectAsStateWithLifecycle()
    val auto by vm.channelAuto.collectAsStateWithLifecycle(true)
    val setup = st.setup
    val allMeasured = setup != null && st.speeds.size == setup.channels.size
    val working = setup?.channels?.count { st.speeds[it.id]?.works == true } ?: 0
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Канал видео", style = Type.Title, color = Nf.Text)
                Text(
                    when {
                        setup == null -> "Узнаю каналы…"
                        st.testing -> "Проверяю скорость…"
                        allMeasured && working == 0 -> "Сейчас ни один не отвечает"
                        allMeasured -> "Работают $working из ${setup.channels.size}"
                        else -> "Через какую сеть Anime 365 отдаёт видео"
                    },
                    style = Type.Caption, color = if (allMeasured && working == 0) Nf.Red else Nf.TextMute,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Nf.Ink).pressable(0.88f, enabled = setup != null && !st.testing) { vm.testChannels() },
                contentAlignment = Alignment.Center,
            ) {
                if (st.testing) CircularProgressIndicator(Modifier.size(18.dp), color = Nf.TextDim, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Speed, "Проверить скорость каналов", tint = Nf.TextDim, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Segmented(listOf(true to "Автовыбор", false to "Вручную"), auto) { vm.setChannelAuto(it) }
        Text(
            if (auto) "Приложение само проверяет канал и включает самый быстрый, если текущий не работает в вашей сети"
            else "Используется выбранный канал. Если видео не запустится, плеер всё равно подберёт рабочий",
            style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
        )
        Column(Modifier.animateContentSize()) {
            if (setup == null) Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp)).shimmer())
            else setup.channels.forEach { ch ->
                ChannelRow(
                    ch, active = ch.id == setup.current, auto = auto, byApp = st.pickedByApp, speed = st.speeds[ch.id],
                    measuring = ch.id in st.measuring, switching = st.switching == ch.id,
                    fastest = allMeasured && working > 1 && st.fastest?.id == ch.id,
                ) { vm.pickChannel(ch) }
            }
        }
        var lastNote by remember { mutableStateOf("") }
        if (st.note != null) lastNote = st.note!!
        AnimatedVisibility(st.note != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(lastNote, style = Type.Caption, color = Nf.TextDim, modifier = Modifier.padding(top = 10.dp, start = 2.dp))
        }
        // Manual mode after a test that found something faster: one tap to use it.
        val best: VideoChannel? = st.fastest
        AnimatedVisibility(
            !auto && !st.testing && best != null && setup != null && best.id != setup.current,
            enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(),
        ) {
            val b: VideoChannel = best ?: return@AnimatedVisibility
            Row(
                Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nf.Mint.copy(alpha = 0.12f))
                    .border(1.dp, Nf.Mint.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .pressable(0.98f) { vm.pickChannel(b) }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Check, null, tint = Nf.Mint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Включить самый быстрый — «${b.label}»", style = Type.Label, color = Nf.Mint, modifier = Modifier.weight(1f))
            }
        }
        setup?.fromRussia?.let { ru ->
            Text(
                if (ru) "Anime 365 хранит отдельный канал для входа из России без VPN — здесь он. На сайте через VPN может стоять другой"
                else "Вы заходите не из России (или через VPN): здесь канал для этого случая. Для входа из России без VPN на сайте хранится отдельный",
                style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 12.dp, start = 2.dp),
            )
        }
    }
}

@Composable
private fun ChannelRow(
    ch: VideoChannel, active: Boolean, auto: Boolean, byApp: Boolean, speed: ChannelSpeed?, measuring: Boolean, switching: Boolean,
    fastest: Boolean, onPick: () -> Unit,
) {
    val bg by animateColorAsState(if (active) Nf.Violet.copy(alpha = 0.12f) else Color.Transparent, label = "ch")
    val dot by animateColorAsState(
        when {
            speed == null -> Nf.Line
            speed.works -> Nf.Mint
            else -> Nf.Red
        },
        label = "chd",
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).clickable(onClick = onPick)
            .padding(start = 10.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ch.label, style = Type.Title.copy(fontSize = 15.sp), color = if (speed?.works == false) Nf.TextMute else Nf.Text, maxLines = 1)
                if (fastest) Badge("быстрее всех", Nf.Mint)
            }
            Text(
                when {
                    active && auto -> "${ch.name} · выбран автоматически"
                    active && byApp -> "${ch.name} · выбран приложением"
                    active -> "${ch.name} · выбран вами"
                    else -> ch.name
                },
                style = Type.Caption, color = if (active) Nf.Violet else Nf.TextMute, maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            switching || measuring -> CircularProgressIndicator(Modifier.size(16.dp), color = Nf.TextDim, strokeWidth = 2.dp)
            speed != null -> Text(speed.label, style = Type.Label, color = if (speed.works) Nf.Mint else Nf.Red)
            active -> Icon(Icons.Rounded.Check, null, tint = Nf.Violet, modifier = Modifier.size(18.dp))
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

/** One quality choice: the height big, a word under it, and bars that show how sharp it is. */
@Composable
private fun RowScope.QualityTile(height: Int, caption: String, bars: Int, selected: Int, onPick: (Int) -> Unit) {
    val on = height == selected
    val bg by animateColorAsState(if (on) Nf.Violet.copy(alpha = 0.16f) else Nf.Ink, label = "qt")
    val br by animateColorAsState(if (on) Nf.Violet.copy(alpha = 0.6f) else Nf.Line, label = "qtb")
    Column(
        Modifier.weight(1f).height(92.dp).clip(RoundedCornerShape(18.dp)).background(bg).border(1.dp, br, RoundedCornerShape(18.dp))
            .pressable(0.95f) { onPick(height) }.padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            repeat(3) { i ->
                val lit = i < bars
                Box(
                    Modifier.width(5.dp).height((6 + i * 4).dp).clip(RoundedCornerShape(2.dp))
                        .background(if (lit) (if (on) Nf.Violet else Nf.TextDim) else Nf.Line)
                )
            }
        }
        Column {
            Text("${height}p", style = Type.Title.copy(fontSize = 17.sp), color = if (on) Nf.Text else Nf.TextDim)
            Text(caption, style = Type.Caption, color = if (on) Nf.Violet else Nf.TextMute, maxLines = 1)
        }
    }
}

/** One skip length: the matching rewind icon in a small square, the length, and what it's good for. */
@Composable
private fun RowScope.StepTile(sec: Int, icon: ImageVector, caption: String, selected: Int, onPick: (Int) -> Unit) {
    val on = sec == selected
    val bg by animateColorAsState(if (on) Nf.Violet.copy(alpha = 0.16f) else Nf.Ink, label = "st")
    val br by animateColorAsState(if (on) Nf.Violet.copy(alpha = 0.6f) else Nf.Line, label = "stb")
    val box by animateColorAsState(if (on) Nf.Violet.copy(alpha = 0.22f) else Nf.Surface, label = "sti")
    Row(
        Modifier.weight(1f).height(68.dp).clip(RoundedCornerShape(18.dp)).background(bg).border(1.dp, br, RoundedCornerShape(18.dp))
            .pressable(0.95f) { onPick(sec) }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(box), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (on) Nf.Violet else Nf.TextDim, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("$sec секунд", style = Type.Title.copy(fontSize = 15.sp), color = if (on) Nf.Text else Nf.TextDim, maxLines = 1)
            Text(caption, style = Type.Caption, color = if (on) Nf.Violet else Nf.TextMute, maxLines = 1)
        }
    }
}

/** A setting that is either on or off: icon, what it does, and a switch. The whole row toggles it. */
@Composable
private fun ToggleRow(icon: ImageVector, title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tint by animateColorAsState(if (checked) Nf.Mint else Nf.TextDim, label = "tg")
    val box by animateColorAsState(if (checked) Nf.Mint.copy(alpha = 0.14f) else Nf.Ink, label = "tgb")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(box), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text)
            Text(hint, style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Nf.Ink, checkedTrackColor = Nf.Mint, checkedBorderColor = Nf.Mint,
                uncheckedThumbColor = Nf.TextMute, uncheckedTrackColor = Nf.Ink, uncheckedBorderColor = Nf.Line,
            ),
        )
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
        if (w > 0) Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(seg).fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(Nf.Violet.copy(alpha = 0.24f)))
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
