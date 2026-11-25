package app.neifur.data.a365

import app.neifur.core.userMessage
import app.neifur.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ChannelState(
    val setup: ChannelSetup? = null,
    val speeds: Map<Int, ChannelSpeed> = emptyMap(),
    /** Channels being measured right now. */
    val measuring: Set<Int> = emptySet(),
    val switching: Int? = null,
    /** The last thing worth telling: what was switched, or why it couldn't be. */
    val note: String? = null,
    /** The current channel was chosen by the app, not by hand. */
    val pickedByApp: Boolean = false,
) {
    val testing: Boolean get() = measuring.isNotEmpty()
    val fastest: VideoChannel? get() = setup?.channels?.filter { speeds[it.id]?.works == true }?.maxByOrNull { speeds.getValue(it.id).bytesPerSec }
}

/** Chooses whether to switch channels: always leaves a broken channel, or moves on open failure to a noticeably faster one. */
internal fun pickBetterChannel(
    currentId: Int?,
    currentSpeed: ChannelSpeed?,
    best: VideoChannel?,
    bestSpeed: ChannelSpeed?,
    preferSwitch: Boolean,
): VideoChannel? {
    if (best == null || bestSpeed?.works != true) return null
    if (best.id == currentId) return null
    if (currentSpeed?.works != true) return best
    if (preferSwitch && bestSpeed.bytesPerSec > currentSpeed.bytesPerSec * 1.25) return best
    return null
}

/**
 * Looks after the account's Anime 365 video channel. In auto mode (the default) the app checks, once in a while
 * and whenever video won't start, that the channel gets through from this network, and moves the account to the
 * fastest one that does. Checks are cheap: only the current channel is sampled unless it fails; the full test
 * measures all of them and is run when asked (Settings) or when switching is needed.
 */
