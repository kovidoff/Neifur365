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
class Account(private val api: Anime365, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<AccountState>(AccountState.Unknown)
    val state: StateFlow<AccountState> = _state

    val profile: Profile? get() = (state.value as? AccountState.SignedIn)?.profile

    init { refresh() }

    /** Re-checks the saved session; a network failure keeps the last known state instead of signing out. */
    fun refresh() = scope.launch {
        runCatching { api.profile() }
            .onSuccess { _state.value = if (it != null) AccountState.SignedIn(it) else AccountState.SignedOut }
            .onFailure { if (_state.value == AccountState.Unknown) _state.value = AccountState.SignedOut }
    }

    suspend fun login(email: String, password: String): Profile =
        api.login(email, password).also { _state.value = AccountState.SignedIn(it) }

    /** After signing in through the site page: take its cookies and confirm who we are. */
    suspend fun adoptBrowserSession(cookieHeader: String): Profile? {
        api.cookies.import(cookieHeader)
        val p = runCatching { api.profile() }.getOrNull()
        if (p != null) _state.value = AccountState.SignedIn(p)
        return p
    }

    /** Signs out of the app and of the in-app site page too, so the next site sign-in can be another account. */
    fun logout() {
        api.logout()
        runCatching { android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() } }
        _state.value = AccountState.SignedOut
    }
}
