package app.neifur.data.a365

import kotlinx.serialization.Serializable

@Serializable
data class Titles(val ru: String? = null, val romaji: String? = null, val en: String? = null, val ja: String? = null)

@Serializable
data class SeriesGenre(val id: Int = 0, val title: String = "")

@Serializable
data class SeriesDescription(val source: String? = null, val value: String? = null)

/** One title on Anime 365. List endpoints return a trimmed copy (see [Anime365Api.CARD_FIELDS]). */
@Serializable
data class Series(
    val id: Long,
    val myAnimeListId: Long = 0,
    val myAnimeListScore: Double? = null,
    val numberOfEpisodes: Int = 0,
    val season: String? = null,
    val year: Int = 0,
    val type: String? = null,
    val typeTitle: String? = null,
    val isAiring: Int = 0,
    val titles: Titles = Titles(),
    val posterUrl: String? = null,
    val posterUrlSmall: String? = null,
    val genres: List<SeriesGenre>? = null,
    val descriptions: List<SeriesDescription>? = null,
    val episodes: List<Episode>? = null,
) {
    val title: String get() = titles.ru?.takeIf { it.isNotBlank() } ?: titles.romaji ?: titles.en ?: "Без названия"
    val original: String? get() = titles.romaji?.takeIf { it != title }
    val score: Double? get() = myAnimeListScore?.takeIf { it > 0 }
    val airing: Boolean get() = isAiring == 1
    val isMovie: Boolean get() = type == "movie"

    /** The longest Russian description wins: Anime 365 keeps several sources, some of them one-liners. */
    val description: String? get() = descriptions.orEmpty().mapNotNull { it.value?.trim() }.filter { it.isNotEmpty() }.maxByOrNull { it.length }

    /** Episodes a viewer can play, in order. Trailers and inactive entries are left out. */
    val playable: List<Episode>
        get() = episodes.orEmpty()
            .filter { it.isActive == 1 && it.episodeType != "preview" }
            .sortedWith(compareBy({ it.number }, { it.id }))
}

@Serializable
data class Episode(
    val id: Long,
    val episodeFull: String = "",
    val episodeInt: Double? = null,
    val episodeTitle: String? = null,
    val episodeType: String? = null,
    val firstUploadedDateTime: String? = null,
    val isActive: Int = 1,
    val seriesId: Long = 0,
    val translations: List<Translation>? = null,
) {
    val number: Int get() = episodeInt?.toInt() ?: 0
    /** "Серия 5", or the site's own label for specials ("OVA", "Фильм"). */
    val label: String get() = if (episodeInt != null && episodeInt > 0 && episodeFull.contains("сери")) "Серия $number" else episodeFull.ifBlank { "Серия $number" }
}

/**
 * One way to watch an episode: a voice-over, a subtitle track, or the raw original.
 * Anime 365 lists every studio separately, often with TV and BD variants of the same one.
 */
@Serializable
data class Translation(
    val id: Long,
    val authorsSummary: String = "",
    val authorsList: List<String> = emptyList(),
    val type: String = "",
    val typeKind: String = "",
    val typeLang: String = "",
    val qualityType: String = "",
    val height: Int = 0,
    val priority: Long = 0,
    val isActive: Int = 1,
    val episodeId: Long = 0,
    val duration: Double = 0.0,
) {
    val kind: TranslationKind get() = TranslationKind.of(type, typeKind, typeLang)

    /** Translations the site itself lists (it hides its "other languages" dubs). */
    val shown: Boolean get() = isActive == 1 && kind != TranslationKind.Hidden

    /** Studio name without release notes: "Freedub Studio [ТВ] (BD)" → "Freedub Studio". */
    val author: String
        get() = authorsSummary.replace(Regex("""\s*[\[(][^\])]*[\])]"""), "")
            .trim().trimStart('-', '–', '—', '·', ' ').trim()
            .ifBlank { authorsSummary.trim() }.ifBlank { "Без названия" }

    /** Identifies "the same translation" across episodes, so a pick made once follows the viewer. */
    val pickKey: String get() = "${kind.name}|${author.lowercase()}"

    /** What can actually be watched: Anime 365 streams up to [MAX_STREAM_HEIGHT], whatever the source file was. */
    val streamHeight: Int get() = minOf(height, MAX_STREAM_HEIGHT)

    val qualityLabel: String
        get() = buildString {
            if (height > 0) append(streamHeight).append('p')
            when (qualityType) { "bd" -> append(if (isEmpty()) "BD" else " · BD"); "dvd" -> append(if (isEmpty()) "DVD" else " · DVD") }
        }
}

