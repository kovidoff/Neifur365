package app.neifur.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.neifur.core.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

private val Context.store by preferencesDataStore(
    name = "neifur",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** One episode's watch progress. The newest entry per anime drives "Continue watching". */
@Serializable
data class WatchEntry(
    val animeId: String,
    val title: String,
    val poster: String? = null,
    val episode: Int,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val updatedAt: Long = 0,
) {
    val progress: Float get() = if (durationSec > 0) (positionSec / durationSec).toFloat().coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = durationSec > 0 && positionSec / durationSec > 0.92
}

@Serializable
data class SavedAnime(
    val id: String, val title: String, val poster: String? = null, val kind: String? = null,
    val year: Int? = null, val score: Double? = null, val addedAt: Long = 0,
)

/** Episode mark queued offline and flushed to Anime 365 as soon as a connection returns. */
@Serializable
data class PendingWatch(val translationId: Long, val animeId: Long, val savedAt: Long = 0)

/** Portable backup of user settings and studio preferences. Never includes cookies, tokens or account data. */
@Serializable
data class SettingsBackup(
    val app: String = "Neifur365",
    val version: Int = 1,
    val exportedAt: Long = 0,
    val preferredKind: String = "VoiceRu",
    val favouriteStudios: List<Pair<String, String>> = emptyList(),
    val translationPicks: Map<String, String> = emptyMap(),
    val quality: Int = 1080,
    val subScale: Double = 1.0,
    val seekStep: Int = 10,
    val holdBoost: Boolean = true,
    val sideGestures: Boolean = true,
    val rememberBrightness: Boolean = false,
    val aniSkip: Boolean = false,
    val normalizeAudio: Boolean = false,
    val downloadQuality: Int = 0,
    val autoDeleteWatched: Boolean = false,
    val wifiOnlyDownloads: Boolean = false,
    val mirrorAuto: Boolean = true,
    val channelAuto: Boolean = true,
    val customMirrors: List<String> = emptyList(),
    val alertsEnabled: Boolean = false,
    val alertsMyStudio: Boolean = false,
    val alertsPlanned: Boolean = false,
    val favorites: List<SavedAnime> = emptyList(),
)

data class BackupImportSummary(val studios: Int, val picks: Int, val favorites: Int)

private val BackupJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

internal fun encodeSettingsBackup(b: SettingsBackup): String = BackupJson.encodeToString(SettingsBackup.serializer(), b)

internal fun decodeSettingsBackup(raw: String): SettingsBackup {
    val parsed = BackupJson.decodeFromString(SettingsBackup.serializer(), raw)
    require(parsed.app == "Neifur365" && parsed.version in 1..2) { "Неподдерживаемый файл резервной копии" }
    return parsed
}

class Prefs(private val context: Context) {
    private val KEY_MIRROR = stringPreferencesKey("mirror")
    private val KEY_CUSTOM_MIRRORS = stringPreferencesKey("custom_mirrors")
    private val KEY_MIRROR_AUTO = stringPreferencesKey("mirror_auto")
    private val KEY_CHANNEL_AUTO = stringPreferencesKey("channel_auto")
    private val KEY_CHANNEL_CHECKED = stringPreferencesKey("channel_checked")
    private val KEY_HISTORY = stringPreferencesKey("history")
    private val KEY_FAVORITES = stringPreferencesKey("favorites")
    private val KEY_QUALITY = intPreferencesKey("quality")
    private val KEY_PICKS = stringPreferencesKey("translation_picks")
    private val KEY_FAVOURITE_AUTHORS = stringPreferencesKey("favourite_authors")
    private val KEY_FAVOURITE_STUDIOS = stringPreferencesKey("favourite_studios")
    private val KEY_KIND = stringPreferencesKey("preferred_kind")
    private val KEY_AUDIO_PREF = stringPreferencesKey("audio_pref")
    private val KEY_SUB_PREF = stringPreferencesKey("sub_pref")
    private val KEY_SUB_SCALE = stringPreferencesKey("sub_scale")
    private val KEY_SEEK_STEP = intPreferencesKey("seek_step")
    private val KEY_HOLD_BOOST = stringPreferencesKey("hold_boost")
    private val KEY_SIDE_GESTURES = stringPreferencesKey("side_gestures")
    private val KEY_DOWNLOAD_QUALITY = intPreferencesKey("download_quality")
    private val KEY_AUTO_DELETE_DL = stringPreferencesKey("auto_delete_dl")
    private val KEY_REMEMBER_BRIGHTNESS = stringPreferencesKey("remember_brightness")
    private val KEY_BRIGHTNESS = stringPreferencesKey("player_brightness")
    private val KEY_ONBOARDED = stringPreferencesKey("onboarded")
    private val KEY_ALERTS = stringPreferencesKey("alerts")
    private val KEY_ALERTS_MODE = stringPreferencesKey("alerts_mode")
    private val KEY_ALERTS_PLANNED = stringPreferencesKey("alerts_planned")
    private val KEY_ALERTS_SEEN = stringPreferencesKey("alerts_seen")
    private val KEY_ALERTS_ASKED = stringPreferencesKey("alerts_asked")
    private val KEY_ALERTS_CHECKED = stringPreferencesKey("alerts_checked")
    private val KEY_OWNER = stringPreferencesKey("owner")
    private val KEY_STASH = stringPreferencesKey("account_stash")
    private val KEY_PROFILE = stringPreferencesKey("profile")
    private val KEY_RECENT_SEARCHES = stringPreferencesKey("recent_searches")
    private val KEY_ANISKIP = stringPreferencesKey("aniskip")
    private val KEY_NORMALIZE_AUDIO = stringPreferencesKey("normalize_audio")
    private val KEY_WIFI_ONLY_DL = stringPreferencesKey("wifi_only_dl")
    private val KEY_PENDING_WATCHED = stringPreferencesKey("pending_watched")

    /** What belongs to one Anime 365 account rather than to the phone: put aside when another account signs in. */
    private val accountKeys = listOf(KEY_HISTORY, KEY_PICKS, KEY_ALERTS_SEEN, KEY_ALERTS_CHECKED, KEY_CHANNEL_CHECKED, KEY_PENDING_WATCHED)
    private val stashSer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))

    private val historySer = ListSerializer(WatchEntry.serializer())
    private val favSer = ListSerializer(SavedAnime.serializer())
    private val pendingWatchSer = ListSerializer(PendingWatch.serializer())

    // --- mirrors -------------------------------------------------------------------------------------
    val mirror: Flow<String?> = context.store.data.map { it[KEY_MIRROR] }.distinctUntilChanged()
    suspend fun setMirror(host: String?) = context.store.edit { if (host == null) it.remove(KEY_MIRROR) else it[KEY_MIRROR] = host }

    val customMirrors: Flow<List<String>> = context.store.data
        .map { it[KEY_CUSTOM_MIRRORS]?.split('\n')?.filter(String::isNotBlank) ?: emptyList() }.distinctUntilChanged()
    suspend fun setCustomMirrors(hosts: List<String>) = context.store.edit { it[KEY_CUSTOM_MIRRORS] = hosts.joinToString("\n") }

    /** Auto: the app picks the fastest working address itself. Manual: stick to the one the viewer chose while it works. */
    val mirrorAuto: Flow<Boolean> = context.store.data.map { it[KEY_MIRROR_AUTO] != "0" }.distinctUntilChanged()
    suspend fun setMirrorAuto(auto: Boolean) = context.store.edit { it[KEY_MIRROR_AUTO] = if (auto) "1" else "0" }

    /** The app looks after the account's video channel itself (on by default). */
    val channelAuto: Flow<Boolean> = context.store.data.map { it[KEY_CHANNEL_AUTO] != "0" }.distinctUntilChanged()
    suspend fun setChannelAuto(auto: Boolean) = context.store.edit { it[KEY_CHANNEL_AUTO] = if (auto) "1" else "0" }

    /** When the channel was last checked in the background (ms). */
    suspend fun channelCheckedAt(): Long = context.store.data.first()[KEY_CHANNEL_CHECKED]?.toLongOrNull() ?: 0L
    suspend fun setChannelCheckedAt(ms: Long) = context.store.edit { it[KEY_CHANNEL_CHECKED] = ms.toString() }

    // --- whose data this is ---------------------------------------------------------------------------
    /**
     * Called before an account is shown as signed in. Another account than last time: the previous one's history,
     * studio picks, notification marks and channel check are put aside (and come back if it signs in again), and
     * the new one starts from its own or from nothing. Returns true when the account changed.
     */
    suspend fun claimFor(userId: Long): Boolean {
        var changed = false
        context.store.edit { p ->
            val me = userId.toString()
            val before = p[KEY_OWNER]
            if (before == me) return@edit
            p[KEY_OWNER] = me
            // The first sign-in after an update (no owner yet) keeps what is here: it was this viewer's.
            if (before == null) return@edit
            changed = true
            val stash = p[KEY_STASH]?.let { runCatching { AppJson.decodeFromString(stashSer, it) }.getOrNull() }.orEmpty().toMutableMap()
            stash.remove(before)
            stash[before] = accountKeys.mapNotNull { k -> p[k]?.let { k.name to it } }.toMap()
            val mine = stash.remove(me).orEmpty()
            accountKeys.forEach { k -> val v = mine[k.name]; if (v == null) p.remove(k) else p[k] = v }
            // A handful of accounts is plenty; the oldest set aside goes first.
            p[KEY_STASH] = AppJson.encodeToString(stashSer, stash.entries.toList().takeLast(4).associate { it.key to it.value })
        }
        return changed
    }

    /**
     * The account last seen signed in. Started without a connection, the app shows it instead of "sign in"
     * and confirms it with the site once the network is back.
     */
    suspend fun savedProfile(): app.neifur.data.a365.Profile? = context.store.data.first()[KEY_PROFILE]?.split('\t')?.let { f ->
        val id = f.getOrNull(0)?.toLongOrNull() ?: return@let null
        val sub = when (f.getOrNull(3)) { "1" -> true; "0" -> false; else -> null }
        val until = f.getOrNull(4)?.ifBlank { null }
        app.neifur.data.a365.Profile(id, f.getOrNull(1).orEmpty(), f.getOrNull(2)?.ifBlank { null }, sub, until)
    }

    suspend fun saveProfile(p: app.neifur.data.a365.Profile?) = context.store.edit {
        if (p == null) it.remove(KEY_PROFILE)
        else it[KEY_PROFILE] = listOf(
            p.id.toString(),
            p.name.replace('\t', ' '),
            p.avatarUrl.orEmpty(),
            when (p.subscribed) { true -> "1"; false -> "0"; null -> "" },
            p.subscribedUntil.orEmpty(),
        ).joinToString("\t")
    }

    // --- onboarding ----------------------------------------------------------------------------------
    /** Whether the welcome flow is done: "1" done, "0" in progress (or shown again from Settings). */
    val onboarded: Flow<Boolean> = context.store.data.map { it[KEY_ONBOARDED] == "1" }.distinctUntilChanged()
    suspend fun beginOnboarding() = context.store.edit { if (it[KEY_ONBOARDED] != "0") it[KEY_ONBOARDED] = "0" }
    suspend fun setOnboarded() = context.store.edit { it[KEY_ONBOARDED] = "1" }

    // --- new-episode notifications ---
    val alerts: Flow<Boolean> = context.store.data.map { it[KEY_ALERTS] == "1" }.distinctUntilChanged()
    suspend fun setAlerts(on: Boolean) = context.store.edit { it[KEY_ALERTS] = if (on) "1" else "0"; it[KEY_ALERTS_ASKED] = "1" }

    /** True: wait until the viewer's own studio has the episode. False: tell as soon as it's out in any translation. */
    val alertsMyStudio: Flow<Boolean> = context.store.data.map { it[KEY_ALERTS_MODE] == "studio" }.distinctUntilChanged()
    suspend fun setAlertsMyStudio(on: Boolean) = context.store.edit { it[KEY_ALERTS_MODE] = if (on) "studio" else "any" }

    /** Also airing titles from "Запланировано". */
    val alertsPlanned: Flow<Boolean> = context.store.data.map { it[KEY_ALERTS_PLANNED] == "1" }.distinctUntilChanged()
    suspend fun setAlertsPlanned(on: Boolean) = context.store.edit { it[KEY_ALERTS_PLANNED] = if (on) "1" else "0" }

    /** The Lists tab offered notifications once (accepted or not): it doesn't ask again. */
    val alertsAsked: Flow<Boolean> = context.store.data.map { it[KEY_ALERTS_ASKED] == "1" }.distinctUntilChanged()
    suspend fun dismissAlertsOffer() = context.store.edit { it[KEY_ALERTS_ASKED] = "1" }

    /** When the last check ran (ms), for "проверено в 10:42". */
    val alertsChecked: Flow<Long> = context.store.data.map { it[KEY_ALERTS_CHECKED]?.toLongOrNull() ?: 0L }.distinctUntilChanged()

    /** The newest episode already told about (or already out when tracking began), per title. */
    suspend fun alertsSeen(): Map<Long, Int> = context.store.data.first()[KEY_ALERTS_SEEN].orEmpty().lines().mapNotNull { l ->
        val id = l.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
        val n = l.substringAfter('\t').toIntOrNull() ?: return@mapNotNull null
        id to n
    }.toMap()

    suspend fun saveAlertsSeen(seen: Map<Long, Int>, checkedAt: Long) = context.store.edit { p ->
        p[KEY_ALERTS_SEEN] = seen.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        p[KEY_ALERTS_CHECKED] = checkedAt.toString()
    }

    // --- playback ------------------------------------------------------------------------------------
    /**
     * Preferred picture height: the player takes the best stream at or below it. Capped at what Anime 365 streams,
     * so an older "best" setting (9999) reads as 1080p.
     */
    val quality: Flow<Int> = context.store.data
        .map { minOf(it[KEY_QUALITY] ?: 1080, app.neifur.data.a365.MAX_STREAM_HEIGHT) }.distinctUntilChanged()
    suspend fun setQuality(q: Int) = context.store.edit { it[KEY_QUALITY] = q }

    /** What a new title starts with: Russian voice-over by default, or subtitles for those who prefer them. */
    val preferredKind: Flow<String> = context.store.data.map { it[KEY_KIND] ?: "VoiceRu" }.distinctUntilChanged()
    suspend fun setPreferredKind(kind: String) = context.store.edit { it[KEY_KIND] = kind }

    /** The translation picked for a title (its pick key), so the next visit and the next episode keep it. */
    suspend fun pick(animeId: String): String? =
        context.store.data.first()[KEY_PICKS]?.lineSequence()?.firstOrNull { it.startsWith("$animeId\t") }?.substringAfter('\t')

    /** Remembers the studio a title plays in. It does not make the studio a favourite: that is the viewer's call (★). */
    suspend fun setPick(animeId: String, key: String) = context.store.edit { p ->
        val lines = p[KEY_PICKS]?.lines()?.filterNot { it.startsWith("$animeId\t") } ?: emptyList()
        p[KEY_PICKS] = (listOf("$animeId\t$key") + lines).take(400).joinToString("\n")
    }

    /** Pick keys of the favourite studios, most recently starred first. */
    suspend fun favouriteAuthors(): List<String> = favouriteStudios.first().map { it.first }

    /**
     * (pick key, display name) of the studios the viewer starred. They come first in every translation list and
     * are what a new title starts in. Kept under a new key: the old list filled itself with every studio tried.
     */
    val favouriteStudios: Flow<List<Pair<String, String>>> = context.store.data
        .map { it[KEY_FAVOURITE_STUDIOS] }
        .distinctUntilChanged()
        .map { raw ->
            raw?.lines().orEmpty().filter { it.isNotBlank() }.map { l ->
                val key = l.substringBefore('\t')
                key to (l.substringAfter('\t', "").ifBlank { key.substringAfter('|').replaceFirstChar { it.uppercase() } })
            }
        }

    /** Stars or unstars a studio ([name] is how it's shown in Settings). */
    suspend fun toggleFavouriteStudio(key: String, name: String) = context.store.edit { p ->
        val cur = p[KEY_FAVOURITE_STUDIOS]?.lines().orEmpty().filter { it.isNotBlank() }
        val had = cur.any { it.substringBefore('\t') == key }
        val rest = cur.filterNot { it.substringBefore('\t') == key }
        p[KEY_FAVOURITE_STUDIOS] = (if (had) rest else listOf("$key\t$name") + rest).take(30).joinToString("\n")
        p.remove(KEY_FAVOURITE_AUTHORS)
    }

    suspend fun removeFavouriteStudio(key: String) = context.store.edit { p ->
        p[KEY_FAVOURITE_STUDIOS] = p[KEY_FAVOURITE_STUDIOS]?.lines().orEmpty().filterNot { it.substringBefore('\t') == key }.joinToString("\n")
    }

    /** How many titles have a translation chosen by hand. */
    val pickCount: Flow<Int> = context.store.data.map { p -> p[KEY_PICKS]?.lines()?.count { it.isNotBlank() } ?: 0 }.distinctUntilChanged()

    /** Forgets the studio chosen per title. Favourite studios stay: they were starred on purpose. */
    suspend fun clearPicks() = context.store.edit { it.remove(KEY_PICKS); it.remove(KEY_FAVOURITE_AUTHORS) }

    // --- history and favourites ----------------------------------------------------------------------
    val history: Flow<List<WatchEntry>> = context.store.data
        .map { it[KEY_HISTORY] }
        .distinctUntilChanged()
        .map { raw -> raw?.let { runCatching { AppJson.decodeFromString(historySer, it) }.getOrNull() } ?: emptyList() }

    val favorites: Flow<List<SavedAnime>> = context.store.data
        .map { it[KEY_FAVORITES] }
        .distinctUntilChanged()
        .map { raw -> raw?.let { runCatching { AppJson.decodeFromString(favSer, it) }.getOrNull() } ?: emptyList() }

    suspend fun saveProgress(e: WatchEntry) = context.store.edit { p ->
        val cur = p[KEY_HISTORY]?.let { runCatching { AppJson.decodeFromString(historySer, it) }.getOrNull() } ?: emptyList()
        val next = (listOf(e) + cur.filterNot { it.animeId == e.animeId && it.episode == e.episode }).take(600)
        p[KEY_HISTORY] = AppJson.encodeToString(historySer, next)
    }

    /** Forgets everything watched on this phone, and the studios chosen for those titles: they open as new again. */
    suspend fun clearHistory() = context.store.edit { p ->
        val ids = p[KEY_HISTORY]?.let { runCatching { AppJson.decodeFromString(historySer, it) }.getOrNull() }.orEmpty().map { it.animeId }.toSet()
        p.remove(KEY_HISTORY)
        p[KEY_PICKS]?.let { picks -> p[KEY_PICKS] = picks.lines().filterNot { it.substringBefore('\t') in ids }.joinToString("\n") }
    }

    /** Forgets one title: its progress here and its chosen studio, so the title page treats it as new. */
    suspend fun removeFromHistory(animeId: String) = context.store.edit { p ->
        val cur = p[KEY_HISTORY]?.let { runCatching { AppJson.decodeFromString(historySer, it) }.getOrNull() } ?: emptyList()
        p[KEY_HISTORY] = AppJson.encodeToString(historySer, cur.filterNot { it.animeId == animeId })
        p[KEY_PICKS]?.let { picks -> p[KEY_PICKS] = picks.lines().filterNot { it.startsWith("$animeId\t") }.joinToString("\n") }
    }

    suspend fun toggleFavorite(a: SavedAnime) = context.store.edit { p ->
        val cur = p[KEY_FAVORITES]?.let { runCatching { AppJson.decodeFromString(favSer, it) }.getOrNull() } ?: emptyList()
        val next = if (cur.any { it.id == a.id }) cur.filterNot { it.id == a.id } else listOf(a) + cur
        p[KEY_FAVORITES] = AppJson.encodeToString(favSer, next)
    }

    // --- tracks inside the file (subtitle studios ship several) ---------------------------------------
    fun trackPrefFlow(type: String): Flow<String?> =
        context.store.data.map { it[if (type == "audio") KEY_AUDIO_PREF else KEY_SUB_PREF] }.distinctUntilChanged()

    val subScaleFlow: Flow<Double> = context.store.data.map { it[KEY_SUB_SCALE]?.toDoubleOrNull() ?: 1.0 }.distinctUntilChanged()

    suspend fun trackPref(type: String): String? = context.store.data.first()[if (type == "audio") KEY_AUDIO_PREF else KEY_SUB_PREF]

    suspend fun setTrackPref(type: String, value: String?) = context.store.edit {
        val k = if (type == "audio") KEY_AUDIO_PREF else KEY_SUB_PREF
        if (value == null) it.remove(k) else it[k] = value
    }

    suspend fun subScale(): Double = context.store.data.first()[KEY_SUB_SCALE]?.toDoubleOrNull() ?: 1.0
    suspend fun setSubScale(v: Double) = context.store.edit { it[KEY_SUB_SCALE] = v.toString() }

    /** Seconds per skip: the player's buttons, a double tap at the edge, the headphones and PiP. 5 or 10. */
    val seekStep: Flow<Int> = context.store.data.map { it[KEY_SEEK_STEP] ?: 10 }.distinctUntilChanged()
    suspend fun setSeekStep(v: Int) = context.store.edit { it[KEY_SEEK_STEP] = v }

    /** Holding a finger on the video plays it at 2×. On unless turned off: some people brush the screen. */
    val holdBoost: Flow<Boolean> = context.store.data.map { it[KEY_HOLD_BOOST] != "off" }.distinctUntilChanged()
    suspend fun setHoldBoost(on: Boolean) = context.store.edit { if (on) it.remove(KEY_HOLD_BOOST) else it[KEY_HOLD_BOOST] = "off" }

    /** Vertical swipes on the left/right halves of the player adjust brightness and volume. On by default. */
    val sideGestures: Flow<Boolean> = context.store.data.map { it[KEY_SIDE_GESTURES] != "off" }.distinctUntilChanged()
    suspend fun setSideGestures(on: Boolean) = context.store.edit { if (on) it.remove(KEY_SIDE_GESTURES) else it[KEY_SIDE_GESTURES] = "off" }

    /** Quality for offline downloads: 0 follows the player's quality setting, otherwise 1080/720/480. */
    val downloadQuality: Flow<Int> = context.store.data.map { it[KEY_DOWNLOAD_QUALITY] ?: 0 }.distinctUntilChanged()
    suspend fun setDownloadQuality(q: Int) = context.store.edit { if (q <= 0) it.remove(KEY_DOWNLOAD_QUALITY) else it[KEY_DOWNLOAD_QUALITY] = q }

    /** Automatically deletes a downloaded episode once it has been watched past 92%. Off by default. */
    val autoDeleteWatched: Flow<Boolean> = context.store.data.map { it[KEY_AUTO_DELETE_DL] == "on" }.distinctUntilChanged()
    suspend fun setAutoDeleteWatched(on: Boolean) = context.store.edit { if (on) it[KEY_AUTO_DELETE_DL] = "on" else it.remove(KEY_AUTO_DELETE_DL) }

    /** Downloads only proceed on Wi-Fi / unmetered networks when enabled; cellular pauses them cleanly. */
    val wifiOnlyDownloads: Flow<Boolean> = context.store.data.map { it[KEY_WIFI_ONLY_DL] == "on" }.distinctUntilChanged()
    suspend fun setWifiOnlyDownloads(on: Boolean) = context.store.edit { if (on) it[KEY_WIFI_ONLY_DL] = "on" else it.remove(KEY_WIFI_ONLY_DL) }

    /** Smart OP/ED skip markers from AniSkip, strictly validated against episode runtime. Off by default. */
    val aniSkip: Flow<Boolean> = context.store.data.map { it[KEY_ANISKIP] == "on" }.distinctUntilChanged()
    suspend fun setAniSkip(on: Boolean) = context.store.edit { if (on) it[KEY_ANISKIP] = "on" else it.remove(KEY_ANISKIP) }

    /** Dynamic audio normalization (dynaudnorm) for balancing quiet dialogue and loud openings. Off by default. */
    val normalizeAudio: Flow<Boolean> = context.store.data.map { it[KEY_NORMALIZE_AUDIO] == "on" }.distinctUntilChanged()
    suspend fun setNormalizeAudio(on: Boolean) = context.store.edit { if (on) it[KEY_NORMALIZE_AUDIO] = "on" else it.remove(KEY_NORMALIZE_AUDIO) }

    // --- offline watched outbox ----------------------------------------------------------------------
    val pendingWatched: Flow<List<PendingWatch>> = context.store.data
        .map { it[KEY_PENDING_WATCHED] }
        .distinctUntilChanged()
        .map { raw -> raw?.let { runCatching { AppJson.decodeFromString(pendingWatchSer, it) }.getOrNull() } ?: emptyList() }

    suspend fun enqueuePendingWatched(translationId: Long, animeId: Long) = context.store.edit { p ->
        val cur = p[KEY_PENDING_WATCHED]?.let { runCatching { AppJson.decodeFromString(pendingWatchSer, it) }.getOrNull() } ?: emptyList()
        if (cur.any { it.translationId == translationId }) return@edit
        val next = (cur + PendingWatch(translationId, animeId, System.currentTimeMillis())).takeLast(200)
        p[KEY_PENDING_WATCHED] = AppJson.encodeToString(pendingWatchSer, next)
    }

    suspend fun removePendingWatched(translationId: Long) = context.store.edit { p ->
        val cur = p[KEY_PENDING_WATCHED]?.let { runCatching { AppJson.decodeFromString(pendingWatchSer, it) }.getOrNull() } ?: emptyList()
        val next = cur.filterNot { it.translationId == translationId }
        if (next.isEmpty()) p.remove(KEY_PENDING_WATCHED) else p[KEY_PENDING_WATCHED] = AppJson.encodeToString(pendingWatchSer, next)
    }

    // --- portable settings backup (JSON, never contains cookies or account tokens) -------------------
    suspend fun exportBackupJson(): String {
        val d = context.store.data.first()
        val studios = d[KEY_FAVOURITE_STUDIOS]?.lines().orEmpty().filter { it.isNotBlank() }.map { l ->
            val key = l.substringBefore('\t')
            key to (l.substringAfter('\t', "").ifBlank { key.substringAfter('|').replaceFirstChar { it.uppercase() } })
        }
        val picks = d[KEY_PICKS]?.lines().orEmpty().filter { it.contains('\t') }.associate { l ->
            l.substringBefore('\t') to l.substringAfter('\t')
        }
        val favs = d[KEY_FAVORITES]?.let { runCatching { AppJson.decodeFromString(favSer, it) }.getOrNull() } ?: emptyList()
        val backup = SettingsBackup(
            exportedAt = System.currentTimeMillis(),
            preferredKind = d[KEY_KIND] ?: "VoiceRu",
            favouriteStudios = studios,
            translationPicks = picks,
            quality = minOf(d[KEY_QUALITY] ?: 1080, app.neifur.data.a365.MAX_STREAM_HEIGHT),
            subScale = d[KEY_SUB_SCALE]?.toDoubleOrNull()?.coerceIn(0.6, 2.0) ?: 1.0,
            seekStep = if (d[KEY_SEEK_STEP] == 5) 5 else 10,
            holdBoost = d[KEY_HOLD_BOOST] != "off",
            sideGestures = d[KEY_SIDE_GESTURES] != "off",
            rememberBrightness = d[KEY_REMEMBER_BRIGHTNESS] == "on",
            aniSkip = d[KEY_ANISKIP] == "on",
            normalizeAudio = d[KEY_NORMALIZE_AUDIO] == "on",
            downloadQuality = d[KEY_DOWNLOAD_QUALITY] ?: 0,
            autoDeleteWatched = d[KEY_AUTO_DELETE_DL] == "on",
            wifiOnlyDownloads = d[KEY_WIFI_ONLY_DL] == "on",
            mirrorAuto = d[KEY_MIRROR_AUTO] != "0",
            channelAuto = d[KEY_CHANNEL_AUTO] != "0",
            customMirrors = d[KEY_CUSTOM_MIRRORS]?.split('\n')?.filter(String::isNotBlank) ?: emptyList(),
            alertsEnabled = d[KEY_ALERTS] == "1",
            alertsMyStudio = d[KEY_ALERTS_MODE] == "studio",
            alertsPlanned = d[KEY_ALERTS_PLANNED] == "1",
            favorites = favs,
        )
        return encodeSettingsBackup(backup)
    }

    suspend fun importBackupJson(raw: String): BackupImportSummary {
        val b = decodeSettingsBackup(raw)
        context.store.edit { p ->
            p[KEY_KIND] = b.preferredKind.ifBlank { "VoiceRu" }
            if (b.favouriteStudios.isNotEmpty()) {
                p[KEY_FAVOURITE_STUDIOS] = b.favouriteStudios.distinctBy { it.first }.take(30)
                    .joinToString("\n") { "${it.first}\t${it.second}" }
            }
            if (b.translationPicks.isNotEmpty()) {
                val existing = p[KEY_PICKS]?.lines().orEmpty().filter { it.contains('\t') }
                    .associate { it.substringBefore('\t') to it.substringAfter('\t') }
                val merged = (existing + b.translationPicks).entries.toList().takeLast(400)
                p[KEY_PICKS] = merged.joinToString("\n") { "${it.key}\t${it.value}" }
            }
            p[KEY_QUALITY] = b.quality.coerceIn(360, app.neifur.data.a365.MAX_STREAM_HEIGHT)
            p[KEY_SUB_SCALE] = b.subScale.coerceIn(0.6, 2.0).toString()
            p[KEY_SEEK_STEP] = if (b.seekStep == 5) 5 else 10
            if (b.holdBoost) p.remove(KEY_HOLD_BOOST) else p[KEY_HOLD_BOOST] = "off"
            if (b.sideGestures) p.remove(KEY_SIDE_GESTURES) else p[KEY_SIDE_GESTURES] = "off"
            if (b.rememberBrightness) p[KEY_REMEMBER_BRIGHTNESS] = "on" else { p.remove(KEY_REMEMBER_BRIGHTNESS); p.remove(KEY_BRIGHTNESS) }
            if (b.aniSkip) p[KEY_ANISKIP] = "on" else p.remove(KEY_ANISKIP)
            if (b.normalizeAudio) p[KEY_NORMALIZE_AUDIO] = "on" else p.remove(KEY_NORMALIZE_AUDIO)
            if (b.downloadQuality <= 0) p.remove(KEY_DOWNLOAD_QUALITY)
            else p[KEY_DOWNLOAD_QUALITY] = b.downloadQuality.coerceIn(360, app.neifur.data.a365.MAX_STREAM_HEIGHT)
            if (b.autoDeleteWatched) p[KEY_AUTO_DELETE_DL] = "on" else p.remove(KEY_AUTO_DELETE_DL)
            if (b.wifiOnlyDownloads) p[KEY_WIFI_ONLY_DL] = "on" else p.remove(KEY_WIFI_ONLY_DL)
            p[KEY_MIRROR_AUTO] = if (b.mirrorAuto) "1" else "0"
            p[KEY_CHANNEL_AUTO] = if (b.channelAuto) "1" else "0"
            if (b.customMirrors.isNotEmpty()) {
                val cur = p[KEY_CUSTOM_MIRRORS]?.split('\n')?.filter(String::isNotBlank) ?: emptyList()
                p[KEY_CUSTOM_MIRRORS] = (cur + b.customMirrors).distinct().joinToString("\n")
            }
            p[KEY_ALERTS] = if (b.alertsEnabled) "1" else "0"
            p[KEY_ALERTS_MODE] = if (b.alertsMyStudio) "studio" else "any"
            p[KEY_ALERTS_PLANNED] = if (b.alertsPlanned) "1" else "0"
            if (b.favorites.isNotEmpty()) {
                val cur = p[KEY_FAVORITES]?.let { runCatching { AppJson.decodeFromString(favSer, it) }.getOrNull() } ?: emptyList()
                val merged = (b.favorites + cur).distinctBy { it.id }
                p[KEY_FAVORITES] = AppJson.encodeToString(favSer, merged)
            }
        }
        return BackupImportSummary(b.favouriteStudios.size, b.translationPicks.size, b.favorites.size)
    }

    /** Keeps manual player brightness between sessions instead of reverting to system auto-brightness. */
    val rememberBrightness: Flow<Boolean> = context.store.data.map { it[KEY_REMEMBER_BRIGHTNESS] == "on" }.distinctUntilChanged()
    suspend fun setRememberBrightness(on: Boolean) = context.store.edit {
        if (on) it[KEY_REMEMBER_BRIGHTNESS] = "on" else { it.remove(KEY_REMEMBER_BRIGHTNESS); it.remove(KEY_BRIGHTNESS) }
    }
    suspend fun savedBrightness(): Float? {
        val d = context.store.data.first()
        if (d[KEY_REMEMBER_BRIGHTNESS] != "on") return null
        return d[KEY_BRIGHTNESS]?.toFloatOrNull()?.takeIf { it in 0.01f..1f }
    }
    suspend fun saveBrightness(v: Float) = context.store.edit {
        if (it[KEY_REMEMBER_BRIGHTNESS] == "on") it[KEY_BRIGHTNESS] = v.coerceIn(0.01f, 1f).toString()
    }

    // --- catalog recent searches ---------------------------------------------------------------------
    val recentSearches: Flow<List<String>> = context.store.data
        .map { p -> p[KEY_RECENT_SEARCHES]?.lines()?.filter(String::isNotBlank) ?: emptyList() }
        .distinctUntilChanged()

    suspend fun addRecentSearch(raw: String) = context.store.edit { p ->
        val cur = p[KEY_RECENT_SEARCHES]?.lines()?.filter(String::isNotBlank) ?: emptyList()
        val next = pushRecentSearch(cur, raw)
        if (next != cur) p[KEY_RECENT_SEARCHES] = next.joinToString("\n")
    }

    suspend fun removeRecentSearch(query: String) = context.store.edit { p ->
        val next = p[KEY_RECENT_SEARCHES]?.lines()?.filter(String::isNotBlank)
            ?.filterNot { it.equals(query, ignoreCase = true) }.orEmpty()
        if (next.isEmpty()) p.remove(KEY_RECENT_SEARCHES) else p[KEY_RECENT_SEARCHES] = next.joinToString("\n")
    }

    suspend fun clearRecentSearches() = context.store.edit { it.remove(KEY_RECENT_SEARCHES) }
}

/** Normalises [raw] and puts it at the front of [current], dropping duplicates (case-insensitively) and excess items. */
internal fun pushRecentSearch(current: List<String>, raw: String, limit: Int = 8): List<String> {
    val clean = raw.trim().replace(Regex("\\s+"), " ")
    if (clean.length < 2) return current
    return (listOf(clean) + current.filterNot { it.equals(clean, ignoreCase = true) }).take(limit)
}
