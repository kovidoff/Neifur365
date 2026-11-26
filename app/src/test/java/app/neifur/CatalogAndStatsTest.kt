package app.neifur

import app.neifur.data.a365.ListEntry
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.ListedTitle
import app.neifur.data.a365.ListsState
import app.neifur.data.a365.Series
import app.neifur.data.a365.SeriesGenre
import app.neifur.data.pushRecentSearch
import app.neifur.ui.screens.CatalogQuery
import app.neifur.ui.screens.catalogFilterSummary
import app.neifur.ui.screens.computeWatchStats
import app.neifur.ui.screens.formatWatchTime
import app.neifur.ui.screens.pickRandomSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class CatalogAndStatsTest {
    @Test
    fun recentSearchNormalisesDeduplicatesAndCaps() {
        val initial = listOf("Фрирен", "Наруто", "Блич")
        assertEquals(initial, pushRecentSearch(initial, " "))
        assertEquals(initial, pushRecentSearch(initial, "а"))
        assertEquals(
            listOf("наруто", "Фрирен", "Блич"),
            pushRecentSearch(initial, "  наруто  "),
        )
        assertEquals(
            listOf("Магическая битва", "Фрирен", "Наруто"),
            pushRecentSearch(initial, "Магическая   битва", limit = 3),
        )
    }

    @Test
    fun pickRandomRespectsExcludedAndListedFallback() {
        val pool = listOf(Series(id = 1), Series(id = 2), Series(id = 3))
        val rng = Random(42)

        // Excludes 1 (seen) and 2 (listed), leaving only 3.
        val picked = pickRandomSeries(pool, excludeIds = setOf(1), listedIds = setOf(2), hideListed = true, random = rng)
        assertEquals(3L, picked?.id)

        // When every unseen title is listed, falls back to unseen listed titles instead of returning null.
        val fallback = pickRandomSeries(pool, excludeIds = setOf(1), listedIds = setOf(2, 3), hideListed = true, random = rng)
        assertEquals(true, fallback?.id in setOf(2L, 3L))

        // When everything is excluded, returns null so caller can start a new cycle.
        assertNull(pickRandomSeries(pool, excludeIds = setOf(1, 2, 3), listedIds = emptySet(), hideListed = false, random = rng))

        // Strictly hiding listed titles returns null when all unseen items in pool are already listed.
        assertNull(pickRandomSeries(pool, excludeIds = setOf(1), listedIds = setOf(2, 3), hideListed = true, random = rng, strictHideListed = true))
    }

    @Test
    fun catalogFilterSummaryReflectsActiveFilters() {
        assertEquals("По всему каталогу", catalogFilterSummary(CatalogQuery()))
        assertEquals(
            "«Фрирен» · TV сериал · Онгоинг · 2024",
            catalogFilterSummary(CatalogQuery(search = "Фрирен", kind = "tv", airing = true, year = 2024)),
        )
    }

    @Test
    fun computeWatchStatsCalculatesTimeScoresAndGenres() {
        val titles = mapOf(
            1L to ListedTitle(
                ListEntry(1, "Frieren", watched = 28, total = 28, score = 10, status = ListStatus.Completed),
                Series(id = 1, type = "tv", genres = listOf(SeriesGenre(1, "Фэнтези"), SeriesGenre(2, "Драма"), SeriesGenre(1, "Фэнтези"))),
            ),
            2L to ListedTitle(
                ListEntry(2, "Your Name", watched = 1, total = 1, score = 9, status = ListStatus.Completed),
                Series(id = 2, type = "movie", genres = listOf(SeriesGenre(2, "Драма"), SeriesGenre(3, "Романтика"))),
            ),
            3L to ListedTitle(
                ListEntry(3, "Watching Show", watched = 6, total = 12, score = 8, status = ListStatus.Watching),
                Series(id = 3, type = "tv", genres = listOf(SeriesGenre(1, "Фэнтези"))),
            ),
            4L to ListedTitle(
                ListEntry(4, "Planned Show", watched = 0, total = 12, score = null, status = ListStatus.Planned),
                Series(id = 4, type = "tv", genres = listOf(SeriesGenre(4, "Комедия"))),
            ),
        )
        val stats = computeWatchStats(ListsState(loaded = true, titles = titles))
        assertEquals(4, stats.totalTitles)
        assertEquals(3, stats.touchedTitles)
        assertEquals(2, stats.completedTitles)
        assertEquals(35, stats.totalEpisodes)
        // (28 + 6) * 24 + 1 * 95 = 816 + 95 = 911 min
        assertEquals(911, stats.estimatedMinutes)
        assertEquals(9.0, stats.meanScore!!, 0.001)
        assertEquals(3, stats.ratedCount)
        assertEquals(1, stats.scoreHistogram[10])
        assertEquals(1, stats.scoreHistogram[9])
        assertEquals(1, stats.scoreHistogram[8])
        assertEquals(0, stats.scoreHistogram[7])
        // Planned genres are excluded when active genres exist; Фэнтези (2) and Драма (2) tie alphabetically -> Драма, Фэнтези
        assertEquals(listOf("Драма" to 2, "Фэнтези" to 2, "Романтика" to 1), stats.topGenres)
    }

    @Test
    fun formatWatchTimeCoversMinutesHoursAndDays() {
        assertEquals("0 ч" to "пока без серий", formatWatchTime(0))
        assertEquals("48 мин" to "меньше часа", formatWatchTime(48))
        assertEquals("15 ч" to "примерно по 24 мин", formatWatchTime(911))
        assertEquals("48 ч" to "≈ 2.0 дн.", formatWatchTime(48 * 60))
    }
}
