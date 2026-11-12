package app.neifur.player

import app.neifur.data.Langs

/** What kind of subtitle a track is; matters when a release ships both full subs and signs-only. */
enum class SubKind { Full, Signs, Captions }

/** A track described for people: "Испанский" + "Латинская Америка · Crunchyroll", not "ES-419 · Latin_America_CR". */
data class TrackInfo(
    val lang: String?,          // canonical code ("ru", "ja"...) or null
    val name: String,           // "Испанский", "Без языка"
    val region: String?,        // "Латинская Америка"
    val source: String?,        // "Crunchyroll"
    val note: String?,          // leftover meaningful title text: "Commentary", "Netflix-перевод"...
    val kind: SubKind,
) {
    val detail: String? get() = listOfNotNull(region, source, note).joinToString(" · ").ifBlank { null }
}

object TrackNames {
    private val ISO = mapOf(
        "ja" to "ja", "jpn" to "ja", "jp" to "ja", "en" to "en", "eng" to "en", "ru" to "ru", "rus" to "ru",
        "uk" to "uk", "ukr" to "uk", "zh" to "zh", "chi" to "zh", "zho" to "zh", "chs" to "zh", "cht" to "zh", "cmn" to "zh", "yue" to "zh",
        "ko" to "ko", "kor" to "ko", "es" to "es", "spa" to "es", "pt" to "pt", "por" to "pt", "fr" to "fr", "fre" to "fr", "fra" to "fr",
        "de" to "de", "ger" to "de", "deu" to "de", "it" to "it", "ita" to "it", "ar" to "ar", "ara" to "ar", "pl" to "pl", "pol" to "pl",
        "tr" to "tr", "tur" to "tr", "id" to "id", "ind" to "id", "ms" to "ms", "may" to "ms", "msa" to "ms", "th" to "th", "tha" to "th",
        "vi" to "vi", "vie" to "vi", "hi" to "hi", "hin" to "hi", "fil" to "fil", "tl" to "fil", "tgl" to "fil",
        "nl" to "nl", "dut" to "nl", "nld" to "nl", "sv" to "sv", "swe" to "sv", "cs" to "cs", "cze" to "cs", "ces" to "cs",
        "hu" to "hu", "hun" to "hu", "ro" to "ro", "rum" to "ro", "ron" to "ro", "el" to "el", "gre" to "el", "ell" to "el",
        "he" to "he", "heb" to "he", "fi" to "fi", "fin" to "fi", "da" to "da", "dan" to "da", "no" to "no", "nor" to "no", "nob" to "no",
        "ca" to "ca", "cat" to "ca", "ta" to "ta", "tam" to "ta", "te" to "te", "tel" to "te", "bn" to "bn", "ben" to "bn",
    )

    private val REGION_TAG = mapOf(
        "br" to "Бразилия", "pt" to "Португалия", "419" to "Латинская Америка", "es" to "Испания", "mx" to "Мексика",
        "la" to "Латинская Америка", "fr" to "Франция", "ca" to "Канада", "us" to "США", "gb" to "Великобритания",
        "hans" to "упрощённый", "hant" to "традиционный", "cn" to "материковый", "tw" to "Тайвань", "hk" to "Гонконг",
        "sa" to "Саудовская Аравия", "de" to "Германия", "it" to "Италия",
    )

    private val REGION_WORDS = listOf(
        Regex("""(?i)latin[\s_-]*america(?:n)?|latino|latam""") to "Латинская Америка",
        Regex("""(?i)\bbrazil(?:ian)?\b|\(brazil\)""") to "Бразилия",
        Regex("""(?i)\b(?:spain|castilian|castellano|european spanish)\b""") to "Испания",
        Regex("""(?i)\bportugal\b""") to "Португалия",
        Regex("""(?i)\bsaudi[\s_-]*arabia\b""") to "Саудовская Аравия",
        Regex("""(?i)\bsimplified\b|简体|簡體|\bchs\b|\bsc\b""") to "упрощённый",
        Regex("""(?i)\btraditional\b|繁體|繁体|\bcht\b|\btc\b""") to "традиционный",
        Regex("""(?i)\bcanad(?:a|ian)\b""") to "Канада",
    )

