package app.neifur

import app.neifur.data.WatchEntry
import app.neifur.data.a365.ListEntry
import app.neifur.data.a365.ListSort
import app.neifur.data.a365.ListStatus
import app.neifur.data.a365.ListedTitle
import app.neifur.data.a365.ListsState
import app.neifur.data.a365.Series
import app.neifur.data.a365.Titles
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ordering of the "Смотрю" list and the Home "Новые серии" row. */
class ListsWatchingTest {
    private fun item(
        id: Long,
        title: String,
        status: ListStatus = ListStatus.Watching,
        watched: Int = 0,
        aired: Int? = null,
        airing: Boolean = false,
    ) = id to ListedTitle(
        entry = ListEntry(id, title, watched, 12, null, status),
        series = Series(id = id, isAiring = if (airing) 1 else 0),
        aired = aired,
    )

    private fun entry(id: String, ep: Int, at: Long) =
        WatchEntry(animeId = id, title = "T$id", episode = ep, positionSec = 100.0, durationSec = 1400.0, updatedAt = at)

    @Test fun recentlyWatchedThenNewEpisodesThenAiringThenSiteOrder() {
        val state = ListsState(
            loaded = true,
            titles = linkedMapOf(
                item(1, "Alpha", watched = 5, aired = 5, airing = false),
                item(2, "Beta", watched = 5, aired = 5, airing = true),
                item(3, "Gamma", watched = 4, aired = 6, airing = true),
                item(4, "Delta", watched = 2, aired = 2, airing = false),
                item(5, "Epsilon", watched = 1, aired = 2, airing = true),
                item(6, "Zeta", watched = 5, aired = 5, airing = false),
                item(7, "Planned", status = ListStatus.Planned, watched = 0, aired = 4, airing = true),
            ),
        )
        val history = listOf(
            entry("4", ep = 2, at = 200),
            entry("5", ep = 1, at = 500),
        )
        // Watched on this phone first (most recent on top), then new episodes, then airing, then alphabetical tie-break.
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L, 6L), state.watching(history).map { it.entry.seriesId })
    }

    @Test fun latestEpisodeTimestampWinsAcrossMultipleEntries() {
        val state = ListsState(
            loaded = true,
            titles = linkedMapOf(
                item(10, "One"),
                item(20, "Two"),
            ),
        )
        val history = listOf(
            entry("10", ep = 1, at = 100),
            entry("20", ep = 1, at = 300),
            entry("10", ep = 2, at = 500),
            entry("not-a-number", ep = 1, at = 999),
        )
        assertEquals(listOf(10L, 20L), state.watching(history).map { it.entry.seriesId })
    }

    @Test fun fullHistoryBeyondTwelveTitlesStillRanksFreshEpisodes() {
        val titles = linkedMapOf<Long, ListedTitle>()
        val history = mutableListOf<WatchEntry>()
        // 15 titles watched on the phone; #15 (oldest) and #99 (never watched here) both have a new episode.
        for (i in 1L..15L) {
            titles += item(i, "Title $i", watched = 3, aired = if (i == 15L) 4 else 3, airing = true)
            history += entry(i.toString(), ep = 3, at = 1000L - i)
        }
        titles += item(99L, "Aaa Never Watched Here", watched = 1, aired = 2, airing = true)
        val state = ListsState(loaded = true, titles = titles)

        val fresh = state.watching(history).filter { it.behind > 0 }
        assertEquals(listOf(15L, 99L), fresh.map { it.entry.seriesId })
    }

    @Test fun displayFiltersAcrossLanguagesAndFindsMatchesInOtherTabs() {
        val frieren = 1L to ListedTitle(
            ListEntry(1, "Провожающая в последний путь Фрирен", 28, 28, 10, ListStatus.Completed),
            Series(1, year = 2023, myAnimeListScore = 9.3, titles = Titles(ru = "Фрирен", romaji = "Sousou no Frieren", en = "Frieren: Beyond Journey's End")),
        )
        val bleach = 2L to ListedTitle(
            ListEntry(2, "Блич", 12, 52, null, ListStatus.Watching),
            Series(2, year = 2004, myAnimeListScore = 7.9, titles = Titles(ru = "Блич", romaji = "Bleach")),
        )
        val state = ListsState(loaded = true, titles = linkedMapOf(frieren, bleach))

        assertEquals(listOf(1L), state.display(ListStatus.Completed, emptyList(), query = "frieren").map { it.entry.seriesId })
        assertEquals(listOf(1L), state.display(ListStatus.Completed, emptyList(), query = "фрирен").map { it.entry.seriesId })
        assertEquals(emptyList<Long>(), state.display(ListStatus.Watching, emptyList(), query = "frieren").map { it.entry.seriesId })
        assertEquals(listOf(ListStatus.Completed to 1), state.matchesInOtherTabs(ListStatus.Watching, "frieren"))
    }

    @Test fun displaySortsByScoreYearTitleAndProgress() {
        val a = 1L to ListedTitle(ListEntry(1, "Второй", 5, 12, 8, ListStatus.Completed), Series(1, year = 2022, myAnimeListScore = 8.9, titles = Titles(ru = "Второй")))
        val b = 2L to ListedTitle(ListEntry(2, "Атака", 24, 24, 10, ListStatus.Completed), Series(2, year = 2013, myAnimeListScore = 8.5, titles = Titles(ru = "Атака")))
        val c = 3L to ListedTitle(ListEntry(3, "Новый", 12, 12, null, ListStatus.Completed), Series(3, year = 2026, myAnimeListScore = 9.1, titles = Titles(ru = "Новый")))
        val state = ListsState(loaded = true, titles = linkedMapOf(a, b, c))

        assertEquals(listOf(2L, 1L, 3L), state.display(ListStatus.Completed, emptyList(), sort = ListSort.Score).map { it.entry.seriesId })
        assertEquals(listOf(3L, 1L, 2L), state.display(ListStatus.Completed, emptyList(), sort = ListSort.Year).map { it.entry.seriesId })
        assertEquals(listOf(2L, 1L, 3L), state.display(ListStatus.Completed, emptyList(), sort = ListSort.Title).map { it.entry.seriesId })
        assertEquals(listOf(2L, 3L, 1L), state.display(ListStatus.Completed, emptyList(), sort = ListSort.Progress).map { it.entry.seriesId })
    }
}
