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
    private val KEY_HISTORY = stringPreferencesKey("history")
    private val KEY_FAVORITES = stringPreferencesKey("favorites")
    private val KEY_QUALITY = intPreferencesKey("quality")
    private val KEY_PICKS = stringPreferencesKey("translation_picks")
    private val KEY_FAVOURITE_AUTHORS = stringPreferencesKey("favourite_authors")
    private val KEY_KIND = stringPreferencesKey("preferred_kind")
    private val KEY_AUDIO_PREF = stringPreferencesKey("audio_pref")
    private val KEY_SUB_PREF = stringPreferencesKey("sub_pref")
    private val KEY_SUB_SCALE = stringPreferencesKey("sub_scale")
    private val KEY_ONBOARDED = stringPreferencesKey("onboarded")

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

    // --- onboarding ----------------------------------------------------------------------------------
    /** Whether the welcome flow is done: "1" done, "0" in progress (or shown again from Settings). */
    val onboarded: Flow<Boolean> = context.store.data.map { it[KEY_ONBOARDED] == "1" }.distinctUntilChanged()
    suspend fun beginOnboarding() = context.store.edit { if (it[KEY_ONBOARDED] != "0") it[KEY_ONBOARDED] = "0" }
    suspend fun setOnboarded() = context.store.edit { it[KEY_ONBOARDED] = "1" }

    // --- playback ------------------------------------------------------------------------------------
    /** Preferred picture height: the player takes the best stream at or below it. */
    val quality: Flow<Int> = context.store.data.map { it[KEY_QUALITY] ?: 1080 }.distinctUntilChanged()
    suspend fun setQuality(q: Int) = context.store.edit { it[KEY_QUALITY] = q }

    /** What a new title starts with: Russian voice-over by default, or subtitles for those who prefer them. */
    val preferredKind: Flow<String> = context.store.data.map { it[KEY_KIND] ?: "VoiceRu" }.distinctUntilChanged()
    suspend fun setPreferredKind(kind: String) = context.store.edit { it[KEY_KIND] = kind }

    /** The translation picked for a title (its pick key), so the next visit and the next episode keep it. */
    suspend fun pick(animeId: String): String? =
        context.store.data.first()[KEY_PICKS]?.lineSequence()?.firstOrNull { it.startsWith("$animeId\t") }?.substringAfter('\t')

    /** [author] is the studio's display name, kept for the "favourite studios" list in Settings. */
    suspend fun setPick(animeId: String, key: String, author: String) = context.store.edit { p ->
        val lines = p[KEY_PICKS]?.lines()?.filterNot { it.startsWith("$animeId\t") } ?: emptyList()
        p[KEY_PICKS] = (listOf("$animeId\t$key") + lines).take(400).joinToString("\n")
        // Studios picked by hand rank first on titles the viewer hasn't opened yet.
        val fav = p[KEY_FAVOURITE_AUTHORS]?.lines().orEmpty().filterNot { it.substringBefore('\t') == key }
        p[KEY_FAVOURITE_AUTHORS] = (listOf("$key\t$author") + fav).take(12).joinToString("\n")
    }

    /** Pick keys of the studios chosen by hand, most recent first. */
    suspend fun favouriteAuthors(): List<String> = favouriteStudios.first().map { it.first }

    /** (pick key, display name) of the studios chosen by hand, most recent first. */
    val favouriteStudios: Flow<List<Pair<String, String>>> = context.store.data.map { p ->
        p[KEY_FAVOURITE_AUTHORS]?.lines().orEmpty().filter { it.isNotBlank() }.map { l ->
            val key = l.substringBefore('\t')
            key to (l.substringAfter('\t', "").ifBlank { key.substringAfter('|').replaceFirstChar { it.uppercase() } })
        }
    }.distinctUntilChanged()

    suspend fun removeFavouriteStudio(key: String) = context.store.edit { p ->
        p[KEY_FAVOURITE_AUTHORS] = p[KEY_FAVOURITE_AUTHORS]?.lines().orEmpty().filterNot { it.substringBefore('\t') == key }.joinToString("\n")
    }

    /** How many titles have a translation chosen by hand. */
    val pickCount: Flow<Int> = context.store.data.map { p -> p[KEY_PICKS]?.lines()?.count { it.isNotBlank() } ?: 0 }.distinctUntilChanged()

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

    suspend fun clearHistory() = context.store.edit { it.remove(KEY_HISTORY) }

    suspend fun removeFromHistory(animeId: String) = context.store.edit { p ->
        val cur = p[KEY_HISTORY]?.let { runCatching { AppJson.decodeFromString(historySer, it) }.getOrNull() } ?: emptyList()
        p[KEY_HISTORY] = AppJson.encodeToString(historySer, cur.filterNot { it.animeId == animeId })
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