/**
 * The kinds of translation Anime 365 shows, named as the site names them, ordered for a Russian-speaking viewer.
 * [Hidden] holds what the site itself doesn't list (its "voiceOther": French, Hindi, Spanish… dubs).
 * [short] fits a chip, [inline] goes after a studio name ("AniLibria · озвучка").
 */
enum class TranslationKind(val title: String, val short: String, val inline: String, val isSubs: Boolean = false) {
    VoiceRu("Озвучка", "Озвучка", "озвучка"),
    SubRu("Русские субтитры", "Субтитры", "русские субтитры", isSubs = true),
    Raw("RAW", "RAW", "RAW, без перевода"),
    VoiceEn("Английская озвучка", "EN озвучка", "англ. озвучка"),
    SubEn("Английские субтитры", "EN субтитры", "англ. субтитры", isSubs = true),
    VoiceUk("Украинская озвучка", "UA озвучка", "укр. озвучка"),
    SubUk("Украинские субтитры", "UA субтитры", "укр. субтитры", isSubs = true),
    SubJa("Японские субтитры", "JP субтитры", "япон. субтитры", isSubs = true),
    Hidden("", "", "");

    companion object {
        /** The kinds a viewer can pick from, in display order. */
        val shown: List<TranslationKind> = entries.filter { it != Hidden }

        fun of(type: String, kind: String, lang: String): TranslationKind = when (type) {
            "voiceRu" -> VoiceRu
            "subRu" -> SubRu
            "raw" -> Raw
            "voiceEn" -> VoiceEn
            "subEn" -> SubEn
            "voiceUk", "voiceUa" -> VoiceUk
            "subUk", "subUa" -> SubUk
            "subJa" -> SubJa
            "" -> when {
                kind == "raw" -> Raw
                kind == "voice" && lang == "ru" -> VoiceRu
                kind == "sub" && lang == "ru" -> SubRu
                else -> Hidden
            }
            else -> Hidden
        }
    }
}

/**
 * The highest picture Anime 365 streams. Its catalog lists the source file's height (2160 for 4K releases), but
 * the player is only ever given 1080p, 720p and 360p, so labels never promise more than this.
 */
const val MAX_STREAM_HEIGHT = 1080

@Serializable
data class EmbedStream(val height: Int = 0, val urls: List<String> = emptyList())

/** What the player needs for one translation: direct video links per quality, plus subtitles when it's a sub. */
@Serializable
data class Embed(
    val stream: List<EmbedStream> = emptyList(),
    val subtitlesUrl: String? = null,
    val subtitlesVttUrl: String? = null,
)

// --- site pages ----------------------------------------------------------------------------------------

data class Profile(val id: Long, val name: String, val avatarUrl: String?)

enum class ListStatus(val id: Int, val path: String, val title: String) {
    Watching(1, "watching", "Смотрю"),
    Planned(0, "planned", "Запланировано"),
    Completed(2, "completed", "Просмотрено"),
    OnHold(3, "onhold", "Отложено"),
    Dropped(4, "dropped", "Брошено");

    companion object { fun of(id: Int) = entries.firstOrNull { it.id == id } }
}

/** The site's own words for each score. */
fun scoreLabel(score: Int): String = when (score) {
    10 -> "Шедевр"; 9 -> "Великолепно"; 8 -> "Очень хорошо"; 7 -> "Хорошо"; 6 -> "Неплохо"
    5 -> "Средне"; 4 -> "Плохо"; 3 -> "Очень плохо"; 2 -> "Ужасно"; 1 -> "Отвратительно"; else -> "Без оценки"
}

/** One row of the viewer's Anime 365 lists. [total] is null while the site doesn't know it ("3 / ?"). */
data class ListEntry(
    val seriesId: Long, val title: String, val watched: Int, val total: Int?, val score: Int?,
    val status: ListStatus? = null,
)

/** The viewer's own record for one title; [status] null means "not in the list". */
data class ListRecord(val status: ListStatus?, val watched: Int, val score: Int?)

