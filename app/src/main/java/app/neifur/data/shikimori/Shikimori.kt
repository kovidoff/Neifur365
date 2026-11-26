package app.neifur.data.shikimori

import app.neifur.core.ApiError
import app.neifur.core.AppJson
import app.neifur.core.awaitText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class Poster(val originalUrl: String? = null, val mainUrl: String? = null, val main2xUrl: String? = null) {
    val card: String? get() = main2xUrl ?: mainUrl ?: originalUrl
    val full: String? get() = originalUrl ?: main2xUrl ?: mainUrl
}

@Serializable
data class ScoreStat(val score: Int = 0, val count: Int = 0)

@Serializable
data class StatusStat(val status: String = "", val count: Int = 0)

@Serializable
data class Video(val kind: String? = null, val name: String? = null, val url: String? = null, val imageUrl: String? = null)

@Serializable
data class CharacterRef(val id: String = "", val name: String = "", val russian: String? = null, val poster: Poster? = null) {
    val label: String get() = russian?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class CharacterRole(val rolesRu: List<String> = emptyList(), val rolesEn: List<String> = emptyList(), val character: CharacterRef? = null) {
    val isMain: Boolean get() = rolesEn.any { it.equals("Main", true) } || rolesRu.any { it.equals("Main", true) || it.startsWith("Главн") }
}

@Serializable
data class PersonRef(val id: String = "", val name: String = "", val russian: String? = null) {
    val label: String get() = russian?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class PersonRole(val rolesRu: List<String> = emptyList(), val rolesEn: List<String> = emptyList(), val person: PersonRef? = null)

/** The heavy part of a title page, fetched separately so the page itself opens at once. */
data class AnimeCast(val characters: List<CharacterRole>, val staff: List<PersonRole>)

@Serializable
data class IncDate(val year: Int? = null, val month: Int? = null, val day: Int? = null) {
    fun format(): String? {
        val y = year ?: return null
        val months = listOf("янв.", "февр.", "марта", "апр.", "мая", "июня", "июля", "авг.", "сент.", "окт.", "нояб.", "дек.")
        val m = month?.let { months.getOrNull(it - 1) }
        return when {
            day != null && m != null -> "$day $m $y"
            m != null -> "${listOf("янв.", "февр.", "март", "апр.", "май", "июнь", "июль", "авг.", "сент.", "окт.", "нояб.", "дек.")[month!! - 1]} $y"
            else -> "$y"
        }
    }
}

@Serializable
data class Genre(val id: String = "", val russian: String? = null, val name: String? = null, val kind: String? = null) {
    val label: String get() = russian ?: name ?: ""
}

@Serializable
data class Studio(val id: String = "", val name: String = "")

@Serializable
data class Screenshot(val originalUrl: String? = null, val x332Url: String? = null)

@Serializable
data class RelatedRef(val id: String, val name: String = "", val russian: String? = null, val kind: String? = null,
                      val score: Double? = null, val status: String? = null, val episodes: Int = 0,
                      val episodesAired: Int = 0, val airedOn: IncDate? = null, val poster: Poster? = null)

@Serializable
data class Related(val relationKind: String? = null, val relationText: String? = null, val anime: RelatedRef? = null)

@Serializable
data class Anime(
    val id: String,
    val name: String = "",
    val russian: String? = null,
    val english: String? = null,
    val japanese: String? = null,
    val synonyms: List<String> = emptyList(),
    val kind: String? = null,
    val rating: String? = null,
    val score: Double? = null,
    val status: String? = null,
    val episodes: Int = 0,
    val episodesAired: Int = 0,
    val duration: Int? = null,
    val airedOn: IncDate? = null,
    val nextEpisodeAt: String? = null,
    val poster: Poster? = null,
    val genres: List<Genre> = emptyList(),
    val studios: List<Studio> = emptyList(),
    val franchise: String? = null,
    val description: String? = null,
    val related: List<Related> = emptyList(),
    val screenshots: List<Screenshot> = emptyList(),
    val origin: String? = null,
    val releasedOn: IncDate? = null,
    val scoresStats: List<ScoreStat> = emptyList(),
    val statusesStats: List<StatusStat> = emptyList(),
    val videos: List<Video> = emptyList(),
) {
    val title: String get() = russian?.takeIf { it.isNotBlank() } ?: name
    val year: Int? get() = airedOn?.year

    /** Episodes a viewer can expect to exist right now. */
    val availableEpisodes: Int
        get() = when (status) {
            "released" -> if (episodes > 0) episodes else episodesAired
            else -> episodesAired
        }
}

fun kindLabel(kind: String?): String = when (kind) {
    "tv" -> "TV сериал"; "movie" -> "Фильм"; "ova" -> "OVA"; "ona" -> "ONA"
    "special" -> "Спешл"; "tv_special" -> "TV спешл"; "music" -> "Клип"; "pv" -> "Промо"; "cm" -> "Реклама"
    null -> ""; else -> kind.uppercase()
}

fun statusLabel(status: String?): String = when (status) {
    "ongoing" -> "Онгоинг"; "released" -> "Вышло"; "anons" -> "Анонс"; else -> ""
}

/** Shikimori descriptions use a BBCode dialect: `[character=1]Name[/character]`, `[[link]]`, `[i]`. */
fun cleanDescription(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw
        .replace(Regex("""\[\[(.*?)]]"""), "$1")
        .replace(Regex("""\[spoiler[^]]*].*?\[/spoiler]""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""\[/?[a-zA-Z_*]+(?:=[^]]*)?]"""), "")
        .replace(Regex("""\r\n?"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

private val FRANCHISE_SKIP_KINDS = setOf("music", "cm", "pv")

/** Filters out music videos/ads and puts a franchise in chronological release order (unannounced/undated last). */
internal fun sortFranchiseEntries(raw: List<RelatedRef>): List<RelatedRef> = raw
    .filterNot { it.kind in FRANCHISE_SKIP_KINDS }
    .distinctBy { it.id }
    .sortedWith(
        compareBy<RelatedRef>(
            { it.airedOn?.year ?: Int.MAX_VALUE },
            { it.airedOn?.month ?: Int.MAX_VALUE },
            { it.airedOn?.day ?: Int.MAX_VALUE },
            { it.id.toLongOrNull() ?: Long.MAX_VALUE },
        )
    )

class ShikimoriApi(
    private val http: OkHttpClient,
    private val offlineCache: app.neifur.data.OfflineCache? = null,
) {
    private val endpoint = "https://shikimori.io/api/graphql"
    private val media = "application/json".toMediaType()
    private val detailCache = ConcurrentHashMap<String, Pair<Long, Anime>>()
    private val castCache = ConcurrentHashMap<String, Pair<Long, AnimeCast>>()
    private val franchiseCache = ConcurrentHashMap<String, Pair<Long, List<RelatedRef>>>()
    private val studioCache = ConcurrentHashMap<String, Pair<Long, List<Anime>>>()
    @Volatile private var scheduleCache: Triple<Long, Set<Long>, List<Anime>>? = null

    // Shikimori allows 5 rps / 90 rpm. A small gate keeps bursts (home + detail + franchise) polite.
    private val gate = Mutex()
    private var lastCall = 0L

    private suspend fun query(q: String, vars: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("query", q)
            if (vars != null) put("variables", vars)
        }.toString()
        var attempt = 0
        while (true) {
            gate.withLock {
                val wait = 230 - (System.currentTimeMillis() - lastCall)
                if (wait > 0) delay(wait)
                lastCall = System.currentTimeMillis()
            }
            try {
                val text = http.newCall(
                    Request.Builder().url(endpoint).post(payload.toRequestBody(media)).build()
                ).awaitText()
                val root = runCatching { AppJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
                    ?: throw ApiError("Shikimori ответил некорректно")
                root["errors"]?.let { errs ->
                    val msg = runCatching { (errs as? JsonArray)?.firstOrNull()?.jsonObject?.get("message")?.toString()?.trim('"') }.getOrNull()
                    throw ApiError("Shikimori: ${msg ?: "ошибка запроса"}")
                }
                return@withContext (root["data"] as? JsonObject) ?: throw ApiError("Shikimori вернул пустой ответ")
            } catch (e: app.neifur.core.HttpError) {
                if (e.code == 429 && attempt++ < 3) delay(900L * attempt) else throw e
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private inline fun <reified T> JsonElement?.decodeList(): List<T> {
        val el = this ?: return emptyList()
        if (el is JsonNull) return emptyList()
        return runCatching { AppJson.decodeFromJsonElement<List<T>>(el) }.getOrDefault(emptyList())
    }

    private fun <V> trimCache(cache: ConcurrentHashMap<String, Pair<Long, V>>) {
        if (cache.size <= 64) return
        val oldest = cache.entries.minByOrNull { it.value.first }?.key ?: return
        cache.remove(oldest)
    }

    suspend fun detail(id: String): Anime {
        val now = System.currentTimeMillis()
        detailCache[id]?.takeIf { now - it.first < 5 * 60_000L }?.let { return it.second }
        val d = query(
            """
            { animes(ids: "$id", limit: 1, censored: false) {
                id name russian english japanese synonyms kind rating score status episodes episodesAired duration
                airedOn { year month day } releasedOn { year month day } nextEpisodeAt franchise description origin
                scoresStats { score count } statusesStats { status count }
                videos { kind name url imageUrl }
                poster { originalUrl mainUrl main2xUrl }
                genres { id russian kind } studios { id name }
                screenshots { originalUrl x332Url }
                related { relationKind relationText anime { id name russian kind score status episodes episodesAired
                  airedOn { year } poster { originalUrl mainUrl main2xUrl } } }
            } }
            """.trimIndent()
        )
        return d["animes"].decodeList<Anime>().firstOrNull()?.also {
            trimCache(detailCache)
            detailCache[id] = System.currentTimeMillis() to it
        } ?: throw ApiError("Тайтл не найден")
    }

    suspend fun cast(id: String): AnimeCast {
        val now = System.currentTimeMillis()
        castCache[id]?.takeIf { now - it.first < 5 * 60_000L }?.let { return it.second }
        val d = query(
            """
            { animes(ids: "$id", limit: 1, censored: false) {
                characterRoles { rolesRu rolesEn character { id name russian poster { mainUrl main2xUrl } } }
                personRoles { rolesRu rolesEn person { id name russian } }
            } }
            """.trimIndent()
        )
        val a = (d["animes"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return AnimeCast(emptyList(), emptyList())
        val chars = a["characterRoles"].decodeList<CharacterRole>().filter { it.character != null }
            .sortedByDescending { it.isMain }.take(24)
        val staff = a["personRoles"].decodeList<PersonRole>().filter { it.person != null }
        return AnimeCast(chars, staff).also {
            trimCache(castCache)
            castCache[id] = System.currentTimeMillis() to it
        }
    }

    /** Chronological list of anime entries belonging to a Shikimori franchise slug (excluding music/CM/PV). */
    suspend fun franchise(name: String): List<RelatedRef> {
        val clean = name.trim().replace("\"", "")
        if (clean.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        franchiseCache[clean]?.takeIf { now - it.first < 10 * 60_000L }?.let { return it.second }
        val d = query(
            """
            { animes(franchise: "$clean", limit: 30, censored: false) {
                id name russian kind score status episodes episodesAired
                airedOn { year month day } poster { originalUrl mainUrl main2xUrl }
            } }
            """.trimIndent()
        )
        return sortFranchiseEntries(d["animes"].decodeList<RelatedRef>()).also {
            trimCache(franchiseCache)
            franchiseCache[clean] = System.currentTimeMillis() to it
        }
    }

    /** Popular anime by a Shikimori studio ID (excluding music/CM/PV). */
    suspend fun byStudio(studioId: String, limit: Int = 40): List<Anime> {
        val clean = studioId.trim().replace("\"", "")
        if (clean.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        studioCache[clean]?.takeIf { now - it.first < 10 * 60_000L }?.let { return it.second }
        val d = query(
            """
            { animes(studio: "$clean", order: popularity, limit: $limit, censored: false) {
                id name russian kind score status episodes episodesAired
                airedOn { year } poster { originalUrl mainUrl main2xUrl }
            } }
            """.trimIndent()
        )
        return d["animes"].decodeList<Anime>()
            .filterNot { it.kind in FRANCHISE_SKIP_KINDS }
            .distinctBy { it.id }
            .also {
                trimCache(studioCache)
                studioCache[clean] = System.currentTimeMillis() to it
            }
    }

    suspend fun cachedSchedule(): List<Anime>? =
        scheduleCache?.third ?: offlineCache?.readSchedule()?.animes

    /** Currently airing anime with their next episode timestamps; merges any extra MAL IDs from the user's lists. */
    suspend fun ongoingSchedule(extraMalIds: List<Long> = emptyList(), forceRefresh: Boolean = false): List<Anime> {
        val wanted = extraMalIds.filter { it > 0L }.toSet()
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            scheduleCache?.takeIf { now - it.first < 15 * 60_000L && wanted.all { id -> id in it.second } }
                ?.let { return it.third }
        }
        val fields = """
            id name russian kind score status episodes episodesAired nextEpisodeAt
            airedOn { year } poster { originalUrl mainUrl main2xUrl }
        """.trimIndent()
        return runCatching {
            val popular = query("""{ animes(status: "ongoing", order: popularity, limit: 50, censored: false) { $fields } }""")["animes"]
                .decodeList<Anime>()
            val have = popular.mapTo(HashSet()) { it.id }
            val missing = extraMalIds.filter { it > 0L && it.toString() !in have }.distinct().take(50)
            val extra = if (missing.isEmpty()) emptyList() else runCatching {
                val ids = missing.joinToString(",")
                query("""{ animes(ids: "$ids", limit: ${missing.size}, censored: false) { $fields } }""")["animes"]
                    .decodeList<Anime>()
            }.getOrDefault(emptyList())
            (popular + extra).distinctBy { it.id }.also { merged ->
                val ts = System.currentTimeMillis()
                scheduleCache = Triple(ts, wanted, merged)
                offlineCache?.writeSchedule(merged, ts)
            }
        }.getOrElse { err ->
            if (err is kotlinx.coroutines.CancellationException) throw err
            // Fall back to the last offline snapshot so Schedule & Home keep working without a network.
            offlineCache?.readSchedule()?.animes?.takeIf { it.isNotEmpty() } ?: throw err
        }
    }
}
