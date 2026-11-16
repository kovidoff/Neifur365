package app.neifur.data.a365

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface AccountState {
    /** Checking a saved session at start-up. */
    data object Unknown : AccountState
    data object SignedOut : AccountState
    data class SignedIn(val profile: Profile) : AccountState
}

/** The viewer's Anime 365 account, shared by every screen. */
class Account(
    private val api: Anime365,
    private val prefs: app.neifur.data.Prefs,
    private val scope: CoroutineScope,
    /** Runs before an account is shown as signed in (the app moves its per-account data over to it). */
    private val beforeSignIn: suspend (Profile) -> Unit = {},
) {
    private val _state = MutableStateFlow<AccountState>(AccountState.Unknown)
    val state: StateFlow<AccountState> = _state

    val profile: Profile? get() = (state.value as? AccountState.SignedIn)?.profile

    /** The site ended the session on its own: screens say so instead of a plain "sign in". Cleared on sign-in. */
    private val _expired = MutableStateFlow(false)
    val expired: StateFlow<Boolean> = _expired
    @Volatile private var checking = false

    /** The start-up check couldn't reach the site (offline): the state shown is a guess until it answers. */
    @Volatile var unconfirmed = false
        private set

    private suspend fun signIn(p: Profile) {
        runCatching { beforeSignIn(p) }
        runCatching { prefs.saveProfile(p) }
        unconfirmed = false
        _expired.value = false
        _state.value = AccountState.SignedIn(p)
    }

    init {
        refresh()
        // A request found the session gone: confirm once (a single page can lie) and show the app as signed out.
        api.onSessionLost = {
            if (!checking && state.value is AccountState.SignedIn) {
                checking = true
                scope.launch {
                    val p = runCatching { api.profile() }
                    if (p.isSuccess && p.getOrNull() == null) {
                        _expired.value = true; unconfirmed = false; _state.value = AccountState.SignedOut
                        runCatching { prefs.saveProfile(null) }
                    }
                    checking = false
                }
            }
        }
    }

    /**
     * Re-checks the saved session. A network failure keeps the last known state instead of signing out; at start-up
     * it shows the account last seen (if any) until the site can confirm it.
     */
    fun refresh() = scope.launch {
        runCatching { api.profile() }
            .onSuccess {
                if (it != null) signIn(it)
                else { unconfirmed = false; _state.value = AccountState.SignedOut; runCatching { prefs.saveProfile(null) } }
            }
            .onFailure {
                if (_state.value == AccountState.Unknown) {
                    val last = runCatching { prefs.savedProfile() }.getOrNull()
                    // Either way the site hasn't said yet: ask again once the network is back.
                    unconfirmed = true
                    _state.value = if (last != null) AccountState.SignedIn(last) else AccountState.SignedOut
                }
            }
    }

    suspend fun login(email: String, password: String): Profile =
        api.login(email, password).also { signIn(it) }

    /** After signing in through the site page: take its cookies and confirm who we are. */
    suspend fun adoptBrowserSession(cookieHeader: String): Profile? {
        api.cookies.import(cookieHeader)
        val p = runCatching { api.profile() }.getOrNull()
        if (p != null) signIn(p)
        return p
    }

    /** Signs out of the app and of the in-app site page too, so the next site sign-in can be another account. */
    fun logout() {
        api.logout()
        runCatching { android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() } }
        _expired.value = false
        unconfirmed = false
        _state.value = AccountState.SignedOut
        scope.launch { runCatching { prefs.saveProfile(null) } }
    }
}
