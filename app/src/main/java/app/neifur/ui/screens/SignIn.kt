@file:OptIn(ExperimentalMaterial3Api::class)

package app.neifur.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.neifur.Container
import app.neifur.core.userMessage
import app.neifur.data.a365.Profile
import app.neifur.nfViewModel
import app.neifur.ui.LocalAccent
import app.neifur.ui.Nf
import app.neifur.ui.PosterImage
import app.neifur.ui.Type
import app.neifur.ui.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SignInVm(private val c: Container) : ViewModel() {
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var profile by mutableStateOf(c.account.profile)
        private set
    /** Address of the mirror in use, for the in-app site page. */
    var base by mutableStateOf<String?>(null)
        private set
    val userAgent: String get() = c.a365.userAgent
    fun isSiteHost(host: String?) = c.a365.isSiteHost(host)

    init { viewModelScope.launch { base = runCatching { c.a365.baseUrl() }.getOrNull() } }

    fun login() {
        if (busy || email.isBlank() || password.isEmpty()) return
        busy = true; error = null
        viewModelScope.launch {
            runCatching { c.account.login(email, password) }
                .onSuccess { profile = it; password = "" }
                .onFailure { error = it.userMessage() }
            busy = false
        }
    }

    /** The site page signed in: take over its cookies. Returns false while the page still isn't signed in. */
    suspend fun adopt(cookies: String): Boolean {
        val p = c.account.adoptBrowserSession(cookies) ?: return false
        profile = p
        return true
    }
}

/** Shown when a signed-out viewer taps "Смотреть". */
@Composable
fun SignInSheet(onDismiss: () -> Unit, onDone: () -> Unit) {
    val vm = nfViewModel(key = "signin-sheet") { SignInVm(it) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(vm.profile) { if (vm.profile != null) { delay(1100); onDone() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nf.SurfaceHi, contentColor = Nf.Text,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(40.dp, 4.dp).clip(CircleShape).background(Nf.Line)) },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 22.dp).padding(top = 10.dp, bottom = 26.dp)) {
            val accent = LocalAccent.current
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.Login, null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Text("Войдите в Anime 365", style = Type.Display, color = Nf.Text, modifier = Modifier.padding(top = 16.dp))
            Text(
                "Видео на Anime 365 доступно только после входа. Списки и отметки о просмотре тоже синхронизируются с аккаунтом.",
                style = Type.Body, color = Nf.TextDim, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            SignInForm(vm, accent)
        }
    }
}

/** E-mail and password, or the site's own page for accounts made through VK, Google or Shikimori. */
@Composable
fun SignInForm(vm: SignInVm, accent: Color = Nf.Violet, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    var showPassword by remember { mutableStateOf(false) }
    var site by remember { mutableStateOf(false) }

    androidx.compose.animation.AnimatedContent(vm.profile, modifier, label = "signin") { p ->
        if (p != null) SignedInCard(p) else Column {
            Field(vm.email, { vm.email = it.trim() }, "E-mail", Icons.Rounded.AlternateEmail, vm.error != null,
                KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false))
            Spacer(Modifier.height(10.dp))
            Field(
                vm.password, { vm.password = it }, "Пароль", Icons.Rounded.Lock, vm.error != null,
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                KeyboardActions(onDone = { focus.clearFocus(); vm.login() }),
                if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            ) {
                Icon(
                    if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Показать пароль", tint = Nf.TextMute,
                    modifier = Modifier.size(42.dp).clip(CircleShape).clickable { showPassword = !showPassword }.padding(11.dp),
                )
            }
            var lastErr by remember { mutableStateOf("") }
            if (vm.error != null) lastErr = vm.error!!
            AnimatedVisibility(vm.error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(lastErr, style = Type.Label, color = Nf.Red, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Войти", enabled = vm.email.isNotBlank() && vm.password.isNotEmpty(), busy = vm.busy, color = accent) {
                focus.clearFocus(); vm.login()
            }
            Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(Nf.Line))
                Text("или", style = Type.Caption, color = Nf.TextMute, modifier = Modifier.padding(horizontal = 12.dp))
                Box(Modifier.weight(1f).height(1.dp).background(Nf.Line))
            }
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(CircleShape).border(1.dp, Nf.Line, CircleShape)
                    .pressable(0.97f, enabled = vm.base != null) { focus.clearFocus(); site = true },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Language, null, tint = Nf.Text, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("Войти через сайт", style = Type.Title.copy(fontSize = 15.sp), color = Nf.Text)
            }
            Text(
                "Для аккаунтов через VK, Google или Shikimori и для регистрации",
                style = Type.Caption, color = Nf.TextMute, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
    if (site) vm.base?.let { base -> SiteSignIn(base, vm, onClose = { site = false }) }
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, hint: String, icon: ImageVector, error: Boolean,
    keyboard: KeyboardOptions, actions: KeyboardActions = KeyboardActions.Default,
    visual: VisualTransformation = VisualTransformation.None, trailing: (@Composable () -> Unit)? = null,
) {
    val border by animateColorAsState(if (error) Nf.Red.copy(alpha = 0.6f) else Nf.Line, label = "fb")
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(Nf.Ink)
            .border(1.dp, border, RoundedCornerShape(18.dp)).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Nf.TextMute, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, style = Type.Body, color = Nf.TextMute)
            BasicTextField(
                value, onChange, singleLine = true, textStyle = Type.Body.copy(color = Nf.Text), cursorBrush = SolidColor(Nf.Violet),
                visualTransformation = visual, keyboardOptions = keyboard, keyboardActions = actions, modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun SignedInCard(p: Profile) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Nf.Mint.copy(alpha = 0.08f))
            .border(1.dp, Nf.Mint.copy(alpha = 0.28f), RoundedCornerShape(20.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterImage(p.avatarUrl, Modifier.size(46.dp).clip(CircleShape))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = Type.Title, color = Nf.Text, maxLines = 1)
            Text("Вы вошли в Anime 365", style = Type.Caption, color = Nf.Mint)
        }
    }
}

