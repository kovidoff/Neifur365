package app.neifur.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.neifur.BuildConfig
import app.neifur.alerts.EpisodeAlerts
import app.neifur.container
import app.neifur.ui.Nf
import app.neifur.ui.Type
import app.neifur.ui.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns new-episode notifications on or off. Turning on asks Android for permission first (13+); a refusal leaves
 * them off. The first check runs at once: it only notes where each title is, so nothing old gets announced.
 */
@Composable
fun rememberAlertsSwitch(onDenied: () -> Unit = {}): (Boolean) -> Unit {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    fun apply(on: Boolean) = scope.launch {
        c.prefs.setAlerts(on)
        EpisodeAlerts.schedule(ctx, on)
        if (on) EpisodeAlerts.checkNow(ctx)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) apply(true) else { scope.launch { c.prefs.dismissAlertsOffer() }; onDenied() }
    }
    return { on ->
        if (on && Build.VERSION.SDK_INT >= 33 && !EpisodeAlerts.permitted(ctx)) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        else apply(on)
    }
}

/** Whether Android lets the app post notifications, re-read whenever the screen comes back (from system settings). */
@Composable
private fun rememberPermitted(): Boolean {
    val ctx = LocalContext.current
    var permitted by remember { mutableStateOf(EpisodeAlerts.permitted(ctx)) }
    LifecycleResumeEffect(Unit) { permitted = EpisodeAlerts.permitted(ctx); onPauseOrDispose {} }
    return permitted
}

/**
 * Xiaomi, Redmi and POCO phones (MIUI / HyperOS) keep sideloaded apps from starting on their own, which also stops
 * the hourly check from coming back after a reboot. True: autostart is allowed; false: it is not; null: not such a
 * phone, or the system wouldn't say.
 */
private fun xiaomiAutostart(ctx: android.content.Context): Boolean? {
    if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return null
    return runCatching {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        val m = android.app.AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        m.invoke(ops, 10008, android.os.Process.myUid(), ctx.packageName) as Int == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrNull()
}

@Composable
private fun rememberAutostart(): Boolean? {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(xiaomiAutostart(ctx)) }
    LifecycleResumeEffect(Unit) { allowed = xiaomiAutostart(ctx); onPauseOrDispose {} }
    return allowed
}

