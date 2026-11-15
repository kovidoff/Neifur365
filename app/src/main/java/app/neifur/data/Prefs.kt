package app.neifur.data

import android.content.Context
import androidx.datastore.preferences.core.edit
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

private val Context.store by preferencesDataStore("neifur")

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
    private val KEY_ONBOARDED = stringPreferencesKey("onboarded")
    private val KEY_ALERTS = stringPreferencesKey("alerts")
    private val KEY_ALERTS_MODE = stringPreferencesKey("alerts_mode")
    private val KEY_ALERTS_PLANNED = stringPreferencesKey("alerts_planned")
    private val KEY_ALERTS_SEEN = stringPreferencesKey("alerts_seen")
    private val KEY_ALERTS_ASKED = stringPreferencesKey("alerts_asked")
    private val KEY_ALERTS_CHECKED = stringPreferencesKey("alerts_checked")

    private val historySer = ListSerializer(WatchEntry.serializer())
    private val favSer = ListSerializer(SavedAnime.serializer())

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
    val favouriteStudios: Flow<List<Pair<String, String>>> = context.store.data.map { p ->
        p[KEY_FAVOURITE_STUDIOS]?.lines().orEmpty().filter { it.isNotBlank() }.map { l ->
            val key = l.substringBefore('\t')
            key to (l.substringAfter('\t', "").ifBlank { key.substringAfter('|').replaceFirstChar { it.uppercase() } })
        }
    }.distinctUntilChanged()

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
    val history: Flow<List<WatchEntry>> = context.store.data.map { p ->
        p[KEY_HISTORY]?.let { runCatching { AppJson.decodeFromString(historySer, it) }.getOrNull() } ?: emptyList()
    }.distinctUntilChanged()

    val favorites: Flow<List<SavedAnime>> = context.store.data.map { p ->
        p[KEY_FAVORITES]?.let { runCatching { AppJson.decodeFromString(favSer, it) }.getOrNull() } ?: emptyList()
    }.distinctUntilChanged()

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
}