    private val SOURCES = listOf(
        Regex("""(?i)(?<![a-z])(?:cr|crunchyroll)(?![a-z])""") to "Crunchyroll",
        Regex("""(?i)(?<![a-z])(?:amzn|amazon)(?![a-z])""") to "Amazon",
        Regex("""(?i)(?<![a-z])(?:nf|netflix)(?![a-z])""") to "Netflix",
        Regex("""(?i)(?<![a-z])(?:dsnp|disney\+?)(?![a-z])""") to "Disney+",
        Regex("""(?i)(?<![a-z])hidive(?![a-z])""") to "HIDIVE",
        Regex("""(?i)(?<![a-z])(?:b-?global|bilibili|bili)(?![a-z])""") to "Bilibili",
        Regex("""(?i)(?<![a-z])adn(?![a-z])""") to "ADN",
        Regex("""(?i)(?<![a-z])abema(?![a-z])""") to "ABEMA",
    )

    private val SIGNS = Regex("""(?i)\b(?:signs?|songs?|forced|надписи|караоке|karaoke)\b""")
    private val CC = Regex("""(?i)(?<![a-z])(?:cc|sdh|hearing[\s_-]*impaired)(?![a-z])""")
    private val FULL = Regex("""(?i)\b(?:full(?:[\s_-]*subtitles?)?|dialogue|dialog|полные)\b""")
    private val NOISE = Regex(
        """(?i)\[[^]]*]|\b(?:aac|flac|opus|e?-?ac-?3|ddp?|dts(?:-?hd)?|truehd|pcm|mp3|ass|ssa|srt|pgs|sup|vobsub|subrip|subtitles?|subs?|audio|track|stereo|mono|\d\.\d|\d{2,3}\s?kbps|default|main)\b"""
    )

    fun describe(t: Track): TrackInfo {
        val tag = t.lang?.trim()?.lowercase()?.replace('_', '-')
        val baseTag = tag?.substringBefore('-')
        var code = baseTag?.let { ISO[it] }
        var region = tag?.substringAfter('-', "")?.takeIf { it.isNotEmpty() }?.let { r ->
            r.split('-').firstNotNullOfOrNull { REGION_TAG[it] }
        }

        val title = t.title.orEmpty().replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
        val titleLangs = Langs.find(title)
        if (code == null) code = titleLangs.firstOrNull()
        if (region == null) region = REGION_WORDS.firstOrNull { it.first.containsMatchIn(title) }?.second
        // "Spanish" and "Spanish (Spain)" side by side: only the Latin American one gets a marker otherwise.
        val source = SOURCES.firstOrNull { it.first.containsMatchIn(title) }?.second
        val kind = when {
            t.forced || (SIGNS.containsMatchIn(title) && !FULL.containsMatchIn(title)) -> SubKind.Signs
            CC.containsMatchIn(title) -> SubKind.Captions
            else -> SubKind.Full
        }

        var rest = title
        rest = NOISE.replace(rest, " ")
        (REGION_WORDS.map { it.first } + SOURCES.map { it.first } + listOf(SIGNS, CC, FULL)).forEach { rest = it.replace(rest, " ") }
        rest = rest.replace(Regex("""(?i)\b(?:${LANG_WORDS})\b"""), " ")
        rest = rest.replace(Regex("""[()\-–—|/:,.+]+"""), " ").replace(Regex("""\s+"""), " ").trim()
        val note = rest.takeIf { it.length in 3..28 && it.any(Char::isLetter) && !it.equals(source, true) }

        val name = code?.let(Langs::name) ?: when {
            tag == null || tag == "und" || tag == "mul" || tag == "zxx" -> if (t.type == "audio") "Дорожка ${t.id}" else "Субтитры ${t.id}"
            else -> tag.uppercase()
        }
        return TrackInfo(code, name, region, source, note, kind)
    }

    private val LANG_WORDS = listOf(
        "japanese", "english", "russian", "spanish", "portuguese", "french", "german", "italian", "arabic", "chinese", "korean",
        "ukrainian", "polish", "turkish", "indonesian", "malay", "thai", "vietnamese", "hindi", "español", "português", "français",
        "deutsch", "italiano", "русский", "日本語", "jpn", "eng", "rus", "spa", "por", "fre", "ger", "ita", "ara", "chi", "kor",
        "brazilian", "brasil",
    ).joinToString("|") { Regex.escape(it) }

    /** Order for the list: the viewer's language first, signs right under the full track of the same language. */
    fun sortKey(t: Track): String {
        val info = t.info
        val order = if (t.type == "audio") listOf("ja", "ru", "en", "uk") else listOf("ru", "en", "ja", "uk")
        val rank = info.lang?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: if (info.lang == null) 90 else 50
        return "%02d|%s|%s|%d|%05d".format(rank, info.name, info.region.orEmpty(), info.kind.ordinal, t.id)
    }
}
