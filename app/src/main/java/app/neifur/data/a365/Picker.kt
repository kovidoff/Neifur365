package app.neifur.data.a365

/**
 * Chooses which translation plays. Studios are compared by [Translation.pickKey] (kind + studio), so picking
 * "AniLibria" once follows the viewer from episode to episode even though each episode has its own ids.
 */
object Picker {
    /** Same studio, several uploads (TV, BD, other heights): the best one for the viewer's quality setting. */
    fun best(variants: List<Translation>, quality: Int): Translation? = variants.maxWithOrNull(
        compareBy<Translation>(
            { if (it.height in 1..quality) 1 else 0 },          // never exceed the chosen quality if avoidable
            { if (it.height <= quality) it.height else -it.height },
            { if (it.qualityType == "bd") 1 else 0 },
            { it.priority },
        )
    )

    fun choose(
        all: List<Translation>, pick: String?, preferred: TranslationKind, favourites: List<String>, quality: Int,
    ): Translation? {
        val active = all.filter { it.shown }
        if (active.isEmpty()) return null
        val byKey = active.groupBy { it.pickKey }
        pick?.let { k -> byKey[k]?.let { return best(it, quality) } }
        // A studio the viewer chose on other titles, in the preferred kind first.
        favourites.sortedByDescending { it.startsWith(preferred.name + "|") }
            .firstNotNullOfOrNull { byKey[it] }?.let { return best(it, quality) }
        val order = listOf(preferred) + TranslationKind.shown.filter { it != preferred }
        for (k in order) {
            val ofKind = active.filter { it.kind == k }
            if (ofKind.isEmpty()) continue
            // The site's own ranking: its priority puts the most-watched studios on top.
            val top = ofKind.maxBy { it.priority }
            return best(byKey.getValue(top.pickKey), quality)
        }
        return null
    }

    /** The list a viewer picks from: one row per studio, grouped by kind, most popular first. */
    fun options(all: List<Translation>, quality: Int): Map<TranslationKind, List<Translation>> =
        all.filter { it.shown }.groupBy { it.pickKey }.values
            .mapNotNull { best(it, quality) }
            .groupBy { it.kind }
            .mapValues { (_, v) -> v.sortedByDescending { it.priority } }
            .toSortedMap(compareBy { it.ordinal })
}
