package app.neifur.data.aniskip

import app.neifur.core.AppJson
import app.neifur.player.ChapterKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** One skippable intro/outro/recap interval verified against the video's actual runtime. */
data class SkipSegment(
    val start: Double,
    val end: Double,
    val kind: ChapterKind,
    val label: String,
)

@Serializable
private data class AniSkipInterval(val startTime: Double = 0.0, val endTime: Double = 0.0)

@Serializable
private data class AniSkipResult(
    val interval: AniSkipInterval = AniSkipInterval(),
    val skipType: String = "",
    val episodeLength: Double = 0.0,
)

@Serializable
private data class AniSkipResponse(
    val found: Boolean = false,
    val results: List<AniSkipResult> = emptyList(),
)

/**
 * Maximum runtime difference (seconds) between the playing stream and AniSkip's reference [episodeLength].
 * Normal container/encoding variance is ~0.1..1.5 s; dub/sub studio intro bumpers typically add 4..15+ s,
 * so rejecting anything beyond 2.5 s prevents shifted skip timestamps on modified cuts.
 */
internal const val ANISKIP_LENGTH_TOLERANCE_SEC = 2.5

/**
 * Parses an AniSkip v2 JSON payload and keeps only segments whose reference duration matches [videoDuration]
 * within [ANISKIP_LENGTH_TOLERANCE_SEC] and whose bounds are sane.
 */
internal fun parseAndFilterSkipTimes(body: String, videoDuration: Double): List<SkipSegment> {
    if (videoDuration <= 60.0) return emptyList()
    val parsed = runCatching { AppJson.decodeFromString(AniSkipResponse.serializer(), body) }.getOrNull()
        ?: return emptyList()
    if (!parsed.found || parsed.results.isEmpty()) return emptyList()

    // Prefer pure op/ed over mixed-op/mixed-ed when both exist for the same kind.
    val priority = mapOf("op" to 0, "mixed-op" to 1, "ed" to 0, "mixed-ed" to 1, "recap" to 0)
    val bestByKind = LinkedHashMap<ChapterKind, Pair<Int, SkipSegment>>()

    for (r in parsed.results) {
        if (r.episodeLength <= 60.0) continue
        if (abs(videoDuration - r.episodeLength) > ANISKIP_LENGTH_TOLERANCE_SEC) continue

        val start = r.interval.startTime
        val end = r.interval.endTime
        val span = end - start
        if (start < 0.0 || span < 5.0 || span > 240.0 || end > videoDuration + 1.5) continue

        val (kind, label) = when (r.skipType) {
            "op", "mixed-op" -> ChapterKind.Opening to "Опенинг"
            "ed", "mixed-ed" -> ChapterKind.Ending to "Эндинг"
            "recap" -> ChapterKind.Recap to "Рекап"
            else -> continue
        }
        val prio = priority[r.skipType] ?: 2
        val seg = SkipSegment(start = start, end = end.coerceAtMost(videoDuration), kind = kind, label = label)
        val cur = bestByKind[kind]
        if (cur == null || prio < cur.first) bestByKind[kind] = prio to seg
    }

    return bestByKind.values.map { it.second }.sortedBy { it.start }
}

/** Converts discrete [SkipSegment] intervals into a contiguous chapter timeline for [MpvPlayer] & SeekBar. */
internal fun skipSegmentsToChapters(segments: List<SkipSegment>, duration: Double): List<app.neifur.player.Chapter> {
    if (segments.isEmpty() || duration <= 0.0) return emptyList()
    val sorted = segments.sortedBy { it.start }
    val out = ArrayList<app.neifur.player.Chapter>(sorted.size * 2 + 1)
    var cursor = 0.0
    for (seg in sorted) {
        val s = seg.start.coerceIn(cursor, duration)
        val e = seg.end.coerceIn(s, duration)
        if (e - s < 2.0) continue
        if (s > cursor + 1.0 && out.isEmpty()) out += app.neifur.player.Chapter(0.0, "", ChapterKind.Other)
        out += app.neifur.player.Chapter(s, seg.label, seg.kind)
        if (e < duration - 1.5) out += app.neifur.player.Chapter(e, "", ChapterKind.Other)
        cursor = e
    }
    return out
}

/**
 * Queries crowdsourced OP/ED timestamps from AniSkip v2.
 * Always passes the actual stream duration and double-checks [episodeLength] locally so studio intros
 * never cause misaligned jumps. Silently returns empty on network errors or 404.
 */
class AniSkipApi(baseHttp: OkHttpClient) {
    private val http = baseHttp.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, List<SkipSegment>>()

    suspend fun skipTimes(malId: Long, episodeNumber: Int, videoDurationSec: Double): List<SkipSegment> {
        if (malId <= 0L || episodeNumber <= 0 || videoDurationSec <= 60.0) return emptyList()
        val key = "$malId:$episodeNumber:${videoDurationSec.toInt()}"
        cache[key]?.let { return it }

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val len = String.format(Locale.US, "%.3f", videoDurationSec)
                val url = "https://api.aniskip.com/v2/skip-times/$malId/$episodeNumber" +
                    "?types=op&types=ed&types=mixed-op&types=mixed-ed&types=recap&episodeLength=$len"
                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching emptyList()
                    val body = resp.body?.string() ?: return@runCatching emptyList()
                    parseAndFilterSkipTimes(body, videoDurationSec)
                }
            }.getOrDefault(emptyList())
        }

        if (cache.size >= 64) cache.clear()
        cache[key] = result
        return result
    }
}