class ChannelPilot(
    private val api: Anime365,
    private val prefs: Prefs,
    private val account: Account,
    private val scope: CoroutineScope,
    /** On mobile data (or another metered network): no background measuring, and lighter tests. */
    private val metered: () -> Boolean = { false },
) {
    private val _state = MutableStateFlow(ChannelState())
    val state: StateFlow<ChannelState> = _state
    private val lock = Mutex()

    init {
        scope.launch {
            account.state.collect { s ->
                when (s) {
                    is AccountState.SignedIn -> { _state.value = ChannelState(); checkInBackground() }
                    AccountState.SignedOut -> _state.value = ChannelState()
                    AccountState.Unknown -> {}
                }
            }
        }
    }

    suspend fun load(): ChannelSetup? {
        val s = runCatching { api.channels() }.getOrNull()
        if (s != null) _state.update { it.copy(setup = s) }
        return s
    }

    /** At most every few hours: is the channel still getting through? Fix it quietly if not. */
    private fun checkInBackground() = scope.launch {
        if (!prefs.channelAuto.first()) { load(); return@launch }
        val now = System.currentTimeMillis()
        if (now - prefs.channelCheckedAt() < BACKGROUND_EVERY) { load(); return@launch }
        // Not on the viewer's mobile data: the check waits for Wi-Fi (video that won't start is still fixed at once).
        if (metered()) { load(); return@launch }
        ensureWorking()
        prefs.setChannelCheckedAt(now)
    }

    /** Re-checks the current channel after switching networks (Wi-Fi <-> VPN <-> another Wi-Fi), locking onto a working one. */
    fun onNetworkChanged() = scope.launch {
        if (account.state.value !is AccountState.SignedIn) return@launch
        if (!prefs.channelAuto.first()) return@launch
        if (metered()) return@launch
        ensureWorking(preferSwitch = false)
        prefs.setChannelCheckedAt(System.currentTimeMillis())
    }

    /**
     * Makes sure video can come through: samples the current channel and, if it doesn't get through (or when
     * [preferSwitch] is true after an open failure, tests all in parallel and moves if another is clearly better).
     * Returns (old, new) when it switched.
     */
    suspend fun ensureWorking(preferSwitch: Boolean = false): Pair<VideoChannel?, VideoChannel>? = lock.withLock {
        val setup = load() ?: return@withLock null
        val current = setup.currentChannel
        if (!preferSwitch) {
            if (current != null) {
                // A second try before blaming the channel: one slow moment of the network is not a reason to switch.
                if (sample(current, quick = true).works) return@withLock null
                if (sample(current, quick = true).works) return@withLock null
            }
            val others = setup.channels.filter { it.id != setup.current }
            coroutineScope { others.map { ch -> async { sample(ch, quick = true) } }.awaitAll() }
            val best = _state.value.fastest ?: return@withLock null
            if (best.id == setup.current) return@withLock null
            switchTo(best, byApp = true, why = current?.let { "«${it.label}» не работает в вашей сети" })
            prefs.setChannelCheckedAt(System.currentTimeMillis())
            return@withLock current to best
        }
        coroutineScope { setup.channels.map { ch -> async { sample(ch, quick = true) } }.awaitAll() }
        val st = _state.value
        val curSpeed = current?.let { st.speeds[it.id] }
        val best = st.fastest
        val bestSpeed = best?.let { st.speeds[it.id] }
        val target = pickBetterChannel(setup.current, curSpeed, best, bestSpeed, preferSwitch = true) ?: return@withLock null
        switchTo(
            target,
            byApp = true,
            why = if (curSpeed?.works != true) current?.let { "«${it.label}» не работает в вашей сети" } else null,
        )
        prefs.setChannelCheckedAt(System.currentTimeMillis())
        current to target
    }

    /** Measures every channel at once (Settings). In auto mode, then moves to the fastest if it's clearly better. */
    fun testAll() = scope.launch {
        lock.withLock {
            val setup = _state.value.setup ?: load() ?: return@withLock
            _state.update { it.copy(speeds = emptyMap(), note = null) }
            val light = metered()
            coroutineScope { setup.channels.map { ch -> async { sample(ch, quick = light) } }.awaitAll() }
            // The current channel failed while others got through: give it one more chance before leaving it.
            setup.currentChannel?.let { cur ->
                if (_state.value.speeds[cur.id]?.works != true && _state.value.fastest != null) sample(cur, quick = true)
            }
            val st = _state.value
            val best = st.fastest
            val current = setup.currentChannel
            val curSpeed = current?.let { st.speeds[it.id] }
            when {
                best == null -> _state.update { it.copy(note = "Ни один канал сейчас не отвечает. Проверьте интернет") }
                best.id == setup.current -> _state.update { it.copy(note = "Используется самый быстрый канал") }
                prefs.channelAuto.first() && (curSpeed?.works != true || st.speeds[best.id]!!.bytesPerSec > curSpeed.bytesPerSec * 1.5) ->
                    switchTo(best, byApp = true, why = null)
                else -> _state.update { it.copy(note = null) }
            }
            prefs.setChannelCheckedAt(System.currentTimeMillis())
        }
    }

    /** A channel chosen by hand: the app stops choosing for the viewer. */
    fun pick(ch: VideoChannel) = scope.launch {
        if (_state.value.switching != null || ch.id == _state.value.setup?.current) return@launch
        prefs.setChannelAuto(false)
        switchTo(ch, byApp = false, why = null)
    }

    fun setAuto(on: Boolean) = scope.launch {
        prefs.setChannelAuto(on)
        if (on) testAll()
    }

    private suspend fun switchTo(ch: VideoChannel, byApp: Boolean, why: String?) {
        _state.update { it.copy(switching = ch.id) }
        runCatching { api.setChannel(ch.id) }
            .onSuccess {
                _state.update { s ->
                    s.copy(
                        setup = s.setup?.copy(current = ch.id), switching = null, pickedByApp = byApp,
                        note = listOfNotNull(why, if (byApp) "Включён самый быстрый — «${ch.label}»" else "Видео идёт через «${ch.label}»").joinToString(". "),
                    )
                }
            }
            .onFailure { e -> _state.update { it.copy(switching = null, note = e.userMessage()) } }
    }

    private suspend fun sample(ch: VideoChannel, quick: Boolean): ChannelSpeed {
        _state.update { it.copy(measuring = it.measuring + ch.id) }
        val sp = if (quick) api.measure(ch, seconds = 3, maxBytes = 2_000_000) else api.measure(ch)
        _state.update { it.copy(measuring = it.measuring - ch.id, speeds = it.speeds + (ch.id to sp)) }
        return sp
    }

    private companion object { const val BACKGROUND_EVERY = 6 * 60 * 60 * 1000L }
}
