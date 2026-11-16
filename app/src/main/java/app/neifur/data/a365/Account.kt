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

    private suspend fun signIn(p: Profile) {
        runCatching { beforeSignIn(p) }
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
                    if (p.isSuccess && p.getOrNull() == null) { _expired.value = true; _state.value = AccountState.SignedOut }
                    checking = false
                }
            }
        }
    }

    /** Re-checks the saved session; a network failure keeps the last known state instead of signing out. */
    fun refresh() = scope.launch {
        runCatching { api.profile() }
            .onSuccess { if (it != null) signIn(it) else _state.value = AccountState.SignedOut }
            .onFailure { if (_state.value == AccountState.Unknown) _state.value = AccountState.SignedOut }
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
        _state.value = AccountState.SignedOut
    }
}