/**
 * The site's own sign-in page inside the app, for accounts made through VK, Google or Shikimori.
 *
 * The site signs in without reloading the page (its "dynpage" requests), so waiting for a page load to finish
 * misses it. Instead the page's cookies are watched while it is open: whenever they change, the app tries them
 * against the profile page, and a last try is made when the page is closed. The page and the app present the
 * same browser identity to the site.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SiteSignIn(base: String, vm: SignInVm, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var done by remember { mutableStateOf(false) }
    var lastTried by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    /** Cookies of the address the page is on now (sign-in through VK or Google can come back on another mirror). */
    fun currentCookies(): String? {
        val host = web?.url?.let { Uri.parse(it).host }?.takeIf { vm.isSiteHost(it) } ?: Uri.parse(base).host
        val cm = CookieManager.getInstance()
        cm.flush()
        return cm.getCookie("https://$host")
    }

    suspend fun tryAdopt(force: Boolean = false): Boolean {
        if (done || checking) return done
        val cookies = currentCookies() ?: return false
        if (!force && cookies == lastTried) return false
        lastTried = cookies
        checking = true
        val ok = runCatching { vm.adopt(cookies) }.getOrDefault(false)
        checking = false
        if (ok) done = true
        return ok
    }

    fun close() {
        scope.launch {
            tryAdopt(force = true)
            onClose()
        }
    }

    LaunchedEffect(Unit) {
        while (!done) {
            delay(1500)
            tryAdopt()
        }
        delay(1200)
        onClose()
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Nf.Ink).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Close, "Закрыть", tint = Nf.Text, modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = ::close).padding(10.dp))
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text("Вход на сайте", style = Type.Title, color = Nf.Text)
                    Text(Uri.parse(base).host.orEmpty(), style = Type.Caption, color = Nf.TextMute)
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (loading || checking) LinearProgressIndicator(Modifier.fillMaxSize(), color = Nf.Violet, trackColor = Color.Transparent)
            }
            Box(Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        CookieManager.getInstance().setAcceptCookie(true)
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = vm.userAgent
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { loading = true }
                                override fun onPageFinished(view: WebView, url: String) {
                                    loading = false
                                    if (vm.isSiteHost(Uri.parse(url).host)) scope.launch { tryAdopt() }
                                }
                            }
                            loadUrl("$base/users/login")
                            web = this
                        }
                    },
                )
                // Success: say so over the page, then the sheet takes over with the signed-in card.
                androidx.compose.animation.AnimatedVisibility(done, enter = fadeIn(), exit = fadeOut()) {
                    Box(Modifier.fillMaxSize().background(Nf.Ink.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
                        vm.profile?.let { SignedInCard(it) }
                    }
                }
            }
            Text(
                if (done) "Готово — вы вошли" else "Войдите на странице любым способом — приложение подхватит вход само",
                style = Type.Caption, color = if (done) Nf.Mint else Nf.TextMute, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Nf.Surface).padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

/** Big pill button used across the welcome flow and the sign-in sheet. Text changes cross-fade. */
@Composable
fun PrimaryButton(
    text: String, enabled: Boolean = true, busy: Boolean = false, color: Color = Nf.Violet, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (enabled) color else Nf.Line, label = "pb")
    val fg by animateColorAsState(if (enabled) Nf.Ink else Nf.TextMute, label = "pf")
    Box(
        modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(bg).pressable(0.97f, enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedContent(if (busy) "" else text, label = "pbt") { t ->
            if (t.isEmpty()) CircularProgressIndicator(Modifier.size(22.dp), color = fg, strokeWidth = 2.5.dp)
            else Text(t, style = Type.Title, color = fg)
        }
    }
}