/** MIUI's own autostart list; the app's system page when that screen isn't there. */
private fun openAutostart(ctx: android.content.Context) {
    val miui = Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
    val app = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))
    if (runCatching { ctx.startActivity(miui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure)
        runCatching { ctx.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun openSystemSettings(ctx: android.content.Context) {
    val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The "Уведомления" block of Settings. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlertsSettings(signedIn: Boolean) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val on by c.prefs.alerts.collectAsStateWithLifecycle(false)
    val myStudio by c.prefs.alertsMyStudio.collectAsStateWithLifecycle(false)
    val planned by c.prefs.alertsPlanned.collectAsStateWithLifecycle(false)
    val checked by c.prefs.alertsChecked.collectAsStateWithLifecycle(0L)
    val permitted = rememberPermitted()
    val autostart = rememberAutostart()
    var denied by remember { mutableStateOf(false) }
    val toggle = rememberAlertsSwitch(onDenied = { denied = true })
    val active = on && signedIn

    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Nf.Surface)
            .border(1.dp, Color(0x0DFFFFFF), RoundedCornerShape(24.dp)).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tint by animateColorAsState(if (active) Nf.Mint else Nf.TextMute, label = "bell")
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(if (active) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff, null, tint = tint, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Новые серии", style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text)
                Text(
                    if (signedIn) "Уведомление, когда у тайтла из «Смотрю» выходит серия"
                    else "Войдите в аккаунт: уведомления следят за вашим «Смотрю» на Anime 365",
                    style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = active, enabled = signedIn, onCheckedChange = { denied = false; toggle(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Nf.Ink, checkedTrackColor = Nf.Mint, checkedBorderColor = Nf.Mint,
                    uncheckedThumbColor = Nf.TextMute, uncheckedTrackColor = Nf.Ink, uncheckedBorderColor = Nf.Line,
                    disabledUncheckedTrackColor = Nf.Ink, disabledUncheckedBorderColor = Nf.Line, disabledUncheckedThumbColor = Nf.Line,
                ),
            )
        }

        // Android said no: say where to fix it, instead of a switch that silently does nothing.
        AnimatedVisibility((active && !permitted) || denied, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nf.Amber.copy(alpha = 0.1f))
                    .pressable(0.98f) { openSystemSettings(ctx) }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Android не разрешает уведомления приложению", style = Type.Caption, color = Nf.Amber, modifier = Modifier.weight(1f))
                Text("Разрешить", style = Type.Label, color = Nf.Amber)
            }
        }

        // Xiaomi without autostart: notifications stop after a reboot until the app is opened. Say so once it matters.
        AnimatedVisibility(active && permitted && autostart == false, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nf.Amber.copy(alpha = 0.1f))
                    .pressable(0.98f) { openAutostart(ctx) }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "После перезагрузки телефона уведомления не придут, пока не откроете Neifur. Включите автозапуск",
                    style = Type.Caption, color = Nf.Amber, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text("Включить", style = Type.Label, color = Nf.Amber)
            }
        }

        AnimatedVisibility(active, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(1.dp).background(Nf.Line.copy(alpha = 0.6f)))
                Text("Когда сообщать", style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeTile(Icons.Rounded.Bolt, "Сразу", "как серия появится на сайте", !myStudio, Nf.Mint) { scope.launch { c.prefs.setAlertsMyStudio(false) } }
                    ModeTile(Icons.Rounded.Mic, "В моём переводе", "когда её выложит ваша студия", myStudio, Nf.Pink) { scope.launch { c.prefs.setAlertsMyStudio(true) } }
                }

                Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(1.dp).background(Nf.Line.copy(alpha = 0.6f)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Онгоинги из «Запланировано»", style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text)
                        Text("Сообщать и о том, что вы собираетесь смотреть", style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = planned, onCheckedChange = { v -> scope.launch { c.prefs.setAlertsPlanned(v) } },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Nf.Ink, checkedTrackColor = Nf.Violet, checkedBorderColor = Nf.Violet,
                            uncheckedThumbColor = Nf.TextMute, uncheckedTrackColor = Nf.Ink, uncheckedBorderColor = Nf.Line,
                        ),
                    )
                }

                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Проверяем примерно раз в час" + if (checked > 0) " · последний раз ${timeOf(checked)}" else "",
                        style = Type.Caption, color = Nf.TextMute, modifier = Modifier.weight(1f),
                    )
                    // Debug builds: a long press pretends the newest episodes are new, to see the notifications.
                    Row(
                        Modifier.clip(CircleShape).background(Nf.Ink).border(1.dp, Nf.Line, CircleShape)
                            .combinedClickable(
                                onClick = { EpisodeAlerts.checkNow(ctx) },
                                onLongClick = if (BuildConfig.DEBUG) ({ EpisodeAlerts.checkNow(ctx, demo = true) }) else null,
                            )
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Refresh, null, tint = Nf.TextDim, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Проверить", style = Type.Label.copy(fontSize = 12.5.sp), color = Nf.TextDim)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ModeTile(icon: ImageVector, title: String, caption: String, selected: Boolean, tint: Color, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) tint.copy(alpha = 0.13f) else Nf.Ink, label = "mt")
    val br by animateColorAsState(if (selected) tint.copy(alpha = 0.55f) else Nf.Line, label = "mtb")
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(bg).border(1.dp, br, RoundedCornerShape(16.dp))
            .pressable(0.97f, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Icon(icon, null, tint = if (selected) tint else Nf.TextDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = Type.Label.copy(fontSize = 14.sp), color = if (selected) Nf.Text else Nf.TextDim, maxLines = 1)
        Text(caption, style = Type.Caption.copy(fontSize = 11.5.sp, lineHeight = 15.sp), color = Nf.TextMute, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun timeOf(ms: Long): String {
    val today = System.currentTimeMillis() / 86_400_000L == ms / 86_400_000L
    return SimpleDateFormat(if (today) "'в' HH:mm" else "d MMM 'в' HH:mm", Locale.forLanguageTag("ru")).format(Date(ms))
}

/**
 * Asked once, at the top of "Смотрю": would the viewer like to hear about new episodes? Either answer folds the
 * card away for good; the setting stays in Settings.
 */
@Composable
fun AlertsOffer(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(true) }
    val toggle = rememberAlertsSwitch()
    fun close(then: suspend () -> Unit) {
        shown = false
        scope.launch { delay(320); then() }
    }
    AnimatedVisibility(shown, modifier, enter = fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Nf.Mint.copy(alpha = 0.13f), Nf.Surface, Nf.Surface)))
                .border(1.dp, Nf.Mint.copy(alpha = 0.22f), RoundedCornerShape(22.dp)).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Nf.Mint.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.NotificationsActive, null, tint = Nf.Mint, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Сообщать о новых сериях?", style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text)
                    Text(
                        "Пришлём уведомление, когда у тайтла из «Смотрю» выйдет серия",
                        style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(CircleShape).background(Nf.Mint).pressable(0.95f) {
                        close { toggle(true) }
                    }.padding(horizontal = 18.dp, vertical = 10.dp),
                ) { Text("Включить", style = Type.Label, color = Nf.Ink) }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.clip(CircleShape).pressable(0.95f) { close { c.prefs.dismissAlertsOffer() } }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) { Text("Не сейчас", style = Type.Label, color = Nf.TextDim) }
            }
        }
    }
}
