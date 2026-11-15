package app.neifur.data.a365

/**
 * One translation of a whole title, as a viewer thinks of it: "AniLibria, voice-over" across all episodes.
 * Anime 365 stores a translation per episode; a studio gathers them by [Translation.pickKey].
 */
data class Studio(
    val key: String,
    val kind: TranslationKind,
    val author: String,
    /** Voice actors / translators, when the site lists them. */
    val people: List<String>,
    /** The studio's uploads per episode id (several when it has TV and BD versions). */
    val byEpisode: Map<Long, List<Translation>>,
    val bestHeight: Int,
    val hasBd: Boolean,
    val priority: Long,
) {
    fun has(episodeId: Long): Boolean = byEpisode.containsKey(episodeId)

    /** How many of [episodes] this studio covers. */
    fun coverage(episodes: List<Episode>): Int = episodes.count { has(it.id) }

    val qualityLabel: String
        get() = listOfNotNull(bestHeight.takeIf { it > 0 }?.let { "${minOf(it, MAX_STREAM_HEIGHT)}p" }, "BD".takeIf { hasBd }).joinToString(" · ")
}

object Studios {
    /** Groups a title's translations into studios. Inactive uploads don't count. */
    fun build(translations: List<Translation>): List<Studio> =
        translations.filter { it.shown }.groupBy { it.pickKey }.map { (key, list) ->
            val first = list.maxBy { it.priority }
            Studio(
                key = key, kind = first.kind, author = first.author,
                people = list.flatMap { it.authorsList }.map { it.trim() }.filter { it.isNotEmpty() && it != first.author }.distinct().take(6),
                byEpisode = list.groupBy { it.episodeId },
                bestHeight = list.maxOf { it.height }, hasBd = list.any { it.qualityType == "bd" },
                priority = list.maxOf { it.priority },
            )
        }

    /** Studios in the order the picker shows them: by kind (the site's order), then most episodes, then popularity. */
    fun grouped(studios: List<Studio>, episodes: List<Episode>): Map<TranslationKind, List<Studio>> =
        studios.groupBy { it.kind }
            .mapValues { (_, v) -> v.sortedWith(compareByDescending<Studio> { it.coverage(episodes) }.thenByDescending { it.priority }) }
            .toSortedMap(compareBy { it.ordinal })

    /**
     * The studio a title opens with: the one picked for it before; else a favourite studio that has this title;
     * else, in the preferred kind, the studio that covers the most episodes from [from] on (ties: the site's
     * popularity). Falls back to the other kinds in the site's order.
     */
    fun choose(
        studios: List<Studio>, pick: String?, preferred: TranslationKind, favourites: List<String>,
        episodes: List<Episode>, from: Int,
    ): Studio? {
        if (studios.isEmpty()) return null
        studios.firstOrNull { it.key == pick }?.let { return it }
        val ahead = episodes.drop(from.coerceAtLeast(0)).ifEmpty { episodes }
        favourites.sortedByDescending { it.startsWith(preferred.name + "|") }
            .firstNotNullOfOrNull { f -> studios.firstOrNull { it.key == f && it.coverage(ahead) > 0 } }?.let { return it }
        val order = listOf(preferred) + TranslationKind.shown.filter { it != preferred }
        for (k in order) {
            val best = studios.filter { it.kind == k }
                .maxWithOrNull(compareBy<Studio> { it.coverage(ahead) }.thenBy { it.priority })
            if (best != null) return best
        }
        return null
    }

    /**
     * Which episodes a studio has, in words: "все 12 серий", "11 из 12 · нет 1", "серии 2–5, 8 из 12".
     * Episode numbers are the site's; specials without a number count but aren't named.
     */
    fun coverageText(studio: Studio, episodes: List<Episode>): String {
        val total = episodes.size
        val have = episodes.filter { studio.has(it.id) }
        if (total == 0) return ""
        if (have.size == total) return if (total == 1) "есть" else "все $total ${plural(total, "серия", "серии", "серий")}"
        if (have.isEmpty()) return "серий пока нет"
        val missing = episodes.filterNot { studio.has(it.id) }
        return if (missing.size <= 3) "${have.size} из $total · нет ${ranges(missing)}"
        else "есть ${ranges(have)} из $total"
    }

    /** "1–5, 7, 9–12" from episode numbers. */
    fun ranges(eps: List<Episode>): String {
        val nums = eps.map { it.number }.filter { it > 0 }.distinct().sorted()
        if (nums.isEmpty()) return "${eps.size}"
        val out = ArrayList<String>()
        var start = nums[0]; var prev = nums[0]
        for (n in nums.drop(1) + Int.MIN_VALUE) {
            if (n == prev + 1) { prev = n; continue }
            out += if (start == prev) "$start" else "$start–$prev"
            start = n; prev = n
        }
        return if (out.size > 4) out.take(3).joinToString(", ") + "…" else out.joinToString(", ")
    }

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        return when { m10 == 1 && m100 != 11 -> one; m10 in 2..4 && m100 !in 12..14 -> few; else -> many }
    }
}
