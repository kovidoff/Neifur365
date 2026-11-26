package app.neifur

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.app.NotificationManagerCompat
import app.neifur.core.buildHttpClient
import app.neifur.player.PlayerActivity
import app.neifur.data.OfflineCache
import app.neifur.data.Prefs
import app.neifur.data.SavedAnime
import app.neifur.data.aniskip.AniSkipApi
import app.neifur.data.a365.Account
import app.neifur.data.a365.AccountState
import app.neifur.data.a365.Lists
import app.neifur.data.a365.ChannelPilot
import app.neifur.data.a365.Anime365
import app.neifur.data.a365.Episode
import app.neifur.data.a365.Series
import app.neifur.downloads.DownloadManager
import app.neifur.data.shikimori.ShikimoriApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The little a screen needs to draw a title before its full data arrives. */
data class AnimeStub(
    val id: String, val title: String, val poster: String?, val kind: String? = null,
    val year: Int? = null, val score: Double? = null, val malId: Long = 0L,
) {
    fun saved() = SavedAnime(id, title, poster, kind, year, score, System.currentTimeMillis())
}

fun Series.stub() = AnimeStub(
    if (id <= 0L && myAnimeListId > 0L) "mal:$myAnimeListId" else id.toString(),
    title, posterUrl ?: posterUrlSmall, type, year.takeIf { it > 0 }, score, myAnimeListId,
)

/**
 * What the player was asked to play: the title's episodes, where to start, and which translation
 * ([pick], a studio key) the viewer chose. Each episode resolves its own translation of that studio.
 */
data class PlaySession(val anime: AnimeStub, val episodes: List<Episode>, val index: Int, val pick: String?, val pickName: String? = null)

class Container(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http = buildHttpClient()
    val prefs = Prefs(app)
    val offlineCache = OfflineCache(app)
    val aniSkip = AniSkipApi(http)
    val shikimori = ShikimoriApi(http, offlineCache)
    val a365 = Anime365(app, http, prefs)
    val account = Account(a365, prefs, scope) { p ->
        // Another account than last time: its own history, picks and marks come in, the previous one's are put
        // aside, and nothing shown so far (new-episode notifications, a half-restored player) is about it.
        if (prefs.claimFor(p.id)) {
            NotificationManagerCompat.from(app).cancelAll()
            app.cacheDir.resolve(PlayerActivity.SESSION_FILE).delete()
        }
    }
    val lists = Lists(a365, account, scope, offlineCache)

    /**
     * Fingerprint of the active network (e.g. Wi-Fi vs LTE vs VPN or network handoff). Empty when offline.
     * Re-evaluates the best Anime 365 mirror and video channel automatically whenever the user moves to another network.
     */
    val networkKey: StateFlow<String> = callbackFlow {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        fun currentKey(): String {
            val net = cm.activeNetwork ?: return ""
            val caps = cm.getNetworkCapabilities(net) ?: return net.toString()
            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val cell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            return "$net|wifi=$wifi|cell=$cell|vpn=$vpn"
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(currentKey()) }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { trySend(currentKey()) }
            override fun onLost(network: Network) { trySend(currentKey()) }
        }
        trySend(currentKey())
        cm.registerDefaultNetworkCallback(cb)
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.stateIn(scope, SharingStarted.Eagerly, "")

    /** Whether the phone has a network right now. Coming back online, what failed for lack of it is retried. */
    val online: StateFlow<Boolean> = networkKey
        .map { it.isNotEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val downloads = DownloadManager(app, a365, prefs, http, scope, networkKey)
    val channels = ChannelPilot(a365, prefs, account, scope) {
        app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
    }

    private val outboxLock = Mutex()

    init {
        // Started offline: confirm the account with the site once there is a network, then load its lists.
        scope.launch {
            online.collect { on ->
                if (on && account.unconfirmed) {
                    val before = account.state.value
                    account.refresh().join()
                    // Same account as guessed: the state didn't change, so the lists need asking for themselves.
                    if (!account.unconfirmed && account.state.value == before) lists.refresh()
                }
            }
        }
        // Offline watched outbox: sync episode marks made while offline once the network & account are ready.
        scope.launch {
            combine(online, account.state, prefs.pendingWatched) { on, acc, pending ->
                Triple(on, acc is AccountState.SignedIn, pending)
            }.collectLatest { (on, signedIn, pending) ->
                if (!on || !signedIn || pending.isEmpty()) return@collectLatest
                delay(1200)
                outboxLock.withLock {
                    val touchedAnime = LinkedHashSet<Long>()
                    for (item in pending) {
                        val ok = runCatching { a365.markWatched(item.translationId) }.isSuccess
                        if (ok) {
                            prefs.removePendingWatched(item.translationId)
                            if (item.animeId > 0L) touchedAnime += item.animeId
                        } else if (!online.value) {
                            break
                        }
                    }
                    touchedAnime.forEach { lists.reload(it) }
                }
            }
        }
        // Switched Wi-Fi / LTE / VPN: re-verify the active mirror and video channel in background.
        scope.launch {
            var lastKey = networkKey.value
            networkKey.drop(1).collectLatest { key ->
                if (key.isEmpty() || key == lastKey) return@collectLatest
                lastKey = key
                delay(1500)
                launch { runCatching { a365.ensureMirrorWorking() } }
                channels.onNetworkChanged()
            }
        }
    }

    val stubs = HashMap<String, AnimeStub>()
    fun remember(stub: AnimeStub) { stubs[stub.id] = stub }

    @Volatile var session: PlaySession? = null

    /** The player just closed: the main screen re-seats the system bars once it is back in portrait. */
    @Volatile var reseatBars = false

    /** A title to open once the app is on screen (tapped in a new-episode notification). */
    val pendingOpen = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Opens the Downloads screen once the app is on screen (tapped on the download notification). */
    val pendingDownloads = kotlinx.coroutines.flow.MutableStateFlow(false)
}

class NeifurApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        // Keeps the hourly new-episode check alive across updates and reboots (WorkManager restores it itself).
        container.scope.launch { if (container.prefs.alerts.first()) app.neifur.alerts.EpisodeAlerts.schedule(this@NeifurApp, true) }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("posters")).maxSizeBytes(256L shl 20).build() }
            .crossfade(220)
            .build()
}

val Context.container: Container get() = (applicationContext as NeifurApp).container

@Composable
inline fun <reified T : ViewModel> nfViewModel(
    key: String? = null,
    owner: androidx.lifecycle.ViewModelStoreOwner? = null,
    crossinline create: (Container) -> T,
): T {
    val c = LocalContext.current.container
    val store = owner ?: checkNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current)
    return viewModel(viewModelStoreOwner = store, key = key, factory = viewModelFactory { initializer { create(c) } })
}
