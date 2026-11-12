package app.neifur.data

/** Language names for subtitle and audio tracks inside a file, in Russian and in the spellings uploaders use. */
object Langs {
    fun name(code: String): String = when (code) {
        "ja" -> "Японский"; "en" -> "Английский"; "ru" -> "Русский"; "uk" -> "Украинский"; "zh" -> "Китайский"
        "ko" -> "Корейский"; "es" -> "Испанский"; "pt" -> "Португальский"; "fr" -> "Французский"; "de" -> "Немецкий"
        "it" -> "Итальянский"; "ar" -> "Арабский"; "pl" -> "Польский"; "tr" -> "Турецкий"; "id" -> "Индонезийский"
        "ms" -> "Малайский"; "th" -> "Тайский"; "vi" -> "Вьетнамский"; "hi" -> "Хинди"; "fil" -> "Филиппинский"
        "nl" -> "Нидерландский"; "sv" -> "Шведский"; "cs" -> "Чешский"; "hu" -> "Венгерский"; "ro" -> "Румынский"
        "el" -> "Греческий"; "he" -> "Иврит"; "fi" -> "Финский"; "da" -> "Датский"; "no" -> "Норвежский"
        "ca" -> "Каталанский"; "ta" -> "Тамильский"; "te" -> "Телугу"; "bn" -> "Бенгальский"
        else -> code.uppercase()
    }


    // Whole-word names. Native spellings matter: uploaders write "日本語", "Русский", "Español"...
    private val NAMES: List<Pair<String, List<String>>> = listOf(
        "ja" to listOf("japanese", "jpn", "jap", "japonais", "japonés", "japones", "giapponese", "japanisch", "японский", "японская", "日本語", "日语", "日語", "日文", "日配"),
        "en" to listOf("english", "eng", "anglais", "inglés", "ingles", "inglese", "englisch", "английский", "английская", "英语", "英語", "英文", "英配"),
        "ru" to listOf("russian", "rus", "russe", "ruso", "russo", "russisch", "русский", "русская", "русские", "русский язык", "俄语", "俄語"),
        "uk" to listOf("ukrainian", "ukr", "українська", "украинский", "украинская"),
        "zh" to listOf("chinese", "chi", "zho", "chs", "cht", "mandarin", "cantonese", "中文", "汉语", "漢語", "简体", "繁體", "繁体", "简中", "繁中", "國語", "国语", "粤语", "粵語", "китайский"),
        "ko" to listOf("korean", "kor", "한국어", "韩语", "韓語", "корейский"),
        "es" to listOf("spanish", "spa", "español", "espanol", "castellano", "castilian", "испанский", "西班牙语"),
        "pt" to listOf("portuguese", "por", "português", "portugues", "brazilian", "pt-br", "ptbr", "португальский"),
        "fr" to listOf("french", "fre", "fra", "français", "francais", "vostfr", "французский"),
        "de" to listOf("german", "ger", "deu", "deutsch", "немецкий"),
        "it" to listOf("italian", "ita", "italiano", "итальянский"),
        "ar" to listOf("arabic", "ara", "العربية", "арабский"),
        "pl" to listOf("polish", "pol", "polski"),
        "tr" to listOf("turkish", "tur", "türkçe", "turkce"),
        "id" to listOf("indonesian", "bahasa indonesia", "indonesia"),
        "ms" to listOf("malay", "bahasa melayu", "melayu"),
        "th" to listOf("thai", "tha", "ภาษาไทย", "ไทย"),
        "vi" to listOf("vietnamese", "vie", "tiếng việt", "tieng viet"),
        "hi" to listOf("hindi", "hin", "हिंदी", "हिन्दी"),
        "fil" to listOf("filipino", "tagalog"),
        "nl" to listOf("dutch", "nederlands"), "sv" to listOf("swedish", "svenska"), "cs" to listOf("czech", "čeština"),
        "hu" to listOf("hungarian", "magyar"), "ro" to listOf("romanian", "română"), "el" to listOf("greek", "ελληνικά"),
        "he" to listOf("hebrew", "עברית"), "fi" to listOf("finnish", "suomi"), "da" to listOf("danish", "dansk"),
        "no" to listOf("norwegian", "norsk"), "ca" to listOf("catalan", "català"), "ta" to listOf("tamil"), "te" to listOf("telugu"),
        "bn" to listOf("bengali", "bangla"),
    )

    private val CJK = Regex("""[぀-ヿ㐀-鿿฀-๿ऀ-ॿ؀-ۿ가-힯]""")

    private val MATCHERS: List<Pair<String, Regex>> = NAMES.map { (code, words) ->
        val (cjk, latin) = words.partition { CJK.containsMatchIn(it) }
        val parts = buildList {
            if (latin.isNotEmpty()) add("""(?<![\p{L}\d])(?:${latin.joinToString("|") { Regex.escape(it) }})(?![\p{L}\d])""")
            if (cjk.isNotEmpty()) add("""(?:${cjk.joinToString("|") { Regex.escape(it) }})""") // CJK has no word boundaries
        }
        code to Regex(parts.joinToString("|"), RegexOption.IGNORE_CASE)
    }

    /** Every language named anywhere in [text]. */
    fun find(text: String): Set<String> = MATCHERS.filter { it.second.containsMatchIn(text) }.mapTo(LinkedHashSet()) { it.first }
}
